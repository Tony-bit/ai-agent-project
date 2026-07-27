package denny.ai.agent.trading.api.vo.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ResearchManagerPayload(
        @NotBlank @Pattern(regexp = "DECIDED|INSUFFICIENT_DATA") String status,
        @DecimalMin("-5.0") @DecimalMax("5.0") Double overallScore,
        @NotNull Boolean needMoreDebate,
        @NotNull @Size(max = 8) List<@NotBlank String> decisiveFactors,
        @NotNull @Size(max = 8) List<@NotBlank String> dataQualityWarnings,
        @NotBlank String conclusion,
        @Valid TargetEchoPayload targetEcho
) {
}
