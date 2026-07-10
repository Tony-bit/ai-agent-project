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
public class QueryDecompositionResult {
    private Boolean multiTask;
    private String reasoning;
    @Builder.Default
    private List<DecomposedTask> taskList = List.of();
}
