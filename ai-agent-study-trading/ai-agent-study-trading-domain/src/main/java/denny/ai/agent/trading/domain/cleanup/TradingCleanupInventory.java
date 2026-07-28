package denny.ai.agent.trading.domain.cleanup;

public record TradingCleanupInventory(
        boolean acceptancePassed,
        boolean stableObservationWindowPassed,
        boolean rollbackWindowClosed,
        boolean allConsumersMigrated,
        long legacyCodeReferences,
        TradingCleanupPromptInventory prompts,
        TradingCleanupRuntimeInventory strictV2Runtime,
        TradingCleanupRuntimeInventory relaxedV3Runtime,
        long expiredV2Caches,
        long expiredV2Snapshots,
        long shadowRunDetails,
        TradingCleanupDisposition disposition
) {
    public boolean gatesPassed() {
        return acceptancePassed && stableObservationWindowPassed && rollbackWindowClosed
                && allConsumersMigrated && strictV2Runtime.activeRuns() == 0
                && strictV2Runtime.retries() == 0
                && strictV2Runtime.recoverableSnapshots() == 0;
    }
}
