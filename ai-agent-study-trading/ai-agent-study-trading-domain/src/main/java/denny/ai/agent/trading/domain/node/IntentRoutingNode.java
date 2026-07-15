package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.ConfidenceEnum;
import denny.ai.agent.trading.api.vo.IntentEnumVO;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingStarter;
import denny.ai.agent.trading.domain.prompt.IntentRoutingPrompt;
import denny.ai.agent.trading.domain.service.TradingIntentRoutingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 意图路由节点。
 * <p>
 * 前置于 Step1AnalyzerNode，负责识别用户意图：
 * - STOCK_ANALYSIS + 高置信度 → 启动状态机执行交易分析
 * - STOCK_ANALYSIS + 中置信度 → 设置 needs_confirmation 标志
 * - GENERAL_CHAT 或低置信度 → 不修改，正常流转
 */
@Slf4j
@Service("tradingIntentRoutingNode")
public class IntentRoutingNode extends AbstractExecuteSupport {

    public static final String TRADING_REQUEST_KEY = "trading_request";
    public static final String NEEDS_CONFIRMATION_KEY = "needs_confirmation";
    public static final String INTENT_ROUTING_RESULT_KEY = "intent_routing_result";
    public static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    public static final String CLARIFICATION_QUESTION_KEY = "clarification_question";
    public static final String ROUTING_CANDIDATES_KEY = "routing_candidates";
    public static final String PENDING_ENTITY_MENTION_KEY = "pending_entity_mention";

    private static final String ACTION_START_TRADING_ANALYSIS = "START_TRADING_ANALYSIS";
    private static final String ACTION_ASK_CLARIFICATION = "ASK_CLARIFICATION";
    private static final String ACTION_ASK_DISAMBIGUATION = "ASK_DISAMBIGUATION";

    @Resource
    private TradingIntentRoutingService tradingIntentRoutingService;

    @Resource
    private TradingStarter starter;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter,
                            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 意图路由节点执行开始 ===");
        log.info("用户消息: {}", requestParameter.getMessage());

        long startAt = System.currentTimeMillis();

        log.info("开始意图识别，用户消息: {}", requestParameter.getMessage());
        ChatClient chatClient = getChatClientByClientId("6001", 0);

        // 构建临时对话历史，让 LLM 在其中执行 tool 调用
        List<org.springframework.ai.chat.messages.Message> messages = List.of(
                new SystemMessage(IntentRoutingPrompt.SYSTEM_PROMPT),
                new UserMessage(requestParameter.getMessage())
        );

        // stream() 返回 Flux<String>，通过 block() 同步获取最终内容
        if (!shouldContinueSse(dynamicContext)) {
            log.info("SSE已关闭，跳过交易意图识别LLM调用");
            return "trading_intent_routing_aborted";
        }
        List<String> contentParts = chatClient.prompt()
                .messages(messages)
                .advisors(MessageChatMemoryAdvisor.builder(
                        MessageWindowChatMemory.builder()
                                .maxMessages(10)
                                .build()
                ).build())
                .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, "intent_routing_" + requestParameter.getSessionId()))
                .stream()
                .content()
                .collectList()
                .block();
        String response = contentParts != null ? String.join("", contentParts) : "";

        log.debug("意图识别 LLM 原始响应: {}", response);

        TradingIntentRoutingService.IntentRoutingResult result = tradingIntentRoutingService.parseResponse(response);

        long latencyMs = System.currentTimeMillis() - startAt;
        log.info("意图识别完成: intent={}, confidence={}, ticker={}, 耗时={}ms",
                result.getIntent(), result.getConfidence(), result.getTicker(), latencyMs);

        sendIntentRoutingEvent(dynamicContext, result);

        if (result.getIntent() == IntentEnumVO.STOCK_ANALYSIS) {
            handleStockAnalysisIntent(dynamicContext, requestParameter.getSessionId(), result);
        } else {
            log.info("非股票分析意图，不设置 trading_request，继续流转到 Step1AnalyzerNode");
        }

        dynamicContext.setValue(INTENT_ROUTING_RESULT_KEY, result);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {

        StockAnalysisRequestVO tradingRequest = dynamicContext.getValue(TRADING_REQUEST_KEY);
        if (tradingRequest != null) {
            try {
                starter.start(tradingRequest, dynamicContext, (type, event) -> {
                    if (event instanceof AutoAgentExecuteResultEntity result) {
                        sendSseResult(dynamicContext, result);
                    }
                });
            } catch (Exception e) {
                log.error("交易分析执行异常: {}", e.getMessage(), e);
            }
            return null;
        }

        Boolean needsConfirmation = dynamicContext.getValue(NEEDS_CONFIRMATION_KEY);
        if (Boolean.TRUE.equals(needsConfirmation)) {
            dynamicContext.setValue("awaiting_confirmation", true);
            return null;
        }

        return null;
    }

    private void handleStockAnalysisIntent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                          String sessionId,
                                          TradingIntentRoutingService.IntentRoutingResult result) {
        String ticker = result.getTicker();
        String nextAction = result.getNextAction();

        if (ACTION_START_TRADING_ANALYSIS.equals(nextAction) && !hasText(ticker)) {
            log.warn("LLM 请求启动股票分析但 ticker 为空，降级为澄清: entity={}, resolutionStatus={}",
                    result.getEntityMention(), result.getResolutionStatus());
            markNeedsClarification(dynamicContext, result, "请提供要分析的股票代码或确认股票名称。");
            return;
        }

        if (ACTION_START_TRADING_ANALYSIS.equals(nextAction) && hasText(ticker)) {
            StockAnalysisRequestVO tradingRequest = StockAnalysisRequestVO.builder()
                    .ticker(ticker)
                    .selectedAnalysts(result.getSelectedAnalysts())
                    .maxDebateRounds(2)
                    .maxRiskRounds(1)
                    .sessionId(sessionId)
                    .build();

            dynamicContext.setValue(TRADING_REQUEST_KEY, tradingRequest);
            log.info("股票分析意图已解析，设置 trading_request: ticker={}, entity={}, resolutionStatus={}",
                    ticker, result.getEntityMention(), result.getResolutionStatus());
            return;
        }

        if (ACTION_ASK_CLARIFICATION.equals(nextAction)
                || ACTION_ASK_DISAMBIGUATION.equals(nextAction)
                || result.getConfidence() == ConfidenceEnum.MEDIUM) {
            markNeedsClarification(dynamicContext, result, null);
            log.info("股票分析意图需要用户确认: nextAction={}, ticker={}, entity={}, resolutionStatus={}",
                    nextAction, ticker, result.getEntityMention(), result.getResolutionStatus());
            return;
        }

        log.info("股票分析意图未触发交易流程: nextAction={}, confidence={}, ticker={}, resolutionStatus={}",
                nextAction, result.getConfidence(), ticker, result.getResolutionStatus());
    }

    private void markNeedsClarification(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                        TradingIntentRoutingService.IntentRoutingResult result,
                                        String fallbackQuestion) {
        String question = hasText(result.getClarificationQuestion())
                ? result.getClarificationQuestion()
                : fallbackQuestion;
        if (!hasText(question)) {
            question = buildDefaultClarificationQuestion(result);
        }

        dynamicContext.setValue(NEEDS_CONFIRMATION_KEY, true);
        dynamicContext.setValue("pending_ticker", result.getTicker());
        dynamicContext.setValue("pending_analysts", result.getSelectedAnalysts());
        dynamicContext.setValue(PENDING_ENTITY_MENTION_KEY, result.getEntityMention());
        dynamicContext.setValue(ROUTING_CANDIDATES_KEY, result.getCandidates());
        dynamicContext.setValue(CLARIFICATION_QUESTION_KEY, question);

        sendClarificationEvent(dynamicContext, question);
    }

    private String buildDefaultClarificationQuestion(TradingIntentRoutingService.IntentRoutingResult result) {
        if ("AMBIGUOUS".equals(result.getResolutionStatus())) {
            return "找到多个候选股票，请确认要分析哪一个。";
        }
        if (hasText(result.getEntityMention())) {
            return String.format("没有找到“%s”对应的A股股票，请提供股票代码或确认名称。", result.getEntityMention());
        }
        return "请提供要分析的股票名称或6位股票代码。";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void sendClarificationEvent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                        String question) {
        AutoAgentExecuteResultEntity event = AutoAgentExecuteResultEntity.builder()
                .type("intent_routing")
                .subType("clarification_required")
                .step(0)
                .content(question)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .build();

        sendSseResult(dynamicContext, event);
    }

    private void sendIntentRoutingEvent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                       TradingIntentRoutingService.IntentRoutingResult result) {
        AutoAgentExecuteResultEntity event = AutoAgentExecuteResultEntity.builder()
                .type("intent_routing")
                .subType("intent_result")
                .step(0)
                .content(String.format("意图识别: %s (置信度: %s)", result.getIntent().getDescription(), result.getConfidence().getDescription()))
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .build();

        sendSseResult(dynamicContext, event);
    }
}
