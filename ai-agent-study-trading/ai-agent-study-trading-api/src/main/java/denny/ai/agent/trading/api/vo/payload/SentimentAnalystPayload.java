package denny.ai.agent.trading.api.vo.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SentimentAnalystPayload(
        @NotNull @Min(1) @Max(5) Integer rating,
        @NotNull @DecimalMin("-1.0") @DecimalMax("1.0") Double sentimentScore,
        @NotNull @Size(min = 1, max = 8) List<@NotBlank String> keySentiments,
        @NotBlank String summary,
        @Valid TargetEchoPayload targetEcho
) {
}
