package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.valobj.DecomposedTask;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertThrows;

public class TaskGraphValidatorTest {
    private final TaskGraphValidator validator = new TaskGraphValidator();

    @Test
    public void acceptsValidOrderedGraph() {
        validator.validateDecomposedTasks(List.of(
                task("a", 1, 2, List.of()),
                task("b", 2, 2, List.of("a"))));
    }

    @Test
    public void rejectsDuplicateTaskId() {
        assertThrows(TaskGraphValidationException.class, () -> validator.validateDecomposedTasks(List.of(
                task("a", 1, 2, List.of()),
                task("a", 2, 2, List.of()))));
    }

    @Test
    public void rejectsMissingDependency() {
        assertThrows(TaskGraphValidationException.class, () -> validator.validateDecomposedTasks(List.of(
                task("a", 1, 1, List.of("missing")))));
    }

    @Test
    public void rejectsSelfDependency() {
        assertThrows(TaskGraphValidationException.class, () -> validator.validateDecomposedTasks(List.of(
                task("a", 1, 1, List.of("a")))));
    }

    @Test
    public void rejectsDependencyOnLaterTask() {
        assertThrows(TaskGraphValidationException.class, () -> validator.validateDecomposedTasks(List.of(
                task("a", 1, 2, List.of("b")),
                task("b", 2, 2, List.of()))));
    }

    @Test
    public void rejectsIndexAndTotalMismatch() {
        assertThrows(TaskGraphValidationException.class, () -> validator.validateDecomposedTasks(List.of(
                task("a", 2, 3, List.of()))));
    }

    private DecomposedTask task(String id, int index, int total, List<String> dependsOn) {
        return DecomposedTask.builder()
                .taskId(id)
                .taskIndex(index)
                .totalTasks(total)
                .content("task " + id)
                .dependsOn(dependsOn)
                .build();
    }
}
