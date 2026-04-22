package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.vo.ConfidenceEnum;
import denny.ai.agent.trading.api.vo.IntentEnumVO;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.service.IntentRoutingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 意图路由节点。
 * <p>
 * 前置于 Step1AnalyzerNode，负责识别用户意图：
 * - STOCK_ANALYSIS + 高置信度 → 设置 trading_request 到 DynamicContext
 * - STOCK_ANALYSIS + 中置信度 → 设置 needs_confirmation 标志
 * - GENERAL_CHAT 或低置信度 → 不修改，正常流转
 */
@Slf4j
@Service
public class IntentRoutingNode extends AbstractExecuteSupport {

    public static final String TRADING_REQUEST_KEY = "trading_request";
    public static final String NEEDS_CONFIRMATION_KEY = "needs_confirmation";
    public static final String INTENT_ROUTING_RESULT_KEY = "intent_routing_result";

    @Resource
    private IntentRoutingService intentRoutingService;

    @Resource
    private TradingRootNode tradingRootNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter,
                            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 意图路由节点执行开始 ===");
        log.info("用户消息: {}", requestParameter.getMessage());

        long startAt = System.currentTimeMillis();

        // 调用意图识别服务
        IntentRoutingService.IntentRoutingResult result = intentRoutingService.route(requestParameter.getMessage());

        long latencyMs = System.currentTimeMillis() - startAt;
        log.info("意图识别完成: intent={}, confidence={}, ticker={}, 耗时={}ms",
                result.getIntent(), result.getConfidence(), result.getTicker(), latencyMs);

        // 发送 SSE 事件
        sendIntentRoutingEvent(dynamicContext, result);

        // 根据意图类型设置 DynamicContext
        if (result.getIntent() == IntentEnumVO.STOCK_ANALYSIS) {
            handleStockAnalysisIntent(requestParameter, dynamicContext, result);
        } else {
            // GENERAL_CHAT 或 UNKNOWN，不修改，正常流转
            log.info("非股票分析意图，不设置 trading_request，继续流转到 Step1AnalyzerNode");
        }

        // 保存意图路由结果
        dynamicContext.setValue(INTENT_ROUTING_RESULT_KEY, result);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {

        // 检查是否有 trading_request
        StockAnalysisRequestVO tradingRequest = dynamicContext.getValue(TRADING_REQUEST_KEY);
        if (tradingRequest != null) {
            // 有股票分析请求，路由到 TradingRootNode
            return tradingRootNode;
        }

        // 检查是否需要确认
        Boolean needsConfirmation = dynamicContext.getValue(NEEDS_CONFIRMATION_KEY);
        if (Boolean.TRUE.equals(needsConfirmation)) {
            // 需要确认，设置标志后由上层决定如何处理
            dynamicContext.setValue("awaiting_confirmation", true);
            return null;
        }

        // 默认返回 null，由框架继续流转到下一个节点
        return null;
    }

    /**
     * 处理股票分析意图。
     */
    private void handleStockAnalysisIntent(ExecuteCommandEntity requestParameter,
                                          DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                          IntentRoutingService.IntentRoutingResult result) {
        if (result.getConfidence() == ConfidenceEnum.HIGH) {
            // 高置信度：构建并设置 trading_request
            StockAnalysisRequestVO tradingRequest = StockAnalysisRequestVO.builder()
                    .ticker(result.getTicker())
                    .selectedAnalysts(result.getSelectedAnalysts())
                    .maxDebateRounds(2)
                    .sessionId(requestParameter.getSessionId())
                    .build();

            dynamicContext.setValue(TRADING_REQUEST_KEY, tradingRequest);
            log.info("高置信度股票分析意图，设置 trading_request: {}", tradingRequest.getTicker());

        } else if (result.getConfidence() == ConfidenceEnum.MEDIUM) {
            // 中置信度：设置确认标志
            dynamicContext.setValue(NEEDS_CONFIRMATION_KEY, true);
            dynamicContext.setValue("pending_ticker", result.getTicker());
            dynamicContext.setValue("pending_analysts", result.getSelectedAnalysts());
            log.info("中置信度股票分析意图，等待用户确认: ticker={}", result.getTicker());

        } else {
            // 低置信度：记录但不做路由
            log.info("低置信度股票分析意图，不触发交易流程: ticker={}", result.getTicker());
        }
    }

    /**
     * 发送意图路由 SSE 事件。
     */
    private void sendIntentRoutingEvent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                       IntentRoutingService.IntentRoutingResult result) {
        AutoAgentExecuteResultEntity event = AutoAgentExecuteResultEntity.builder()
                .type("intent_routing")
                .subType("intent_result")
                .step(dynamicContext.getStep())
                .content(String.format("意图识别: %s (置信度: %s)", result.getIntent().getDescription(), result.getConfidence().getDescription()))
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .build();

        sendSseResult(dynamicContext, event);
    }
}
