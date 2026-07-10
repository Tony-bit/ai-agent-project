package denny.ai.agent.domain.service.auto.step.factory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.service.auto.step.RootNode;
import denny.ai.agent.domain.service.auto.step.routing.MultiTaskExecutionNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 工厂类
 *
 * @author denny
 * 2025/7/27 16:34
 */
@Service
public class DefaultAutoAgentExecuteStrategyFactory {

    private final RootNode executeRootNode;

    public DefaultAutoAgentExecuteStrategyFactory(RootNode executeRootNode) {
        this.executeRootNode = executeRootNode;
    }

    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> armoryStrategyHandler(){
        return executeRootNode;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        // 任务执行步骤
        private int step = 1;

        // 最大任务步骤
        private int maxStep = 1;

        private StringBuilder executionHistory;

        private String currentTask;

        /**
         * Langfuse traceId（一次完整任务生命周期）
         */
        private String traceId;

        boolean isCompleted = false;

        private Map<String, AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOMap;

        @Builder.Default
        private Map<String, Object> dataObjects = new HashMap<>();

        /**
         * 任务完成信号量。
         * 用于等待子任务/Agent 执行完成后再返回结果。
         */
        private CountDownLatch taskLatch;

        public <T> void setValue(String key, T value) {
            dataObjects.put(key, value);
        }

        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }

        public void putSubTaskResult(String taskId, SubTask subTask) {
            getSubTaskResults().put(taskId, subTask);
        }

        public SubTask getSubTaskResult(String taskId) {
            return getSubTaskResults().get(taskId);
        }

        public Map<String, SubTask> getAllSubTaskResults() {
            return getSubTaskResults();
        }

        public void clearSubTaskResults() {
            getSubTaskResults().clear();
        }

        public void clearMultiTaskContext() {
            dataObjects.remove(MultiTaskExecutionNode.TASK_LIST_KEY);
            dataObjects.remove(MultiTaskExecutionNode.ORIGINAL_MESSAGE_KEY);
        }

        @SuppressWarnings("unchecked")
        private Map<String, SubTask> getSubTaskResults() {
            Object results = dataObjects.get("subTaskResults");
            if (results instanceof Map<?, ?>) {
                return (Map<String, SubTask>) results;
            }
            Map<String, SubTask> subTaskResults = new HashMap<>();
            dataObjects.put("subTaskResults", subTaskResults);
            return subTaskResults;
        }
    }

}
