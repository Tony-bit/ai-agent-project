package denny.ai.agent.trading.api.vo.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RecommendationPayload(
        @NotBlank @Pattern(regexp = "BUY|SELL|HOLD") String action,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double positionRatio,
        @NotBlank String entryPriceRange,
        @NotBlank String stopLossPrice,
        @NotBlank String takeProfitPrice,
        @NotBlank String holdingPeriod,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) Double riskRewardRatio,
        @NotBlank String rationale,
        @Valid TargetEchoPayload targetEcho
) {
}
