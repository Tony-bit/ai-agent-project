package denny.ai.agent.trading.api.vo.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ResearchManagerDecisionV3(
        @NotBlank @Pattern(regexp = "BUY|SELL|HOLD|INSUFFICIENT_DATA") String recommendation,
        @NotBlank String reasoning
) {
    public ResearchManagerDecisionV3 {
        recommendation = DecisionWordNormalizer.normalizeResearch(recommendation);
        reasoning = DecisionWordNormalizer.trim(reasoning);
    }
}
