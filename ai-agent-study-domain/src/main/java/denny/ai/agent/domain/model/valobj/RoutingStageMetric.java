package denny.ai.agent.domain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RoutingStageMetric {
    private String stageName;
    private String clientId;
    private String taskId;
    private Integer callIndex;
    private Long latencyMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Boolean estimatedTokens;
    private Boolean success;
    private String errorMessage;
    private String finalFailureType;
    private Boolean jsonModeEnabled;
    private Boolean schemaValidationEnabled;
    private Integer attemptCount;
    private String retryReasons;
}
