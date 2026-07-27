package denny.ai.agent.trading.api.vo.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RiskAssessmentPayload(
        @NotBlank @Pattern(regexp = "AGGRESSIVE|CONSERVATIVE|NEUTRAL") String perspective,
        @NotNull @Min(1) @Max(5) Integer riskScore,
        @NotNull @Size(min = 1, max = 10) List<@NotBlank String> riskItems,
        @NotNull @Size(min = 1, max = 10) List<@NotBlank String> mitigations,
        @NotBlank String summary,
        @Valid TargetEchoPayload targetEcho
) {
}
