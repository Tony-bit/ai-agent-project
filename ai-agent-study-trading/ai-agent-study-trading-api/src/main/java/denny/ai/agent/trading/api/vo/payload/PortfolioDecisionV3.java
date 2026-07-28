package denny.ai.agent.trading.api.vo.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PortfolioDecisionV3(
        @NotBlank @Pattern(regexp = "BUY|SELL|HOLD|SKIP") String decision,
        @NotBlank String reasoning
) {
    public PortfolioDecisionV3 {
        decision = DecisionWordNormalizer.normalizeAction(decision, true);
        reasoning = DecisionWordNormalizer.trim(reasoning);
    }
}
