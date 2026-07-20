package denny.ai.agent.domain.service.auto.step.routing;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.DecomposedTask;
import denny.ai.agent.domain.model.valobj.IntentRoutingResult;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.QueryDecompositionResult;
import denny.ai.agent.domain.model.valobj.RoutingExecutionMetrics;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.chatmemory.ConversationContextProvider;
import denny.ai.agent.domain.service.runtime.RuntimeHistorySupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service("taskRoutingSlotNode")
public class TaskRoutingSlotNode extends AbstractExecuteSupport {
    @Resource
    private IntentRoutingService intentRoutingService;
    @Resource
    private TaskGraphValidator taskGraphValidator;
    @Resource
    private RoutingResultHandler routingResultHandler;
    @Resource
    private ConversationContextProvider conversationContextProvider;

    @Override
    protected String doApply(ExecuteCommandEntity request,
                             DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) throws Exception {
        QueryDecompositionResult decomposition = context.getValue(QueryDecompositionNode.DECOMPOSITION_RESULT_KEY);
        if (decomposition == null) {
            throw new IllegalStateException("Missing query decomposition result");
        }
        RoutingExecutionMetrics metrics = context.getValue(RoutingResultHandler.METRICS_KEY);
        AiAgentClientFlowConfigVO config = context.getAiAgentClientFlowConfigVOMap()
                .get(AiClientTypeEnumVO.INTENT_ROUTING.getCode());
        List<String> history = history(request.getSessionId(), context);
        List<DecomposedTask> ordered = decomposition.getTaskList().stream()
                .sorted(Comparator.comparing(DecomposedTask::getTaskIndex))
                .toList();
        List<SubTask> tasks = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            DecomposedTask task = ordered.get(i);
            IntentRoutingService.RoutingCallResult<IntentRoutingResult> call =
                    intentRoutingService.routeTaskIntentSlotsWithMetric(
                            task.getContent(), task.getTaskId(), i + 1, history, config,
                            request.getSessionId());
            metrics.addStage(call.metric());
            tasks.add(intentRoutingService.toSubTask(task, call.result()));
        }

        MultiIntentRoutingResult result = intentRoutingService.buildSplitResult(decomposition, tasks);
        try {
            taskGraphValidator.validateSubTasks(tasks);
        } catch (TaskGraphValidationException e) {
            result = intentRoutingService.fallbackMultiIntentResult(
                    "Task graph validation failed: " + e.getMessage());
        }
        Long startedAt = context.getValue(QueryDecompositionNode.ROUTING_STARTED_AT_KEY);
        metrics.setTotalLatencyMs(startedAt == null ? 0L : System.currentTimeMillis() - startedAt);
        result.setMetrics(metrics);
        return routingResultHandler.handle(request, context, result);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity request,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) {
        return routingResultHandler.select(context);
    }

    private List<String> history(String sessionId,
                                 DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) {
        return RuntimeHistorySupport.preparedHistory(context)
                .orElseGet(() -> {
                    try {
                        return conversationContextProvider.getSlotContext(sessionId).getHistoryMessages();
                    } catch (Exception e) {
                        log.warn("Failed to load conversation history: sessionId={}, error={}", sessionId, e.getMessage());
                        return List.of();
                    }
                });
    }
}
