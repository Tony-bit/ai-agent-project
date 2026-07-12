package denny.ai.agent.domain.service.auto.step.routing;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
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
        MultiIntentRoutingResult result = intentRoutingService.routeUnified(
                request.getMessage(), getRecentHistoryMessages(request.getSessionId(), context), config);
        if (!Boolean.TRUE.equals(result.getNeedsClarification())) {
            try {
                validator().validateSubTasks(result.getTaskList());
            } catch (TaskGraphValidationException e) {
                log.warn("Unified task graph is invalid, falling back to general chat: {}", e.getMessage());
                result = intentRoutingService.fallbackMultiIntentResult("Task graph validation failed: " + e.getMessage());
            }
        }
        return handler().handle(request, context, result);
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
}
