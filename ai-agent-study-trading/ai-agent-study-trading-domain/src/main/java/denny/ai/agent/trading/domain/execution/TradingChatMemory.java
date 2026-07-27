package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.cache.TradingNamespaceKeyFactory;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.Objects;

public final class TradingChatMemory {

    private TradingChatMemory() {
    }

    public static ChatClient.ChatClientRequestSpec apply(
            ChatClient.ChatClientRequestSpec request,
            TradingContextVO context,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
            String nodeName) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(dynamicContext, "dynamicContext");
        String sessionId = dynamicContext.getValue(TradingStateContext.TRADING_SESSION_ID_KEY);
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "no-session";
        }
        String key = TradingNamespaceKeyFactory.chatMemory(
                sessionId, context.getTargetContext(), nodeName);
        return request.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, key));
    }
}
