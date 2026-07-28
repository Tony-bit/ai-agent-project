package denny.ai.agent.trading.domain.cleanup;

public record TradingCleanupPromptInventory(
        long v1Active,
        long v1Archived,
        long v2Active,
        long v2Archived,
        long v3Active,
        long v3Archived,
        long duplicates,
        long orphans
) {
}
