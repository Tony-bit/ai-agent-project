package denny.ai.agent.domain.service.auto.step.routing;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.chat.GeneralChatNode;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.pe.Step1AnalyzerNode;
import denny.ai.agent.domain.service.auto.step.react.IntelligentInspection;
import denny.ai.agent.domain.service.chatmemory.ChatMemoryPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
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

    @Resource
    private IntentRoutingService intentRoutingService;

    @Resource
    private ChatMemoryPersistenceService chatMemoryPersistenceService;

    @Resource
    private Step1AnalyzerNode step1AnalyzerNode;

    @Resource
    private IntelligentInspection intelligentInspection;

    @Resource
    @Lazy
    private GeneralChatNode generalChatNode;

    @Override
    protected String doApply(ExecuteCommandEntity request,
                            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 意图路由节点执行开始 ===");

        long startAt = System.currentTimeMillis();

        List<String> historyMessages = getRecentHistoryMessages(request.getSessionId());
        String prompt = IntentRoutingPrompt.buildPrompt(request.getMessage(), historyMessages);

        IntentRoutingService.IntentRoutingResult result = intentRoutingService.route(
                request.getMessage(), prompt);

        long latencyMs = System.currentTimeMillis() - startAt;
        log.info("意图识别完成: intent={}, confidence={}, reasoning={}, 耗时={}ms",
                result.getIntent(), result.getConfidence(), result.getReasoning(), latencyMs);

        dynamicContext.setValue(ROUTING_RESULT_KEY, result);
        dynamicContext.setValue(RECOGNIZED_INTENT_KEY, result.getIntent());

        if (result.getConfidence() == ConfidenceEnum.LOW) {
            log.warn("意图识别置信度低: intent={}, reasoning={}, sessionId={}",
                    result.getIntent(), result.getReasoning(), request.getSessionId());
        }

        if (result.getIntent() == IntentTypeEnum.STOCK_ANALYSIS) {
            log.warn("STOCK_ANALYSIS 暂不支持内部路由，打日志后流转到下一节点: sessionId={}",
                    request.getSessionId());
        }

        return router(request, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity request,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {

        IntentTypeEnum intent = dynamicContext.getValue(RECOGNIZED_INTENT_KEY);
        if (intent == null) {
            return generalChatNode;
        }

        return switch (intent) {
            case PE_REASONING, PE_CALCULATION, PE_RETRIEVAL -> step1AnalyzerNode;
            case INSPECTION -> intelligentInspection;
            default -> generalChatNode;
        };
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
}
