package denny.ai.agent.trading.api.vo.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record PortfolioDecisionPayload(
        @NotBlank @Pattern(regexp = "BUY|SELL|HOLD|SKIP") String decision,
        @NotBlank @Pattern(regexp = "HIGH|MEDIUM|LOW") String confidence,
        @NotNull @DecimalMin("1.0") @DecimalMax("5.0") Double overallRating,
        @NotBlank String reasoning,
        @NotNull List<@NotBlank String> warnings,
        @Valid TargetEchoPayload targetEcho
) {
}
