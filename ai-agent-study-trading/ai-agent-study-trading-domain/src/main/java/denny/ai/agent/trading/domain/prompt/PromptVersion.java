package denny.ai.agent.trading.domain.prompt;

public record PromptVersion(
        String promptId,
        int version,
        PromptContractMode mode,
        String content,
        String contentHash
) {
    public PromptVersion {
        if (promptId == null || promptId.isBlank()) {
            throw new IllegalArgumentException("promptId must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (mode == null || mode != PromptContractMode.fromVersion(version)) {
            throw new IllegalArgumentException("prompt mode does not match version");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (contentHash == null || !contentHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("contentHash must be a SHA-256 hex value");
        }
    }
}
