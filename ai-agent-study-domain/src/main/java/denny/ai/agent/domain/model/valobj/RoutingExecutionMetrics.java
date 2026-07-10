package denny.ai.agent.domain.model.valobj;

import denny.ai.agent.domain.service.auto.step.routing.IntentRoutingMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RoutingExecutionMetrics {
    private IntentRoutingMode mode;
    private Long totalLatencyMs;
    private Integer totalPromptTokens;
    private Integer totalCompletionTokens;
    private Integer totalTokens;
    private Boolean estimated;
    @Builder.Default
    private List<RoutingStageMetric> stageMetrics = new ArrayList<>();

    public void addStage(RoutingStageMetric stage) {
        if (stageMetrics == null) {
            stageMetrics = new ArrayList<>();
        }
        stageMetrics.add(stage);
        totalPromptTokens = value(totalPromptTokens) + value(stage.getPromptTokens());
        totalCompletionTokens = value(totalCompletionTokens) + value(stage.getCompletionTokens());
        totalTokens = value(totalTokens) + value(stage.getTotalTokens());
        estimated = Boolean.TRUE.equals(estimated) || Boolean.TRUE.equals(stage.getEstimatedTokens());
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }
}
