package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.valobj.DecomposedTask;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import org.junit.Test;

import java.util.List;
import java.util.Map;

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

    @Test
    public void acceptsValidGraphForBothTaskModels() {
        validator.validateDecomposedTasks(List.of(
                task("a", 1, 2, null),
                task("b", 2, 2, List.of("a"))));

        validator.validateSubTasks(List.of(
                subTask("a", 1, 2, List.of()),
                subTask("b", 2, 2, List.of("a"))));
    }

    @Test
    public void rejectsBlankTaskId() {
        assertThrows(TaskGraphValidationException.class, () -> validator.validateDecomposedTasks(List.of(
                task(" ", 1, 1, List.of()))));
    }

    @Test
    public void rejectsBlankContent() {
        assertThrows(TaskGraphValidationException.class, () -> validator.validateDecomposedTasks(List.of(
                DecomposedTask.builder()
                        .taskId("a")
                        .taskIndex(1)
                        .totalTasks(1)
                        .content(" ")
                        .dependsOn(List.of())
                        .build())));
    }

    @Test
    public void rejectsNullTask() {
        assertThrows(TaskGraphValidationException.class, () -> validator.validateDecomposedTasks(
                java.util.Collections.singletonList(null)));
    }

    @Test
    public void rejectsInvalidSubTaskGraph() {
        assertThrows(TaskGraphValidationException.class, () -> validator.validateSubTasks(List.of(
                subTask("a", 1, 2, List.of("missing")),
                subTask("b", 2, 2, List.of()))));
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

    private SubTask subTask(String id, int index, int total, List<String> dependsOn) {
        return SubTask.builder()
                .taskId(id)
                .taskIndex(index)
                .totalTasks(total)
                .content("task " + id)
                .dependsOn(dependsOn)
                .intent(IntentTypeEnum.GENERAL_CHAT)
                .executorNode("generalChatNode")
                .confidence(ConfidenceEnum.HIGH)
                .slots(Map.of())
                .taskType(0)
                .status(SubTask.SubTaskStatus.PENDING)
                .build();
    }
}
