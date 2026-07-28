package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.domain.vo.TradingContextVO;

import java.util.Objects;

public final class FinalDecisionIdentityGuard {

    private FinalDecisionIdentityGuard() {
    }

    public static void requireBound(TargetContext target,
                                    TradingContextVO.FinalTradeDecisionVO decision) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(decision, "decision");
        if (!target.targetId().equals(decision.getTargetId())
                || !target.stockName().equals(decision.getStockName())) {
            throw new IllegalStateException("IDENTITY_BOUNDARY_VIOLATION: final decision target mismatch");
        }
    }
}
