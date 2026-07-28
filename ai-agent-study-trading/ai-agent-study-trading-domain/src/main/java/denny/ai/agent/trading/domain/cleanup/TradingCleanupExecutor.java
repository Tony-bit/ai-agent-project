package denny.ai.agent.trading.domain.cleanup;

public interface TradingCleanupExecutor {
    long executeApprovedBatch(String batchId, TradingCleanupInventory inventory);
}
