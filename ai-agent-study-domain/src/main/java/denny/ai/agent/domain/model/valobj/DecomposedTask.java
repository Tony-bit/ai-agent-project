package denny.ai.agent.domain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DecomposedTask {
    private String taskId;
    private Integer taskIndex;
    private Integer totalTasks;
    private String content;
    @Builder.Default
    private List<String> dependsOn = List.of();
}
