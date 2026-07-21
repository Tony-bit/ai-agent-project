package denny.ai.agent.domain.service.auto.step.routing;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.chat.GeneralChatNode;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.pe.Step1AnalyzerNode;
import denny.ai.agent.domain.service.auto.step.react.IntelligentInspection;
import denny.ai.agent.domain.service.chatmemory.ConversationContextProvider;
import denny.ai.agent.domain.service.observability.ObservabilityService;
import denny.ai.agent.domain.service.runtime.RuntimeHistorySupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service("intentRoutingNode")
public class IntentRoutingNode extends AbstractExecuteSupport {
    public static final String RECOGNIZED_INTENT_KEY = RoutingResultHandler.RECOGNIZED_INTENT_KEY;
    public static final String ROUTING_RESULT_KEY = RoutingResultHandler.ROUTING_RESULT_KEY;
    public static final String BASE_SLOT_KEY = RoutingResultHandler.BASE_SLOT_KEY;
    public static final String INTENT_SPECIFIC_SLOTS_KEY = RoutingResultHandler.INTENT_SPECIFIC_SLOTS_KEY;
    public static final String STOCK_SLOT_KEY = RoutingResultHandler.STOCK_SLOT_KEY;

    @Resource
    private IntentRoutingService intentRoutingService;
    @Resource
    private AnalysisDepthFollowUpResolver analysisDepthFollowUpResolver;
    @Resource
    private ConversationContextProvider conversationContextProvider;
    @Resource
    private TaskGraphValidator taskGraphValidator;
    @Resource
    private RoutingResultHandler routingResultHandler;
    @Resource
    private Step1AnalyzerNode step1AnalyzerNode;
    @Resource
    private IntelligentInspection intelligentInspection;
    @Resource
    private GeneralChatNode generalChatNode;
    @Resource
    private MultiTaskExecutionNode multiTaskExecutionNode;
    @Resource
    private ObservabilityService observabilityService;

    @Override
    protected String doApply(ExecuteCommandEntity request,
                             DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) throws Exception {
        AiAgentClientFlowConfigVO config = context.getAiAgentClientFlowConfigVOMap()
                .get(AiClientTypeEnumVO.INTENT_ROUTING.getCode());
        if (config == null) {
            throw new IllegalStateException("Missing INTENT_ROUTING client configuration");
        }
        List<String> historyMessages = getRecentHistoryMessages(request.getSessionId(), context);
        AnalysisDepthFollowUpResolver.Resolution followUp = followUpResolver()
                .resolve(request.getMessage(), historyMessages);
        log.debug("Routing history prepared: sessionId={}, historyCount={}, analysisDepthFollowUpResolved={}",
                request.getSessionId(), historyMessages.size(), followUp.resolved());
        String effectiveQuery = followUp.effectiveQuery();
        Map<String, Object> observationContext = observationContext(context);
        MultiIntentRoutingResult result = observationContext.isEmpty()
                ? intentRoutingService.routeUnified(effectiveQuery, historyMessages, config, request.getSessionId())
                : intentRoutingService.routeUnified(
                        effectiveQuery, historyMessages, config, request.getSessionId(), observationContext);
        result = followUpResolver().enforce(result, followUp);
        if (!Boolean.TRUE.equals(result.getNeedsClarification())) {
            try {
                validator().validateSubTasks(result.getTaskList());
            } catch (TaskGraphValidationException e) {
                log.warn("Unified task graph is invalid, falling back to general chat: {}", e.getMessage());
                result = intentRoutingService.fallbackMultiIntentResult("Task graph validation failed: " + e.getMessage());
            }
        }
        logRoutingOutcome(request.getSessionId(), result);
        ExecuteCommandEntity effectiveRequest = followUp.resolved()
                ? copyWithMessage(request, effectiveQuery)
                : request;
        String response = handler().handle(effectiveRequest, context, result);
        if (Boolean.TRUE.equals(result.getNeedsClarification())) {
            sendClarificationAndComplete(context, request.getSessionId(), response);
        }
        return response;
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity request,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) {
        return handler().select(context);
    }

    private TaskGraphValidator validator() {
        return taskGraphValidator == null ? new TaskGraphValidator() : taskGraphValidator;
    }

    private RoutingResultHandler handler() {
        if (routingResultHandler == null) {
            routingResultHandler = new RoutingResultHandler(step1AnalyzerNode, intelligentInspection,
                    generalChatNode, multiTaskExecutionNode, applicationContext, observabilityService);
        }
        return routingResultHandler;
    }

    private List<String> getRecentHistoryMessages(String sessionId,
                                                  DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) {
        return RuntimeHistorySupport.preparedHistory(context)
                .orElseGet(() -> {
                    try {
                        return conversationContextProvider.getRoutingContext(sessionId).getHistoryMessages();
                    } catch (Exception e) {
                        log.warn("Failed to load conversation history: sessionId={}, error={}", sessionId, e.getMessage());
                        return List.of();
                    }
                });
    }

    private void logRoutingOutcome(String sessionId, MultiIntentRoutingResult result) {
        List<SubTask> tasks = result.getTaskList() == null ? List.of() : result.getTaskList();
        List<String> outcomes = tasks.stream()
                .map(task -> String.valueOf(task.getIntent()) + ":" + String.valueOf(task.getConfidence()))
                .toList();
        log.info("Intent routing outcome: sessionId={}, multiTask={}, needsClarification={}, tasks={}, missingInfo={}",
                sessionId, result.getMultiTask(), result.getNeedsClarification(), outcomes, result.getMissingInfo());
    }

    private Map<String, Object> observationContext(
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) {
        return context.getTraceId() == null || context.getTraceId().isBlank()
                ? Map.of()
                : Map.of("trace_id", context.getTraceId());
    }

    private AnalysisDepthFollowUpResolver followUpResolver() {
        return analysisDepthFollowUpResolver == null
                ? new AnalysisDepthFollowUpResolver()
                : analysisDepthFollowUpResolver;
    }

    private ExecuteCommandEntity copyWithMessage(ExecuteCommandEntity request, String message) {
        return ExecuteCommandEntity.builder()
                .aiAgentId(request.getAiAgentId())
                .message(message)
                .sessionId(request.getSessionId())
                .maxStep(request.getMaxStep())
                .inputType(request.getInputType())
                .file(request.getFile())
                .userId(request.getUserId())
                .agentType(request.getAgentType())
                .build();
    }

    private void sendClarificationAndComplete(
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext context,
            String sessionId,
            String clarificationPrompt) {
        boolean clarificationSent = sendSseResult(context,
                AutoAgentExecuteResultEntity.createSummarySubResult(
                        "clarification", clarificationPrompt, sessionId));
        boolean terminalSent = clarificationSent && sendSseResult(context,
                AutoAgentExecuteResultEntity.createCompleteResult(sessionId));
        if (terminalSent) {
            log.info("Clarification SSE completed: sessionId={}", sessionId);
        } else {
            log.warn("Clarification SSE delivery failed: sessionId={}, clarificationSent={}",
                    sessionId, clarificationSent);
        }
    }
}
