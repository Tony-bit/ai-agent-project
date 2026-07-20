package denny.ai.agent.domain.service.auto.step.routing;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.QueryDecompositionResult;
import denny.ai.agent.domain.model.valobj.RoutingExecutionMetrics;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.chatmemory.ConversationContextProvider;
import denny.ai.agent.domain.service.runtime.RuntimeHistorySupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service("queryDecompositionNode")
public class QueryDecompositionNode extends AbstractExecuteSupport {
    public static final String DECOMPOSITION_RESULT_KEY = "queryDecompositionResult";
    static final String ROUTING_STARTED_AT_KEY = "intentRoutingStartedAt";

    @Resource
    private IntentRoutingService intentRoutingService;
    @Resource
    private TaskGraphValidator taskGraphValidator;
    @Resource
    private ConversationContextProvider conversationContextProvider;
    @Resource
    private TaskRoutingSlotNode taskRoutingSlotNode;

    @Override
    protected String doApply(ExecuteCommandEntity request,
                             DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) throws Exception {
        long startedAt = System.currentTimeMillis();
        AiAgentClientFlowConfigVO config = requireConfig(context);
        IntentRoutingService.RoutingCallResult<QueryDecompositionResult> call =
                intentRoutingService.decomposeQueryWithMetric(
                        request.getMessage(), history(request.getSessionId(), context), config,
                        request.getSessionId());
        QueryDecompositionResult result = call.result();
        try {
            taskGraphValidator.validateDecomposedTasks(result.getTaskList());
            result.setMultiTask(result.getTaskList().size() > 1);
        } catch (TaskGraphValidationException e) {
            call.metric().setSuccess(false);
            call.metric().setErrorMessage(e.getMessage());
            result = intentRoutingService.fallbackDecomposition(
                    request.getMessage(), "Task graph validation failed: " + e.getMessage());
        }
        RoutingExecutionMetrics metrics = RoutingExecutionMetrics.builder()
                .mode(IntentRoutingMode.SPLIT)
                .totalLatencyMs(0L)
                .totalPromptTokens(0)
                .totalCompletionTokens(0)
                .totalTokens(0)
                .estimated(false)
                .stageMetrics(new ArrayList<>())
                .build();
        metrics.addStage(call.metric());
        context.setValue(DECOMPOSITION_RESULT_KEY, result);
        context.setValue(RoutingResultHandler.METRICS_KEY, metrics);
        context.setValue(ROUTING_STARTED_AT_KEY, startedAt);
        return router(request, context);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity request,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) {
        return taskRoutingSlotNode;
    }

    private AiAgentClientFlowConfigVO requireConfig(DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) {
        AiAgentClientFlowConfigVO config = context.getAiAgentClientFlowConfigVOMap()
                .get(AiClientTypeEnumVO.INTENT_ROUTING.getCode());
        if (config == null) {
            throw new IllegalStateException("Missing INTENT_ROUTING client configuration");
        }
        return config;
    }

    private List<String> history(String sessionId,
                                 DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) {
        return RuntimeHistorySupport.preparedHistory(context)
                .orElseGet(() -> {
                    try {
                        return conversationContextProvider.getDecompositionContext(sessionId).getHistoryMessages();
                    } catch (Exception e) {
                        log.warn("Failed to load conversation history: sessionId={}, error={}", sessionId, e.getMessage());
                        return List.of();
                    }
                });
    }
}
