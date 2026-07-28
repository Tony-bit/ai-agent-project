package denny.ai.agent.trading.domain.cleanup;

import java.time.Instant;
import java.util.List;

public record TradingCleanupReport(
        String mode,
        Instant generatedAt,
        TradingCleanupInventory inventory,
        boolean executable,
        List<String> blockedReasons,
        String approvedBatchId,
        long deletedObjects
) {
    public TradingCleanupReport {
        blockedReasons = List.copyOf(blockedReasons);
    }
}
