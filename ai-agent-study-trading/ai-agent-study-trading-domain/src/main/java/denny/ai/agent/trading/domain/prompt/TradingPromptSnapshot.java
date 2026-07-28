package denny.ai.agent.trading.domain.prompt;

import java.util.Map;

public record TradingPromptSnapshot(String runId,
                                    PromptContractMode mode,
                                    int version,
                                    Map<String, PromptVersion> prompts) {
    public TradingPromptSnapshot {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        prompts = Map.copyOf(prompts);
        if (mode == null || mode != PromptContractMode.fromVersion(version)) {
            throw new IllegalArgumentException("snapshot mode does not match version");
        }
        if (!prompts.keySet().equals(TradingPromptSet.REQUIRED_PROMPT_IDS)) {
            throw new IllegalArgumentException("snapshot must contain the complete trading prompt set");
        }
        if (prompts.values().stream().anyMatch(prompt -> prompt.version() != version
                || prompt.mode() != mode)) {
            throw new IllegalArgumentException("snapshot cannot mix prompt versions or modes");
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
