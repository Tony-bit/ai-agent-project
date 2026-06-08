package denny.ai.agent.domain.service.auto.step.routing;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.BaseSlot;
import denny.ai.agent.domain.model.valobj.IntentRoutingResult;
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
import denny.ai.agent.domain.service.chatmemory.ChatMemoryPersistenceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 意图路由节点
 * <p>
 * 当用户未显式指定 aiAgentId 时，由 RootNode 路由至此节点。
 * 通过 LLM 识别用户意图后，路由到对应的处理节点。
 * </p>
 *
 * @author denny
 * 2026/5/10
 */
@Slf4j
@Service("intentRoutingNode")
public class IntentRoutingNode extends AbstractExecuteSupport {

    public static final String RECOGNIZED_INTENT_KEY = "recognizedIntent";
    public static final String ROUTING_RESULT_KEY = "intentRoutingResult";
    public static final String BASE_SLOT_KEY = "baseSlot";
    public static final String INTENT_SPECIFIC_SLOTS_KEY = "intentSpecificSlots";
    public static final String STOCK_SLOT_KEY = "stockSlot";

    private static final String TRADING_NODE_BEAN_NAME = "tradingIntentRoutingNode";

    @Resource
    private IntentRoutingService intentRoutingService;

    @Resource
    private ChatMemoryPersistenceService chatMemoryPersistenceService;

    @Resource
    private Step1AnalyzerNode step1AnalyzerNode;

    @Resource
    private IntelligentInspection intelligentInspection;

    @Resource
    private GeneralChatNode generalChatNode;

    @Resource
    private MultiTaskExecutionNode multiTaskExecutionNode;

    @Override
    protected String doApply(ExecuteCommandEntity request,
                             DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 意图路由节点执行开始 ===");

        long startAt = System.currentTimeMillis();

        AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.INTENT_ROUTING.getCode());
        if (aiAgentClientFlowConfigVO == null) {
            throw new IllegalStateException("未找到意图路由客户端配置，aiAgentId=" + request.getAiAgentId()
                    + "，请确认智能体流程配置中已添加 INTENT_ROUTING 类型的节点");
        }

        List<String> historyMessages = getRecentHistoryMessages(request.getSessionId());
        MultiIntentRoutingResult routingResult = intentRoutingService.routeUnified(
                request.getMessage(),
                historyMessages,
                aiAgentClientFlowConfigVO
        );

        long latencyMs = System.currentTimeMillis() - startAt;
        log.info("意图路由完成: multiTask={}, needsClarification={}, taskCount={}, 耗时={}ms",
                routingResult.getMultiTask(), routingResult.getNeedsClarification(),
                routingResult.getTaskList() != null ? routingResult.getTaskList().size() : 0, latencyMs);

        if (Boolean.TRUE.equals(routingResult.getNeedsClarification())) {
            log.info("任务信息不完整，需要补全: missingInfo={}", routingResult.getMissingInfo());
            dynamicContext.setValue("clarificationPrompt", routingResult.getClarificationPrompt());
            dynamicContext.setValue("missingInfo", routingResult.getMissingInfo());
            return routingResult.getClarificationPrompt();
        }

        if (Boolean.TRUE.equals(routingResult.getMultiTask())) {
            dynamicContext.setValue(MultiTaskExecutionNode.TASK_LIST_KEY, routingResult.getTaskList());
            dynamicContext.setValue(MultiTaskExecutionNode.ORIGINAL_MESSAGE_KEY, request.getMessage());
            log.info("多任务分解完成，共 {} 个子任务", routingResult.getTaskList().size());
            return router(request, dynamicContext);
        }

        return handleSingleTask(request, dynamicContext, routingResult);
    }

    private String handleSingleTask(ExecuteCommandEntity request,
                                    DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                    MultiIntentRoutingResult routingResult) throws Exception {
        IntentRoutingResult result = convertToIntentRoutingResult(routingResult);

        dynamicContext.setValue(ROUTING_RESULT_KEY, result);
        dynamicContext.setValue(RECOGNIZED_INTENT_KEY, result.getIntent());
        dynamicContext.setValue(BASE_SLOT_KEY, result.getBaseSlot());
        dynamicContext.setValue(INTENT_SPECIFIC_SLOTS_KEY, result.getIntentSpecificSlots());

        if (result.getConfidence() == ConfidenceEnum.LOW) {
            log.warn("意图识别置信度低: intent={}, reasoning={}, sessionId={}",
                    result.getIntent(), result.getReasoning(), request.getSessionId());
        }

        if (result.getIntent() == IntentTypeEnum.STOCK_ANALYSIS) {
            StockSlot stockSlot = extractStockSlot(result.getIntentSpecificSlots());
            dynamicContext.setValue(STOCK_SLOT_KEY, stockSlot);
            log.info("STOCK_ANALYSIS 切槽完成: stockCode={}, queryType={}",
                    stockSlot != null ? stockSlot.getStockCode() : "null",
                    stockSlot != null ? stockSlot.getStockQueryType() : "null");
        }

        return router(request, dynamicContext);
    }

    @SuppressWarnings("unchecked")
    private IntentRoutingResult convertToIntentRoutingResult(MultiIntentRoutingResult routingResult) {
        SubTask firstTask = routingResult.getTaskList() != null && !routingResult.getTaskList().isEmpty()
                ? routingResult.getTaskList().get(0) : null;
        IntentTypeEnum intent = firstTask != null ? firstTask.getIntent() : IntentTypeEnum.GENERAL_CHAT;
        ConfidenceEnum confidence = firstTask != null && firstTask.getConfidence() != null
                ? firstTask.getConfidence() : ConfidenceEnum.MEDIUM;

        BaseSlot baseSlot = null;
        Map<String, Object> intentSpecificSlots = null;
        if (firstTask != null && firstTask.getSlots() != null) {
            Object baseSlotObj = firstTask.getSlots().get("baseSlot");
            if (baseSlotObj instanceof BaseSlot castBaseSlot) {
                baseSlot = castBaseSlot;
            } else if (baseSlotObj instanceof Map<?, ?> baseSlotMap) {
                baseSlot = BaseSlot.builder()
                        .topic((String) baseSlotMap.get("topic"))
                        .sentiment((String) baseSlotMap.get("sentiment"))
                        .build();
            }

            Object intentSpecificSlotsObj = firstTask.getSlots().get("intentSpecificSlots");
            if (intentSpecificSlotsObj instanceof Map<?, ?> rawIntentSpecificSlots) {
                intentSpecificSlots = (Map<String, Object>) rawIntentSpecificSlots;
            }
        }

        return IntentRoutingResult.builder()
                .intent(intent)
                .confidence(confidence)
                .reasoning(routingResult.getReasoning())
                .baseSlot(baseSlot)
                .intentSpecificSlots(intentSpecificSlots)
                .build();
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity request,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {

        List<SubTask> taskList = dynamicContext.getValue(MultiTaskExecutionNode.TASK_LIST_KEY);
        if (taskList != null && !taskList.isEmpty()) {
            return multiTaskExecutionNode;
        }

        IntentTypeEnum intent = dynamicContext.getValue(RECOGNIZED_INTENT_KEY);
        if (intent == null) {
            return generalChatNode;
        }

        return switch (intent) {
            case STOCK_ANALYSIS -> resolveTradingNode();
            case PE_REASONING, PE_CALCULATION, PE_RETRIEVAL -> step1AnalyzerNode;
            case INSPECTION -> intelligentInspection;
            default -> generalChatNode;
        };
    }

    @SuppressWarnings("unchecked")
    private StrategyHandler<ExecuteCommandEntity,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> resolveTradingNode() {
        try {
            return (StrategyHandler<ExecuteCommandEntity,
                    DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String>)
                    applicationContext.getBean(TRADING_NODE_BEAN_NAME);
        } catch (Exception e) {
            log.warn("TradingNode 未找到，降级为 generalChatNode: {}", e.getMessage());
            return generalChatNode;
        }
    }

    private List<String> getRecentHistoryMessages(String sessionId) {
        try {
            List<ChatMessageEntity> messages = chatMemoryPersistenceService.getConversationHistory(sessionId);
            return messages.stream()
                    .filter(m -> m.getRole() != null && m.getContent() != null)
                    .map(m -> m.getRole() + ": " + m.getContent())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("获取会话历史失败，降级为空列表: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return List.of();
        }
    }

    private StockSlot extractStockSlot(Map<String, Object> intentSpecificSlots) {
        if (intentSpecificSlots == null) {
            return null;
        }
        Object stockSlotObj = intentSpecificSlots.get("stockSlot");
        if (stockSlotObj instanceof StockSlot) {
            return (StockSlot) stockSlotObj;
        }
        return null;
    }
}
