package denny.ai.agent.trading.domain.cleanup;

public record TradingCleanupDisposition(
        long plannedDeletions,
        long plannedArchives,
        long plannedRetentions,
        String primaryKeyRange,
        String backupLocation
) {
}
