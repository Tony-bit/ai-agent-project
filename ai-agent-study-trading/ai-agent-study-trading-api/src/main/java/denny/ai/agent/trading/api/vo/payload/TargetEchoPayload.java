package denny.ai.agent.trading.api.vo.payload;

import jakarta.validation.constraints.NotBlank;

public record TargetEchoPayload(
        @NotBlank String ticker,
        @NotBlank String stockName
) {
}
