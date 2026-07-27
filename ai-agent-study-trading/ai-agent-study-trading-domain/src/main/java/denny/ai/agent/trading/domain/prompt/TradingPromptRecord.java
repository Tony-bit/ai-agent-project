package denny.ai.agent.trading.domain.prompt;

public record TradingPromptRecord(
        Long id,
        String promptId,
        int promptType,
        int version,
        String content,
        boolean active
) {
}
