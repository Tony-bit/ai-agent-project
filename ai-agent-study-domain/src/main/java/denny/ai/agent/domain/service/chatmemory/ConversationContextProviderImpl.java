package denny.ai.agent.domain.service.chatmemory;

import denny.ai.agent.domain.model.entity.ChatConversationContext;
import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.CompressionConversationContext;
import denny.ai.agent.domain.model.entity.ConversationMemoryOptions;
import denny.ai.agent.domain.model.entity.ConversationMemorySnapshot;
import denny.ai.agent.domain.model.entity.RoutingConversationContext;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConversationContextProviderImpl implements ConversationContextProvider {

    @Resource
    private ConversationMemoryService conversationMemoryService;

    @Value("${chat.memory.runtime-window-size:${chat.memory.max-cache-size:20}}")
    private int runtimeWindowSize = ConversationMemoryOptions.DEFAULT_WINDOW_SIZE;

    @Override
    public RoutingConversationContext getRoutingContext(String sessionId) {
        return routingContext(sessionId);
    }

    @Override
    public RoutingConversationContext getDecompositionContext(String sessionId) {
        return routingContext(sessionId);
    }

    @Override
    public RoutingConversationContext getSlotContext(String sessionId) {
        return routingContext(sessionId);
    }

    @Override
    public ChatConversationContext getChatContext(String sessionId) {
        ConversationMemorySnapshot snapshot = loadRuntimeSnapshot(sessionId);
        return ChatConversationContext.builder()
                .sessionId(sessionId)
                .recentMessages(snapshot.getRecentMessages())
                .summary(snapshot.getSummary())
                .durable(snapshot.isDurable())
                .build();
    }

    @Override
    public CompressionConversationContext getCompressionContext(String sessionId) {
        ConversationMemorySnapshot snapshot = loadRuntimeSnapshot(sessionId);
        return CompressionConversationContext.builder()
                .sessionId(sessionId)
                .recentMessages(snapshot.getRecentMessages())
                .summary(snapshot.getSummary())
                .durable(snapshot.isDurable())
                .build();
    }

    private RoutingConversationContext routingContext(String sessionId) {
        ConversationMemorySnapshot snapshot = loadRuntimeSnapshot(sessionId);
        List<String> history = snapshot.getRecentMessages().stream()
                .filter(message -> message.getRole() != null && message.getContent() != null)
                .map(message -> message.getRole() + ": " + message.getContent())
                .toList();
        return RoutingConversationContext.builder()
                .sessionId(sessionId)
                .historyMessages(history)
                .durable(snapshot.isDurable())
                .build();
    }

    private ConversationMemorySnapshot loadRuntimeSnapshot(String sessionId) {
        return conversationMemoryService.loadSnapshot(sessionId, ConversationMemoryOptions.builder()
                .windowSize(runtimeWindowSize)
                .allowRuntimeWindow(true)
                .build());
    }
}
