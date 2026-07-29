package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.cache.TradingNamespaceKeyFactory;
import denny.ai.agent.trading.api.context.TradingTargetContextKeys;
import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.Objects;
import java.util.Map;

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
        TargetContext target = requireMatchingTarget(context, dynamicContext, nodeName);
        String sessionId = dynamicContext.getValue(TradingStateContext.TRADING_SESSION_ID_KEY);
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "no-session";
        }
        String key = TradingNamespaceKeyFactory.chatMemory(
                sessionId, context.getTargetContext(), nodeName);
        return request
                .toolContext(Map.of(TradingTargetContextKeys.TARGET_CONTEXT, target))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, key));
    }

    private static TargetContext requireMatchingTarget(
            TradingContextVO context,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
            String nodeName) {
        Object value = dynamicContext.getValue(TradingTargetContextKeys.TARGET_CONTEXT);
        if (!(value instanceof TargetContext target)) {
            throw identityViolation(nodeName, context.getTargetContext(),
                    value == null ? "dynamic target is missing" : "dynamic target has invalid type");
        }
        TargetContext contextTarget = context.getTargetContext();
        if (contextTarget == null) {
            throw identityViolation(nodeName, target, "TradingContextVO target is missing");
        }
        if (!target.runId().equals(contextTarget.runId())
                || !target.targetId().equals(contextTarget.targetId())) {
            throw identityViolation(nodeName, target, "dynamic target does not match TradingContextVO");
        }
        return target;
    }

    private static IllegalStateException identityViolation(
            String nodeName, TargetContext target, String reason) {
        return new IllegalStateException(String.format(
                "IDENTITY_BOUNDARY_VIOLATION: %s; nodeName=%s runId=%s targetId=%s",
                reason,
                nodeName == null ? "unknown" : nodeName,
                target == null ? "unknown" : target.runId(),
                target == null ? "unknown" : target.targetId()));
    }
}
