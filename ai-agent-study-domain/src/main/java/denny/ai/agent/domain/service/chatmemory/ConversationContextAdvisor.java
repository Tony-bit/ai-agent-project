package denny.ai.agent.domain.service.chatmemory;

import denny.ai.agent.domain.model.entity.RoutingConversationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class ConversationContextAdvisor implements BaseAdvisor {

    public static final String CHAT_MEMORY_CONVERSATION_ID_KEY = ChatMemory.CONVERSATION_ID;
    public static final String CONVERSATION_CONTEXT_SCENE_KEY = "conversation_context_scene";
    public static final String CONVERSATION_CONTEXT_PRELOADED_KEY = "conversation_context_preloaded";
    public static final String SCENE_CHAT = "chat";
    public static final String SCENE_ROUTING = "routing";
    public static final String SCENE_DECOMPOSITION = "decomposition";
    public static final String SCENE_SLOT = "slot";

    private final ConversationContextProvider conversationContextProvider;
    private final MessageChatMemoryAdvisor chatMemoryAdvisor;
    private final int order;

    public ConversationContextAdvisor(ConversationContextProvider conversationContextProvider,
                                      SpringAiConversationMemoryRepository chatMemoryRepository,
                                      int maxMessages) {
        this.conversationContextProvider = conversationContextProvider;
        this.chatMemoryAdvisor = MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(chatMemoryRepository)
                        .maxMessages(Math.max(maxMessages, 1))
                        .build()
        ).build();
        this.order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        String scene = scene(request.context());
        if (SCENE_CHAT.equals(scene)) {
            return chatMemoryAdvisor.before(request, advisorChain);
        }
        String conversationId = conversationId(request.context());
        if (conversationId == null || conversationId.isBlank()) {
            log.debug("未提供 conversationId，跳过场景化上下文注入: scene={}", scene);
            return request;
        }
        if (contextPreloaded(request.context())) {
            return request;
        }
        RoutingConversationContext context = switch (scene) {
            case SCENE_ROUTING -> conversationContextProvider.getRoutingContext(conversationId);
            case SCENE_DECOMPOSITION -> conversationContextProvider.getDecompositionContext(conversationId);
            case SCENE_SLOT -> conversationContextProvider.getSlotContext(conversationId);
            default -> conversationContextProvider.getChatContext(conversationId).getRecentMessages().isEmpty()
                    ? RoutingConversationContext.builder().sessionId(conversationId).build()
                    : conversationContextProvider.getRoutingContext(conversationId);
        };
        if (context.getHistoryMessages() == null || context.getHistoryMessages().isEmpty()) {
            return request;
        }
        return request.mutate()
                .prompt(promptWithContext(request.prompt(), scene, context.getHistoryMessages()))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        if (SCENE_CHAT.equals(scene(chatClientResponse.context()))) {
            return chatMemoryAdvisor.after(chatClientResponse, advisorChain);
        }
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return order;
    }

    private String scene(Map<String, Object> context) {
        Object scene = context == null ? null : context.get(CONVERSATION_CONTEXT_SCENE_KEY);
        return scene == null ? SCENE_CHAT : scene.toString();
    }

    private String conversationId(Map<String, Object> context) {
        Object conversationId = context == null ? null : context.get(CHAT_MEMORY_CONVERSATION_ID_KEY);
        return conversationId == null ? null : conversationId.toString();
    }

    private boolean contextPreloaded(Map<String, Object> context) {
        Object preloaded = context == null ? null : context.get(CONVERSATION_CONTEXT_PRELOADED_KEY);
        return Boolean.TRUE.equals(preloaded) || "true".equalsIgnoreCase(String.valueOf(preloaded));
    }

    private Prompt promptWithContext(Prompt original, String scene, List<String> historyMessages) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildContextText(scene, historyMessages)));
        messages.addAll(original.getInstructions());
        return new Prompt(messages, original.getOptions());
    }

    private String buildContextText(String scene, List<String> historyMessages) {
        return """
                [Conversation Context]
                scene: %s

                %s
                [/Conversation Context]
                """.formatted(scene, String.join("\n", historyMessages));
    }
}
