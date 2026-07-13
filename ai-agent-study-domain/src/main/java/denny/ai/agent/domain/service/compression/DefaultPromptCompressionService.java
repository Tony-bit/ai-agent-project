package denny.ai.agent.domain.service.compression;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.service.armory.factory.element.AiErrorCodeExtractor;
import denny.ai.agent.domain.service.armory.factory.element.AiErrorCodes;
import denny.ai.agent.domain.service.armory.factory.element.CompressionPolicy;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.domain.service.runtime.RetryRuntimeContextHolder;
import denny.ai.agent.domain.util.TokenCountUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DefaultPromptCompressionService implements PromptCompressionService {

    static final String COMPRESSION_CLIENT_BEAN = ArmoryObjectRegistry.COMPRESSION_CHAT_CLIENT;
    private static final int REQUEST_OVERHEAD_TOKENS = 1024;
    private static final Pattern SUMMARY_PATTERN = Pattern.compile("(?i)<摘要>([\\s\\S]*?)</摘要>");

    private final ApplicationContext applicationContext;
    private final ArmoryObjectRegistry armoryObjectRegistry;
    private final AiErrorCodeExtractor errorCodeExtractor;

    public DefaultPromptCompressionService(ApplicationContext applicationContext) {
        this(applicationContext, new ArmoryObjectRegistry(), new AiErrorCodeExtractor());
    }

    @Autowired
    public DefaultPromptCompressionService(ApplicationContext applicationContext,
                                           ArmoryObjectRegistry armoryObjectRegistry) {
        this(applicationContext, armoryObjectRegistry, new AiErrorCodeExtractor());
    }

    DefaultPromptCompressionService(ApplicationContext applicationContext,
                                    ArmoryObjectRegistry armoryObjectRegistry,
                                    AiErrorCodeExtractor errorCodeExtractor) {
        this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext must not be null");
        this.armoryObjectRegistry = Objects.requireNonNull(armoryObjectRegistry,
                "armoryObjectRegistry must not be null");
        this.errorCodeExtractor = Objects.requireNonNull(errorCodeExtractor, "errorCodeExtractor must not be null");
    }

    @Override
    public Prompt compress(Prompt originalPrompt,
                           RetryRuntimeContext runtimeContext,
                           CompressionPolicy policy) {
        Objects.requireNonNull(originalPrompt, "originalPrompt must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        List<ChatMessageEntity> history = resolveHistory(originalPrompt, runtimeContext);
        if (history.isEmpty()) {
            throw new CompressionExhaustedException("no compressible history is available");
        }

        int inputBudget = Math.max(1,
                policy.getProactiveThresholdTokens() - policy.getMaxSummaryTokens() - REQUEST_OVERHEAD_TOKENS);
        ClientSelection clientSelection = resolveCompressionClient(policy);
        String request = buildCompressionRequest(history, policy.getMaxSummaryTokens(), inputBudget);
        log.info("[CompressionRequest] traceId={}, sessionId={}, historyMessages={}, inputBudget={}, requestTokens={}, completeClient={}",
                runtimeContext == null ? null : runtimeContext.getTraceId(),
                runtimeContext == null ? null : runtimeContext.getSessionId(),
                history.size(), inputBudget, TokenCountUtils.estimate(request), clientSelection.completeBean());
        RetryRuntimeContext compressionContext = runtimeContext == null
                ? RetryRuntimeContext.builder().compressionCall(true).recentMessages(history).build()
                : runtimeContext.forCompressionCall();

        String summary;
        try {
            summary = RetryRuntimeContextHolder.withContext(compressionContext,
                    () -> clientSelection.client().prompt(request).call().content());
        } catch (RuntimeException error) {
            if (AiErrorCodes.CONTEXT_OVERFLOW.equals(errorCodeExtractor.extract(error))) {
                throw new CompressionExhaustedException(
                        "compression request exceeds compression model context window", error);
            }
            throw error;
        }

        String formattedSummary = formatSummary(summary);
        log.info("[CompressionResponse] traceId={}, sessionId={}, summaryTokens={}",
                runtimeContext == null ? null : runtimeContext.getTraceId(),
                runtimeContext == null ? null : runtimeContext.getSessionId(),
                TokenCountUtils.estimate(formattedSummary));
        return rebuildPrompt(originalPrompt, formattedSummary);
    }

    private ClientSelection resolveCompressionClient(CompressionPolicy policy) {
        ChatClient registryClient = armoryObjectRegistry.get(COMPRESSION_CLIENT_BEAN);
        if (registryClient != null) {
            return new ClientSelection(registryClient, true);
        }
        if (applicationContext.containsBean(COMPRESSION_CLIENT_BEAN)) {
            return new ClientSelection(applicationContext.getBean(COMPRESSION_CLIENT_BEAN, ChatClient.class), true);
        }
        try {
            return new ClientSelection(
                    applicationContext.getBean(COMPRESSION_CLIENT_BEAN, ChatClient.class), true);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Unable to resolve compression client alias="
                    + COMPRESSION_CLIENT_BEAN, error);
        }
    }

    private List<ChatMessageEntity> resolveHistory(Prompt prompt, RetryRuntimeContext runtimeContext) {
        if (runtimeContext != null && runtimeContext.getRecentMessages() != null
                && !runtimeContext.getRecentMessages().isEmpty()) {
            return runtimeContext.getRecentMessages().stream().filter(Objects::nonNull).toList();
        }
        List<Message> messages = prompt.getInstructions();
        int lastUserIndex = lastUserIndex(messages);
        if (lastUserIndex <= 0) {
            return List.of();
        }
        List<ChatMessageEntity> history = new ArrayList<>();
        for (int index = 0; index < lastUserIndex; index++) {
            Message message = messages.get(index);
            if (message instanceof SystemMessage || message.getText() == null || message.getText().isBlank()) {
                continue;
            }
            history.add(ChatMessageEntity.builder()
                    .role(message.getMessageType().getValue())
                    .content(message.getText())
                    .build());
        }
        return List.copyOf(history);
    }

    private String buildCompressionRequest(List<ChatMessageEntity> history,
                                           int maxSummaryTokens,
                                           int inputBudget) {
        String prefix = "[待压缩对话内容]\n";
        String suffix = "[/待压缩对话内容]\n请生成不超过 "
                + Math.max(1, maxSummaryTokens) + " tokens 的摘要。";
        int fixedTokens = TokenCountUtils.estimate(prefix + suffix);
        int historyBudget = Math.max(0, inputBudget - fixedTokens);
        List<String> selected = new ArrayList<>();
        int used = 0;
        for (int index = history.size() - 1; index >= 0; index--) {
            ChatMessageEntity message = history.get(index);
            if (message == null || message.getContent() == null) {
                continue;
            }
            String line = Objects.requireNonNullElse(message.getRole(), "unknown")
                    + ": " + message.getContent() + "\n\n";
            int lineTokens = TokenCountUtils.estimate(line);
            if (used + lineTokens <= historyBudget) {
                selected.add(line);
                used += lineTokens;
                continue;
            }
            if (selected.isEmpty() && historyBudget > used) {
                selected.add(truncateToTokens(line, historyBudget - used));
            }
            break;
        }
        Collections.reverse(selected);
        String request = prefix + String.join("", selected) + suffix;
        return truncateToTokens(request, inputBudget);
    }

    private String truncateToTokens(String text, int tokenLimit) {
        if (tokenLimit <= 0) {
            return "";
        }
        if (TokenCountUtils.estimate(text) <= tokenLimit) {
            return text;
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (TokenCountUtils.estimate(text.substring(0, middle)) <= tokenLimit) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return text.substring(0, low);
    }

    Prompt rebuildPrompt(Prompt originalPrompt, String summary) {
        List<Message> originalMessages = originalPrompt.getInstructions();
        int lastUserIndex = lastUserIndex(originalMessages);
        if (lastUserIndex < 0) {
            throw new CompressionExhaustedException("no current user message is available");
        }
        UserMessage currentUser = (UserMessage) originalMessages.get(lastUserIndex);
        List<Message> rebuilt = new ArrayList<>();
        originalMessages.stream()
                .filter(SystemMessage.class::isInstance)
                .forEach(rebuilt::add);
        rebuilt.add(new SystemMessage("[压缩边界]\n" + summary + "\n[压缩边界结束]"));
        rebuilt.add(currentUser);
        for (int index = lastUserIndex + 1; index < originalMessages.size(); index++) {
            Message message = originalMessages.get(index);
            if (!(message instanceof SystemMessage)) {
                rebuilt.add(message);
            }
        }
        return new Prompt(rebuilt, (ChatOptions) originalPrompt.getOptions());
    }

    String formatSummary(String rawSummary) {
        if (rawSummary == null || rawSummary.isEmpty()) {
            return "";
        }
        String formatted = rawSummary.replaceAll("(?i)<分析>[\\s\\S]*?</分析>", "");
        Matcher matcher = SUMMARY_PATTERN.matcher(formatted);
        if (matcher.find()) {
            formatted = matcher.group(1).trim();
        }
        return formatted.replaceAll("\n{3,}", "\n\n").trim();
    }

    private int lastUserIndex(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) {
                return index;
            }
        }
        return -1;
    }

    private record ClientSelection(ChatClient client, boolean completeBean) {
    }
}
