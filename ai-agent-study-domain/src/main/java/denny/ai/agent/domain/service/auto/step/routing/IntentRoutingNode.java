package denny.ai.agent.domain.service.auto.step.routing;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.BaseSlot;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.StockSlot;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.chat.GeneralChatNode;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.pe.Step1AnalyzerNode;
import denny.ai.agent.domain.service.auto.step.react.IntelligentInspection;
import denny.ai.agent.domain.service.chatmemory.ConversationContextProvider;
import denny.ai.agent.domain.model.valobj.stock.StockRequestRouteDecisionType;
import denny.ai.agent.domain.model.valobj.stock.StockRequestRoutingDecision;
import denny.ai.agent.domain.service.stock.StockResolutionPendingRepository;
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
    private static final String STOCK_CLAIM_VERSION_KEY = "stockResolutionClaimVersion";
    private static final String STOCK_CLAIM_ID_KEY = "stockResolutionClaimId";

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
    private StockRequestResolver stockRequestResolver;
    @Resource
    private ConversationContextProvider conversationContextProvider;
    @Resource
    private TaskGraphValidator taskGraphValidator;
    @Resource
    private RoutingResultHandler routingResultHandler;
    @Resource
    private StockResolutionPendingRepository stockResolutionPendingRepository;
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
        StockRoutingAdjustment stockAdjustment = applyStockRouting(request, result, context);
        result = stockAdjustment.result();
        if (stockAdjustment.terminalResponse() != null) {
            sendRoutingTerminalIfPresent(context, request.getSessionId());
            return stockAdjustment.terminalResponse();
        }
        if (!Boolean.TRUE.equals(result.getNeedsClarification())) {
            try {
                validator().validateSubTasks(result.getTaskList());
            } catch (TaskGraphValidationException e) {
                log.warn("Unified task graph is invalid, falling back to general chat: {}", e.getMessage());
                result = intentRoutingService.fallbackMultiIntentResult("Task graph validation failed: " + e.getMessage());
            }
        }
        logRoutingOutcome(request.getSessionId(), result);
        ExecuteCommandEntity routedRequest = stockAdjustment.executionQuery() == null
                ? request
                : copyWithMessage(request, stockAdjustment.executionQuery());
        ExecuteCommandEntity effectiveRequest = followUp.resolved()
                ? copyWithMessage(routedRequest, effectiveQuery)
                : routedRequest;
        String response = handler().handle(effectiveRequest, context, result);
        deleteClaimedPendingIfPresent(request.getSessionId(), context);
        sendRoutingTerminalIfPresent(context, request.getSessionId());
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

    private StockRequestResolver stockResolver() {
        return stockRequestResolver;
    }

    @SuppressWarnings("unchecked")
    private StockRoutingAdjustment applyStockRouting(
            ExecuteCommandEntity request,
            MultiIntentRoutingResult result,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) {
        if (result == null || Boolean.TRUE.equals(result.getMultiTask())) {
            return StockRoutingAdjustment.unchanged(result);
        }

        IntentTypeEnum intent = IntentTypeEnum.GENERAL_CHAT;
        StockSlot stockSlot = new StockSlot();
        if (result.getTaskList() != null && !result.getTaskList().isEmpty()) {
            SubTask task = result.getTaskList().get(0);
            if (task.getIntent() != null) {
                intent = task.getIntent();
            }
            if (task.getSlots() != null) {
                Object rawIntentSlots = task.getSlots().get("intentSpecificSlots");
                if (rawIntentSlots instanceof Map<?, ?> intentSlots) {
                    Object rawStockSlot = intentSlots.get("stockSlot");
                    if (rawStockSlot instanceof StockSlot typed) {
                        stockSlot = typed;
                    }
                }
            }
        }

        StockRequestRoutingDecision decision = stockResolver() == null
                ? null
                : stockResolver().resolve(request.getSessionId(), request.getMessage(), intent, stockSlot);
        if (decision == null || decision.getDecisionType() == null) {
            return StockRoutingAdjustment.unchanged(result);
        }

        if (decision.getDecisionType() == StockRequestRouteDecisionType.CLARIFY_TARGET
                || decision.getDecisionType() == StockRequestRouteDecisionType.CLARIFY_ANALYSIS_MODE
                || decision.getDecisionType() == StockRequestRouteDecisionType.NOT_FOUND) {
            context.setValue(RoutingResultHandler.ROUTING_TERMINAL_KIND_KEY,
                    RoutingResultHandler.TERMINAL_KIND_CLARIFICATION);
            context.setValue(RoutingResultHandler.ROUTING_TERMINAL_RESPONSE_KEY, decision.getClarificationPrompt());
            context.setValue("clarificationPrompt", decision.getClarificationPrompt());
            return new StockRoutingAdjustment(result, null, decision.getClarificationPrompt());
        }

        if (decision.getDecisionType() == StockRequestRouteDecisionType.ROUTE_GENERAL_CHAT) {
            applyClaimContext(context, decision);
            return new StockRoutingAdjustment(
                    singleTaskResult(
                            IntentTypeEnum.FINANCIAL_GENERAL,
                            "generalChatNode",
                            request.getMessage(),
                            Map.of(
                                    "baseSlot", BaseSlot.builder().topic(request.getMessage()).sentiment("neutral").build(),
                                    "intentSpecificSlots", Map.of())),
                    decision.getExecutionQuery(),
                    null);
        }

        if (decision.getDecisionType() == StockRequestRouteDecisionType.ROUTE_TRADING) {
            applyClaimContext(context, decision);
            return new StockRoutingAdjustment(
                    singleTaskResult(
                            IntentTypeEnum.STOCK_ANALYSIS,
                            "tradingRequestNode",
                            request.getMessage(),
                            Map.of(
                                    "baseSlot", BaseSlot.builder().topic(request.getMessage()).sentiment("neutral").build(),
                                    "intentSpecificSlots", Map.of("stockSlot", decision.getStockSlot()))),
                    null,
                    null);
        }

        return StockRoutingAdjustment.unchanged(result);
    }

    private MultiIntentRoutingResult singleTaskResult(IntentTypeEnum intent,
                                                      String executorNode,
                                                      String content,
                                                      Map<String, Object> slots) {
        return MultiIntentRoutingResult.builder()
                .multiTask(false)
                .needsClarification(false)
                .missingInfo(List.of())
                .clarificationPrompt("")
                .reasoning("Resolved by StockRequestResolver")
                .taskList(List.of(SubTask.builder()
                        .taskId("sub-1")
                        .taskIndex(1)
                        .totalTasks(1)
                        .content(content)
                        .intent(intent)
                        .confidence(ConfidenceEnum.HIGH)
                        .executorNode(executorNode)
                        .slots(slots)
                        .dependsOn(List.of())
                        .status(SubTask.SubTaskStatus.PENDING)
                        .taskType(0)
                        .build()))
                .build();
    }

    private void applyClaimContext(DefaultAutoAgentExecuteStrategyFactory.DynamicContext context,
                                   StockRequestRoutingDecision decision) {
        if (decision.getPendingVersion() != null && decision.getClaimId() != null) {
            context.setValue(STOCK_CLAIM_VERSION_KEY, decision.getPendingVersion());
            context.setValue(STOCK_CLAIM_ID_KEY, decision.getClaimId());
        }
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

    private void sendRoutingTerminalIfPresent(
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext context,
            String sessionId) {
        String kind = context.getValue(RoutingResultHandler.ROUTING_TERMINAL_KIND_KEY);
        String response = context.getValue(RoutingResultHandler.ROUTING_TERMINAL_RESPONSE_KEY);
        if (RoutingResultHandler.TERMINAL_KIND_CLARIFICATION.equals(kind)) {
            sendClarificationAndComplete(context, sessionId, response);
            return;
        }
        if (RoutingResultHandler.TERMINAL_KIND_ERROR.equals(kind)) {
            boolean sent = sendSseResult(context,
                    AutoAgentExecuteResultEntity.createErrorResult(response, sessionId));
            if (!sent) {
                log.warn("Routing error SSE delivery failed without retry: sessionId={}", sessionId);
            }
        }
    }

    private void deleteClaimedPendingIfPresent(String sessionId,
                                               DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) {
        if (stockResolutionPendingRepository == null) {
            return;
        }
        String version = context.getValue(STOCK_CLAIM_VERSION_KEY);
        String claimId = context.getValue(STOCK_CLAIM_ID_KEY);
        if (version == null || claimId == null) {
            return;
        }
        boolean deleted = stockResolutionPendingRepository.deleteClaimed(sessionId, version, claimId);
        if (!deleted) {
            log.warn("Claimed stock pending cleanup skipped: sessionId={}, version={}", sessionId, version);
        }
    }

    private record StockRoutingAdjustment(MultiIntentRoutingResult result,
                                          String executionQuery,
                                          String terminalResponse) {
        static StockRoutingAdjustment unchanged(MultiIntentRoutingResult result) {
            return new StockRoutingAdjustment(result, null, null);
        }
    }
}
