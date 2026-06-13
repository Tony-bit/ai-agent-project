package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.valobj.DecomposedTask;
import denny.ai.agent.domain.model.valobj.SubTask;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Component
public class TaskGraphValidator {

    public void validateDecomposedTasks(List<DecomposedTask> tasks) {
        validate(tasks, DecomposedTask::getTaskId, DecomposedTask::getTaskIndex,
                DecomposedTask::getTotalTasks, DecomposedTask::getContent, DecomposedTask::getDependsOn);
    }

    public void validateSubTasks(List<SubTask> tasks) {
        validate(tasks, SubTask::getTaskId, SubTask::getTaskIndex,
                SubTask::getTotalTasks, SubTask::getContent, SubTask::getDependsOn);
    }

    private <T> void validate(List<T> tasks,
                              Function<T, String> idGetter,
                              Function<T, Integer> indexGetter,
                              Function<T, Integer> totalGetter,
                              Function<T, String> contentGetter,
                              Function<T, List<String>> dependencyGetter) {
        if (tasks == null || tasks.isEmpty()) {
            throw new TaskGraphValidationException("task list must not be empty");
        }

        Map<String, Integer> positions = new HashMap<>();
        Set<Integer> indexes = new HashSet<>();
        for (int i = 0; i < tasks.size(); i++) {
            T task = tasks.get(i);
            if (task == null) {
                throw new TaskGraphValidationException("task must not be null");
            }
            String taskId = idGetter.apply(task);
            Integer taskIndex = indexGetter.apply(task);
            if (!StringUtils.hasText(taskId)) {
                throw new TaskGraphValidationException("taskId must not be blank");
            }
            if (positions.put(taskId, i) != null) {
                throw new TaskGraphValidationException("duplicate taskId: " + taskId);
            }
            if (!StringUtils.hasText(contentGetter.apply(task))) {
                throw new TaskGraphValidationException("content must not be blank: " + taskId);
            }
            if (taskIndex == null || taskIndex != i + 1 || !indexes.add(taskIndex)) {
                throw new TaskGraphValidationException("taskIndex must be unique and match list order: " + taskId);
            }
            if (totalGetter.apply(task) == null || totalGetter.apply(task) != tasks.size()) {
                throw new TaskGraphValidationException("totalTasks mismatch: " + taskId);
            }
        }

        for (int i = 0; i < tasks.size(); i++) {
            T task = tasks.get(i);
            String taskId = idGetter.apply(task);
            List<String> dependencies = dependencyGetter.apply(task);
            if (dependencies == null) {
                continue;
            }
            for (String dependency : dependencies) {
                Integer dependencyPosition = positions.get(dependency);
                if (dependencyPosition == null) {
                    throw new TaskGraphValidationException("unknown dependency: " + dependency);
                }
                if (taskId.equals(dependency)) {
                    throw new TaskGraphValidationException("task cannot depend on itself: " + taskId);
                }
                if (dependencyPosition >= i) {
                    throw new TaskGraphValidationException("dependency must precede task: " + taskId);
                }
            }
        }
    }
}
