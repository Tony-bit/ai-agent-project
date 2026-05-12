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
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

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


        String response = chatClient.prompt()
                .system(IntentRoutingPrompt.SYSTEM_PROMPT)
                .user(requestParameter.getMessage())
                .call()
                .content();

        log.debug("意图识别 LLM 原始响应: {}", response);

        TradingIntentRoutingService.IntentRoutingResult result = tradingIntentRoutingService.parseResponse(response);

        long latencyMs = System.currentTimeMillis() - startAt;
        log.info("意图识别完成: intent={}, confidence={}, ticker={}, 耗时={}ms",
                result.getIntent(), result.getConfidence(), result.getTicker(), latencyMs);

        sendIntentRoutingEvent(dynamicContext, result);

        if (result.getIntent() == IntentEnumVO.STOCK_ANALYSIS) {
            handleStockAnalysisIntent(requestParameter, dynamicContext, result);
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
                    // SSE 发送已由 TradingStateContext 处理，此处为空
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

    private void handleStockAnalysisIntent(ExecuteCommandEntity requestParameter,
                                          DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                          TradingIntentRoutingService.IntentRoutingResult result) {
        if (result.getConfidence() == ConfidenceEnum.HIGH) {
            StockAnalysisRequestVO tradingRequest = StockAnalysisRequestVO.builder()
                    .ticker(result.getTicker())
                    .selectedAnalysts(result.getSelectedAnalysts())
                    .maxDebateRounds(2)
                    .maxRiskRounds(1)
                    .sessionId(requestParameter.getSessionId())
                    .build();

            dynamicContext.setValue(TRADING_REQUEST_KEY, tradingRequest);
            log.info("高置信度股票分析意图，设置 trading_request: {}", tradingRequest.getTicker());

        } else if (result.getConfidence() == ConfidenceEnum.MEDIUM) {
            dynamicContext.setValue(NEEDS_CONFIRMATION_KEY, true);
            dynamicContext.setValue("pending_ticker", result.getTicker());
            dynamicContext.setValue("pending_analysts", result.getSelectedAnalysts());
            log.info("中置信度股票分析意图，等待用户确认: ticker={}", result.getTicker());

        } else {
            log.info("低置信度股票分析意图，不触发交易流程: ticker={}", result.getTicker());
        }
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
