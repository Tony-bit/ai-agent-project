package denny.ai.agent.domain.service.auto.step.routing;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.BaseSlot;
import denny.ai.agent.domain.model.valobj.IntentRoutingResult;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.StockSlot;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.chat.GeneralChatNode;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.pe.Step1AnalyzerNode;
import denny.ai.agent.domain.service.auto.step.react.IntelligentInspection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RoutingResultHandler {
    public static final String RECOGNIZED_INTENT_KEY = "recognizedIntent";
    public static final String ROUTING_RESULT_KEY = "intentRoutingResult";
    public static final String BASE_SLOT_KEY = "baseSlot";
    public static final String INTENT_SPECIFIC_SLOTS_KEY = "intentSpecificSlots";
    public static final String STOCK_SLOT_KEY = "stockSlot";
    public static final String METRICS_KEY = "intentRoutingMetrics";

    private static final String TRADING_NODE_BEAN_NAME = "tradingIntentRoutingNode";

    private final Step1AnalyzerNode step1AnalyzerNode;
    private final IntelligentInspection intelligentInspection;
    private final GeneralChatNode generalChatNode;
    private final MultiTaskExecutionNode multiTaskExecutionNode;
    private final ApplicationContext applicationContext;

    public RoutingResultHandler(Step1AnalyzerNode step1AnalyzerNode,
                                IntelligentInspection intelligentInspection,
                                GeneralChatNode generalChatNode,
                                MultiTaskExecutionNode multiTaskExecutionNode,
                                ApplicationContext applicationContext) {
        this.step1AnalyzerNode = step1AnalyzerNode;
        this.intelligentInspection = intelligentInspection;
        this.generalChatNode = generalChatNode;
        this.multiTaskExecutionNode = multiTaskExecutionNode;
        this.applicationContext = applicationContext;
    }

    public String handle(ExecuteCommandEntity request,
                         DefaultAutoAgentExecuteStrategyFactory.DynamicContext context,
                         MultiIntentRoutingResult result) throws Exception {
        if (result.getMetrics() != null) {
            context.setValue(METRICS_KEY, result.getMetrics());
        }
        if (Boolean.TRUE.equals(result.getNeedsClarification())) {
            context.setValue("clarificationPrompt", result.getClarificationPrompt());
            context.setValue("missingInfo", result.getMissingInfo());
            return result.getClarificationPrompt();
        }
        if (Boolean.TRUE.equals(result.getMultiTask())) {
            context.setValue(MultiTaskExecutionNode.TASK_LIST_KEY, result.getTaskList());
            context.setValue(MultiTaskExecutionNode.ORIGINAL_MESSAGE_KEY, request.getMessage());
            return multiTaskExecutionNode.apply(request, context);
        }

        IntentRoutingResult single = toSingleResult(result);
        context.setValue(ROUTING_RESULT_KEY, single);
        context.setValue(RECOGNIZED_INTENT_KEY, single.getIntent());
        context.setValue(BASE_SLOT_KEY, single.getBaseSlot());
        context.setValue(INTENT_SPECIFIC_SLOTS_KEY, single.getIntentSpecificSlots());
        if (single.getIntent() == IntentTypeEnum.STOCK_ANALYSIS) {
            context.setValue(STOCK_SLOT_KEY, extractStockSlot(single.getIntentSpecificSlots()));
        }
        if (single.getConfidence() == ConfidenceEnum.LOW) {
            log.warn("Low confidence intent routing: intent={}, reasoning={}, sessionId={}",
                    single.getIntent(), single.getReasoning(), request.getSessionId());
        }
        return select(context).apply(request, context);
    }

    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> select(
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) {
        List<SubTask> tasks = context.getValue(MultiTaskExecutionNode.TASK_LIST_KEY);
        if (tasks != null && !tasks.isEmpty()) {
            return multiTaskExecutionNode;
        }
        IntentTypeEnum intent = context.getValue(RECOGNIZED_INTENT_KEY);
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
    private IntentRoutingResult toSingleResult(MultiIntentRoutingResult result) {
        SubTask task = result.getTaskList() == null || result.getTaskList().isEmpty()
                ? null : result.getTaskList().get(0);
        BaseSlot baseSlot = null;
        Map<String, Object> intentSlots = Map.of();
        if (task != null && task.getSlots() != null) {
            Object rawBaseSlot = task.getSlots().get("baseSlot");
            if (rawBaseSlot instanceof BaseSlot typed) {
                baseSlot = typed;
            } else if (rawBaseSlot instanceof Map<?, ?> map) {
                baseSlot = BaseSlot.builder()
                        .topic((String) map.get("topic"))
                        .sentiment((String) map.get("sentiment"))
                        .build();
            }
            Object rawIntentSlots = task.getSlots().get("intentSpecificSlots");
            if (rawIntentSlots instanceof Map<?, ?> map) {
                intentSlots = (Map<String, Object>) map;
            }
        }
        return IntentRoutingResult.builder()
                .intent(task == null || task.getIntent() == null ? IntentTypeEnum.GENERAL_CHAT : task.getIntent())
                .confidence(task == null || task.getConfidence() == null ? ConfidenceEnum.MEDIUM : task.getConfidence())
                .reasoning(result.getReasoning())
                .baseSlot(baseSlot)
                .intentSpecificSlots(intentSlots)
                .build();
    }

    private StockSlot extractStockSlot(Map<String, Object> slots) {
        if (slots == null) {
            return null;
        }
        Object stockSlot = slots.get("stockSlot");
        return stockSlot instanceof StockSlot typed ? typed : null;
    }

    @SuppressWarnings("unchecked")
    private StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> resolveTradingNode() {
        try {
            StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> node =
                    (StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String>)
                            applicationContext.getBean(TRADING_NODE_BEAN_NAME);
            return node == null ? generalChatNode : node;
        } catch (Exception e) {
            log.warn("Trading node is unavailable, falling back to general chat: {}", e.getMessage());
            return generalChatNode;
        }
    }
}
