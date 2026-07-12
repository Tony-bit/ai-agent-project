package denny.ai.agent.domain.service.compression;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.valobj.enums.AiAgentEnumVO;
import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.service.armory.factory.element.AiErrorCodeExtractor;
import denny.ai.agent.domain.service.armory.factory.element.AiErrorCodes;
import denny.ai.agent.domain.service.armory.factory.element.CompressionPolicy;
import denny.ai.agent.domain.service.runtime.RetryRuntimeContextHolder;
import denny.ai.agent.domain.util.TokenCountUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.ApplicationContext;
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

    static final String COMPRESSION_CLIENT_BEAN = "aiClientCOMPRESSION_ASSISTANTtaskType1";
    private static final int REQUEST_OVERHEAD_TOKENS = 1024;
    private static final Pattern SUMMARY_PATTERN = Pattern.compile("(?i)<摘要>([\\s\\S]*?)</摘要>");

    private final ApplicationContext applicationContext;
    private final AiErrorCodeExtractor errorCodeExtractor;

    public DefaultPromptCompressionService(ApplicationContext applicationContext) {
        this(applicationContext, new AiErrorCodeExtractor());
    }

    DefaultPromptCompressionService(ApplicationContext applicationContext,
                                    AiErrorCodeExtractor errorCodeExtractor) {
        this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext must not be null");
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
        return rebuildPrompt(originalPrompt, formattedSummary);
    }

    private ClientSelection resolveCompressionClient(CompressionPolicy policy) {
        if (applicationContext.containsBean(COMPRESSION_CLIENT_BEAN)) {
            return new ClientSelection(applicationContext.getBean(COMPRESSION_CLIENT_BEAN, ChatClient.class), true);
        }
        String modelBeanName = AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(policy.getCompressionModelId());
        try {
            ChatModel model = applicationContext.getBean(modelBeanName, ChatModel.class);
            ChatClient client = ChatClient.builder(model)
                    .defaultSystem(Objects.requireNonNullElse(policy.getPromptTemplate(), ""))
                    .defaultOptions(OpenAiChatOptions.builder()
                            .maxTokens(Math.max(1, policy.getMaxSummaryTokens()))
                            .build())
                    .build();
            return new ClientSelection(client, false);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Unable to resolve compression client bean="
                    + COMPRESSION_CLIENT_BEAN + " or modelId=" + policy.getCompressionModelId(), error);
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
