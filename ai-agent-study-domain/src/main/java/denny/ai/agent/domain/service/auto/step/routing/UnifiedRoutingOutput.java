package denny.ai.agent.domain.service.auto.step.routing;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UnifiedRoutingOutput {

    private Boolean multiTask;
    private Boolean needsClarification;
    private List<String> missingInfo;
    private String clarificationPrompt;
    private String reasoning;
    private List<TaskOutput> taskList;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TaskOutput {
        private String taskId;
        private Integer taskIndex;
        private Integer totalTasks;
        private String content;
        private String intent;
        private String confidence;
        private Map<String, Object> slots;
        private List<String> dependsOn;
    }
}
