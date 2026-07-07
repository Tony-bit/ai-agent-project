package denny.ai.agent.domain.service.chatmemory;

import denny.ai.agent.domain.model.entity.ChatConversationContext;
import denny.ai.agent.domain.model.entity.CompressionConversationContext;
import denny.ai.agent.domain.model.entity.RoutingConversationContext;

public interface ConversationContextProvider {

    RoutingConversationContext getRoutingContext(String sessionId);

    RoutingConversationContext getDecompositionContext(String sessionId);

    RoutingConversationContext getSlotContext(String sessionId);

    ChatConversationContext getChatContext(String sessionId);

    CompressionConversationContext getCompressionContext(String sessionId);
}
