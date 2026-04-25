package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory.DynamicContext;
import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.api.vo.StockInfoVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.BiConsumer;

/**
 * 交易状态机请求入口。
 * <p>
 * 职责：
 * <ul>
 *   <li>每次 start() 创建独立的 Context + Driver + Dispatcher 实例，请求间互不影响</li>
 *   <li>初始化上下文、获取股票信息</li>
 *   <li>设置 ThreadLocal Driver，启动状态机</li>
 *   <li>finally 中清理 ThreadLocal，发送正常结束 SSE</li>
 * </ul>
 */
@Slf4j
@Service
public class TradingStarter {

    @Resource
    private IStockDataProvider dataProvider;

    @Resource
    private TradingDispatcher tradingDispatcher;

    /**
     * 开始交易分析流程。
     * <p>
     * 每次调用创建独立的上下文实例，请求间互不影响。
     *
     * @param request        股票分析请求
     * @param dynamicContext 动态上下文
     * @param sseSender     SSE 发送器
     */
    public void start(StockAnalysisRequestVO request,
                      DynamicContext dynamicContext,
                      BiConsumer<String, Object> sseSender) {
        // 1. 创建请求级上下文
        TradingStateContext stateContext = new TradingStateContext(request, dynamicContext, sseSender);

        // 2. 创建 Driver（Dispatcher 注入自 Spring）
        TradingDispatcher dispatcher = this.tradingDispatcher;
        TradingDriver driver = new TradingDriver(stateContext, dispatcher);

        // 3. 填充股票信息
        try {
            populateStockInfo(stateContext);
        } catch (Exception e) {
            log.error("获取股票信息失败: ticker={}", request.getTicker(), e);
            stateContext.sendError("无法获取股票信息: " + e.getMessage());
            return;
        }

        // 4. 将 tradingContext 存入 DynamicContext，供节点读取
        dynamicContext.setValue("trading_context", stateContext.getTradingContext());

        // 5. 设置 ThreadLocal Driver，启动状态机
        try {
            TradingDriver.setCurrent(driver);
            stateContext.transitionTo(TradingPhase.INIT);
            dispatcher.onEvent(TradingEvent.START_TRADING, stateContext);
        } finally {
            TradingDriver.clear();
            if (stateContext.getCurrentPhase() == TradingPhase.FINAL_REPORT) {
                stateContext.sendSseResult("trading", "trading_complete", "交易分析完成", true);
            }
        }
    }

    private void populateStockInfo(TradingStateContext stateContext) {
        StockAnalysisRequestVO request = stateContext.getRequest();
        if (request == null) {
            throw new IllegalStateException("股票分析请求为空");
        }
        StockInfoVO stockInfo = dataProvider.getStockInfo(request.getTicker());
        if (stockInfo == null) {
            throw new IllegalStateException("无法获取股票信息 ticker=" + request.getTicker());
        }
        stateContext.getTradingContext().setStockInfo(stockInfo);
        log.info("交易 Agent 初始化完成: ticker={}, analysts={}",
                stockInfo.getTicker(), stateContext.getSelectedAnalysts());
    }
}
