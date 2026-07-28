package denny.ai.agent.trading.api.vo;

import java.util.Set;

public record NarrativeNodeResult(String role, String rawText) {

    private static final Set<String> ROLES = Set.of(
            "BULL", "BEAR", "NEUTRAL", "CONSERVATIVE", "AGGRESSIVE");

    public NarrativeNodeResult {
        role = normalize(role, "role");
        rawText = normalize(rawText, "rawText");
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("unsupported narrative role: " + role);
        }
    }

    private static String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
