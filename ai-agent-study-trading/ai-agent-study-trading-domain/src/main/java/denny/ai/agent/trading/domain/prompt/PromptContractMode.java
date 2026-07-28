package denny.ai.agent.trading.domain.prompt;

public enum PromptContractMode {
    STRICT_V2,
    RELAXED_V3;

    public static PromptContractMode fromVersion(int version) {
        if (version < 1) {
            throw new IllegalArgumentException("prompt version must be positive");
        }
        return version >= 3 ? RELAXED_V3 : STRICT_V2;
    }
}
