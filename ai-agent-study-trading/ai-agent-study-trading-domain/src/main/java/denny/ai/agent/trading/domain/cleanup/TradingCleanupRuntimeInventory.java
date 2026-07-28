package denny.ai.agent.trading.domain.cleanup;

public record TradingCleanupRuntimeInventory(
        long activeRuns,
        long retries,
        long recoverableSnapshots,
        long caches,
        long dualWriteRecords
) {
}
