package denny.ai.agent.trading.api.vo.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record FundamentalAnalystPayload(
        @NotNull @Min(1) @Max(5) Integer rating,
        @NotNull @Size(min = 1, max = 8) List<@NotBlank String> keyFindings,
        @NotNull @Size(max = 8) List<@NotBlank String> riskWarnings,
        @NotBlank String summary,
        @Valid TargetEchoPayload targetEcho
) {
}
