package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.function.Supplier;

/** Records failures at the actual LLM invocation boundary. */
@Slf4j
public final class TradingLlmCallAudit {

    private TradingLlmCallAudit() {
    }

    public static <T> T execute(TradingContextVO context,
                                String clientId,
                                String nodeName,
                                Supplier<T> invocation) {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(nodeName, "nodeName");
        Objects.requireNonNull(invocation, "invocation");
        try {
            TargetContext target = context == null ? null : context.getTargetContext();
            if (target == null) {
                throw new IllegalStateException(
                        "IDENTITY_BOUNDARY_VIOLATION: LLM invocation has no targetContext");
            }
            return invocation.get();
        } catch (RuntimeException error) {
            TargetContext target = context == null ? null : context.getTargetContext();
            log.error("trading_llm_execution_failed runId={} targetId={} clientId={} nodeName={}",
                    target == null ? "unknown" : target.runId(),
                    target == null ? "unknown" : target.targetId(),
                    clientId, nodeName, error);
            throw error;
        }
    }
}
