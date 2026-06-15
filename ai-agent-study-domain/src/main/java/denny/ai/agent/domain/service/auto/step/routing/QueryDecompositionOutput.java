package denny.ai.agent.domain.service.auto.step.routing;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class QueryDecompositionOutput {

    private Boolean multiTask;
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
        private List<String> dependsOn;
    }
}
