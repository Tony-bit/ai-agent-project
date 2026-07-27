package denny.ai.agent.trading.api.vo.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record NewsAnalystPayload(
        @NotNull @Min(1) @Max(5) Integer rating,
        @NotBlank @Pattern(regexp = "positive|negative|neutral|mixed") String overallSentiment,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
        @NotNull List<Integer> enhancedSourceNewsIds,
        @NotNull List<@Valid NewsEventPayload> deduplicatedEvents,
        @NotNull List<@Valid NewsThemePayload> newsThemes,
        @NotNull List<@Valid NewsRiskPayload> riskWarnings,
        @NotBlank String dataQuality,
        @NotBlank String summary,
        @Valid TargetEchoPayload targetEcho
) {
    public record NewsEventPayload(
            @NotBlank String eventType,
            @NotBlank String eventTitle,
            @NotBlank @Pattern(regexp = "positive|negative|neutral|mixed") String sentiment,
            @NotBlank @Pattern(regexp = "high|medium|low") String impactLevel,
            @NotNull @Size(min = 1) List<Integer> sourceNewsIds,
            @NotNull List<Integer> enhancedSourceNewsIds,
            @NotBlank String evidenceLevel,
            @NotBlank String evidenceQuality,
            @NotBlank String summary) {
    }

    public record NewsThemePayload(
            @NotBlank String theme,
            @NotBlank @Pattern(regexp = "positive|negative|neutral|mixed") String sentiment,
            @NotBlank @Pattern(regexp = "high|medium|low") String impactLevel,
            @NotNull @Size(min = 1) List<Integer> evidenceIds,
            @NotNull List<Integer> enhancedSourceNewsIds,
            @NotBlank String evidenceLevel,
            @NotBlank String evidenceQuality,
            @NotBlank String reason) {
    }

    public record NewsRiskPayload(
            @NotBlank String risk,
            @NotBlank @Pattern(regexp = "high|medium|low") String impactLevel,
            @NotNull @Size(min = 1) List<Integer> evidenceIds,
            @NotNull List<Integer> enhancedSourceNewsIds,
            @NotBlank String evidenceLevel,
            @NotBlank String evidenceQuality,
            @NotBlank String reason) {
    }
}
