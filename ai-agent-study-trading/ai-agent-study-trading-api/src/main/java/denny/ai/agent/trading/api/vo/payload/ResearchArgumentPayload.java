package denny.ai.agent.trading.api.vo.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ResearchArgumentPayload(
        @NotBlank @Pattern(regexp = "BULL|BEAR") String stance,
        @NotNull @Size(min = 1, max = 8) List<@Valid EvidenceArgument> keyEvidence,
        @NotNull @Size(max = 8) List<@NotBlank String> risks,
        @NotBlank String summary,
        @Valid TargetEchoPayload targetEcho
) {
    public record EvidenceArgument(
            @NotBlank @Pattern(regexp = "FACT|INFERENCE") String type,
            @NotBlank @Pattern(regexp = "HIGH|MEDIUM|LOW") String confidence,
            @NotBlank String claim) {
    }
}
