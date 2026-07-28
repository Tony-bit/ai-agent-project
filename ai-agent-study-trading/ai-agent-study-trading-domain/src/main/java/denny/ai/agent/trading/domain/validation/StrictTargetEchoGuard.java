package denny.ai.agent.trading.domain.validation;

import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.api.vo.payload.TargetEchoPayload;

public final class StrictTargetEchoGuard {

    private StrictTargetEchoGuard() {
    }

    public static void requireMatch(TargetContext target, TargetEchoPayload echo) {
        if (echo == null) {
            return;
        }
        if (!target.stockCode().equals(echo.ticker()) || !target.stockName().equals(echo.stockName())) {
            throw new IllegalArgumentException("STRICT_V2 targetEcho does not match TargetContext");
        }
    }
}
