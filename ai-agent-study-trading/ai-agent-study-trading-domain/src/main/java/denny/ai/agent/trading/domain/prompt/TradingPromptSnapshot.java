package denny.ai.agent.trading.domain.prompt;

import java.util.Map;

public record TradingPromptSnapshot(String runId, Map<String, PromptVersion> prompts) {
    public TradingPromptSnapshot {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        prompts = Map.copyOf(prompts);
        if (!prompts.keySet().equals(TradingPromptSet.REQUIRED_PROMPT_IDS)) {
            throw new IllegalArgumentException("snapshot must contain the complete trading prompt set");
        }
    }

    public PromptVersion require(String promptId) {
        PromptVersion prompt = prompts.get(promptId);
        if (prompt == null) {
            throw new IllegalArgumentException("prompt is not part of this run: " + promptId);
        }
        return prompt;
    }
}
