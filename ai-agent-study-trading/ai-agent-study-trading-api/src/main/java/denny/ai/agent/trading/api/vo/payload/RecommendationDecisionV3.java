package denny.ai.agent.trading.api.vo.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RecommendationDecisionV3(
        @NotBlank @Pattern(regexp = "BUY|SELL|HOLD") String action,
        @NotBlank String rationale
) {
    public RecommendationDecisionV3 {
        action = DecisionWordNormalizer.normalizeAction(action, false);
        rationale = DecisionWordNormalizer.trim(rationale);
    }
}
