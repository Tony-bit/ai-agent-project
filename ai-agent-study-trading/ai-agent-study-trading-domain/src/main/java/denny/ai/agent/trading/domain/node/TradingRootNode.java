package denny.ai.agent.trading.domain.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 交易 Agent 根节点。
 * <p>
 * 负责：
 * 1. 从 DynamicContext 获取 trading_request
 * 2. 初始化 TradingContextVO
 * 3. 调用 IStockDataProvider 获取股票数据
 * 4. 根据配置决定并行触发哪些分析师节点
 * 5. 管理辩论轮次计数
 */
@Slf4j
@Service
public class TradingRootNode extends AbstractExecuteSupport {

    public static final String TRADING_CONTEXT_KEY = "trading_context";
    public static final String TRADING_REQUEST_KEY = "trading_request";
    public static final String DEBATE_ROUND_KEY = "debate_round";
    public static final String TRADING_STEP_KEY = "trading_step";

    @Resource
    private IStockDataProvider dataProvider;

    @Resource
    private FundamentalAnalystNode fundamentalAnalystNode;

    @Resource
    private TechnicalAnalystNode technicalAnalystNode;

    @Resource
    private SentimentAnalystNode sentimentAnalystNode;

    @Resource
    private NewsAnalystNode newsAnalystNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 交易 Agent 根节点执行开始 ===");

        StockAnalysisRequestVO tradingRequest = dynamicContext.getValue(TRADING_REQUEST_KEY);
        if (tradingRequest == null) {
            log.error("未找到 trading_request，无法执行交易分析");
            return "error: no trading request";
        }

        log.info("交易分析请求: ticker={}, analysts={}, maxDebateRounds={}",
                tradingRequest.getTicker(),
                tradingRequest.getSelectedAnalysts(),
                tradingRequest.getMaxDebateRounds());

        // 初始化交易上下文
        TradingContextVO context = TradingContextVO.empty();
        dynamicContext.setValue(TRADING_CONTEXT_KEY, context);

        // 获取股票基本信息
        StockInfoVO stockInfo = dataProvider.getStockInfo(tradingRequest.getTicker());
        context.setStockInfo(stockInfo);
        log.info("获取股票信息: ticker={}, price={}, pe={}",
                stockInfo.getTicker(), stockInfo.getCurrentPrice(), stockInfo.getPeRatio());

        // 发送初始化 SSE 事件
        sendTradingInitEvent(dynamicContext, stockInfo);

        // 确定需要执行的分析师
        List<AnalystTypeEnum> analystsToRun = determineAnalysts(tradingRequest);
        log.info("将执行以下分析师: {}", analystsToRun);

        // 初始化辩论轮次
        dynamicContext.setValue(DEBATE_ROUND_KEY, new AtomicInteger(0));
        dynamicContext.setValue(TRADING_STEP_KEY, "analyst_collection");

        // 这里返回后，由框架通过 router 方法调度到各个分析师节点
        // 分析师节点完成后会再次回到这里进行下一阶段

        return "analyst_collection_started";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity requestParameter,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {

        String currentStep = dynamicContext.getValue(TRADING_STEP_KEY);
        TradingContextVO context = dynamicContext.getValue(TRADING_CONTEXT_KEY);

        if (context == null) {
            log.error("TradingContextVO 为空");
            return null;
        }

        // 根据当前步骤决定下一个 Handler
        return switch (currentStep) {
            case "analyst_collection" -> selectNextAnalyst(context, dynamicContext);
            case "investment_debate" -> null; // TODO: Phase 3 实现
            case "trader_decision" -> null;   // TODO: Phase 4 实现
            case "risk_management" -> null;    // TODO: Phase 4 实现
            case "final_report" -> null;      // TODO: Phase 5 实现
            default -> {
                log.warn("未知的交易步骤: {}", currentStep);
                yield null;
            }
        };
    }

    /**
     * 选择下一个需要执行的分析师节点。
     */
    private StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> selectNextAnalyst(
            TradingContextVO context,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {

        if (context.getFundamentalReport() == null) {
            return fundamentalAnalystNode;
        }
        if (context.getTechnicalReport() == null) {
            return technicalAnalystNode;
        }
        if (context.getSentimentReport() == null) {
            return sentimentAnalystNode;
        }
        if (context.getNewsReport() == null) {
            return newsAnalystNode;
        }

        // 所有分析师都完成了，进入辩论阶段
        log.info("所有分析师完成，进入辩论阶段");
        dynamicContext.setValue(TRADING_STEP_KEY, "investment_debate");
        return null; // TODO: 返回辩论节点
    }

    /**
     * 确定需要执行的分析师列表。
     */
    private List<AnalystTypeEnum> determineAnalysts(StockAnalysisRequestVO request) {
        if (request.getSelectedAnalysts() != null && !request.getSelectedAnalysts().isEmpty()) {
            return request.getSelectedAnalysts();
        }
        // 默认执行全部 4 个分析师
        return List.of(
                AnalystTypeEnum.FUNDAMENTAL,
                AnalystTypeEnum.TECHNICAL,
                AnalystTypeEnum.SENTIMENT,
                AnalystTypeEnum.NEWS
        );
    }

    /**
     * 发送交易初始化 SSE 事件。
     */
    private void sendTradingInitEvent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                     StockInfoVO stockInfo) {
        AutoAgentExecuteResultEntity event = AutoAgentExecuteResultEntity.builder()
                .type("trading")
                .subType("trading_init")
                .step(dynamicContext.getStep())
                .content(JSON.toJSONString(stockInfo))
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .build();

        sendSseResult(dynamicContext, event);
    }
}
