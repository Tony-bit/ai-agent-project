package denny.ai.agent.domain.service.armory.factory.element;

import com.alibaba.fastjson.JSON;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import denny.ai.agent.domain.adapter.repository.IRagKnowledgeRepository;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class RagAnswerAdvisor implements BaseAdvisor {

    private final IRagKnowledgeRepository ragKnowledgeRepository;
    private final SearchRequest searchRequest;
    private final String userTextAdvise;

    public RagAnswerAdvisor(IRagKnowledgeRepository ragKnowledgeRepository, SearchRequest searchRequest) {
        this.ragKnowledgeRepository = ragKnowledgeRepository;
        this.searchRequest = searchRequest;
        this.userTextAdvise = "\nContext information is below, surrounded by ---------------------\n\n---------------------\n{question_answer_context}\n---------------------\n\nGiven the context and provided history information and not prior knowledge,\nreply to the user comment. If the answer is not in the context, inform\nthe user that you can't answer the question.\n";
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        Map<String, Object> context = new HashMap<>(chatClientRequest.context());

        String userText = chatClientRequest.prompt().getUserMessage().getText();
        String advisedUserText = userText + System.lineSeparator() + this.userTextAdvise;

        int topK = this.searchRequest != null ? this.searchRequest.getTopK() : 5;
        String userId = doGetUserId(context);

        List<String> queries = buildQueries(userText);
        List<String> contexts = new ArrayList<>();
        Set<String> uniqueContexts = new LinkedHashSet<>();
        for (String query : queries) {
            String ragContext = ragKnowledgeRepository.retrieveContext(userId, query, topK);
            contexts.add(ragContext);
            if (ragContext != null) {
                uniqueContexts.add(ragContext);
            }
        }

        String mergedContext = uniqueContexts.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(System.lineSeparator()));

        context.put("qa_retrieved_documents", mergedContext);

        Map<String, Object> advisedUserParams = new HashMap<>(chatClientRequest.context());
        advisedUserParams.put("question_answer_context", mergedContext);

        return ChatClientRequest.builder()
                .prompt(Prompt.builder().messages(new UserMessage(advisedUserText), new AssistantMessage(JSON.toJSONString(advisedUserParams))).build())
                .context(advisedUserParams)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        ChatResponse.Builder chatResponseBuilder = ChatResponse.builder().from(chatClientResponse.chatResponse());
        chatResponseBuilder.metadata("qa_retrieved_documents", chatClientResponse.context().get("qa_retrieved_documents"));
        ChatResponse chatResponse = chatResponseBuilder.build();

        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(chatClientResponse.context())
                .build();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(this.before(chatClientRequest, callAdvisorChain));
        return this.after(chatClientResponse, callAdvisorChain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        return BaseAdvisor.super.adviseStream(chatClientRequest, streamAdvisorChain);
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    protected String doGetUserId(Map<String, Object> context) {
        return context.containsKey("user_id")
                ? String.valueOf(context.get("user_id"))
                : "";
    }

    private List<String> buildQueries(String userText) {
        if (!StringUtils.hasText(userText)) {
            return List.of();
        }
        String normalizedText = userText.trim();
        List<String> queries = new ArrayList<>();
        queries.add(normalizedText);

        for (String part : Arrays.asList(normalizedText.split("[。！？!?\\n]+"))) {
            String trimmed = part.trim();
            if (StringUtils.hasText(trimmed) && trimmed.length() >= 3) {
                queries.add(trimmed);
            }
        }

        queries.addAll(extractKeywordsAsQueries(normalizedText));

        return queries.stream().distinct().collect(Collectors.toList());
    }

    private List<String> extractKeywordsAsQueries(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> keywords = tokenizeText(text).stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(token -> token.length() >= 2)
                .filter(token -> !isStopWord(token))
                .distinct()
                .limit(6)
                .collect(Collectors.toList());

        if (keywords.isEmpty()) {
            return List.of();
        }

        return List.of(String.join(" ", keywords));
    }

    private List<String> tokenizeText(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String part : text.split("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+")) {
            String trimmed = part.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            if (containsChinese(trimmed)) {
                tokens.addAll(splitChineseTokens(trimmed));
            } else {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    private List<String> splitChineseTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        String cleaned = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        if (!StringUtils.hasText(cleaned)) {
            return List.of();
        }
        if (cleaned.length() <= 4) {
            tokens.add(cleaned);
            return tokens;
        }
        for (int i = 0; i < cleaned.length(); i++) {
            int end = Math.min(i + 2, cleaned.length());
            if (end - i >= 2) {
                tokens.add(cleaned.substring(i, end));
            }
        }
        return tokens;
    }

    private boolean containsChinese(String text) {
        return text.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FA5);
    }

    private boolean isStopWord(String token) {
        String lower = token.toLowerCase();
        return Set.of(
                "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "be", "as", "by",
                "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个", "上", "也", "很", "到", "说",
                "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这", "那", "与", "及", "或", "而", "被"
        ).contains(lower);
    }

}
