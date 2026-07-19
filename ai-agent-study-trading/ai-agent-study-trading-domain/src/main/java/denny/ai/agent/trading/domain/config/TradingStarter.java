package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory.DynamicContext;
import denny.ai.agent.domain.service.sse.SseEventSender;
import denny.ai.agent.domain.service.sse.SseEventSink;
import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.api.vo.StockInfoVO;
import denny.ai.agent.trading.domain.pipeline.TradingPipeline;
import denny.ai.agent.trading.domain.pipeline.TradingPipelineException;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CountDownLatch;

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

    private static final String SSE_EVENT_SINK_KEY = "sseEventSink";

    @Resource
    private IStockDataProvider dataProvider;

    @Resource
    private TradingDispatcher tradingDispatcher;

    @Resource
    private TradingPipeline tradingPipeline;

    @Value("${spring.ai.trading.sync-pipeline-enabled:true}")
    private boolean syncPipelineEnabled = true;

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
                      SseEventSender sseSender) {
        // 1. 创建请求级上下文
        TradingStateContext stateContext = new TradingStateContext(request, dynamicContext, sseSender);

        if (!syncPipelineEnabled) {
            runLegacyAsyncDispatcherWithLatch(stateContext, dynamicContext);
            return;
        }

        try {
            // 2. 填充股票信息
            populateStockInfo(stateContext);

            // 3. 将 tradingContext 存入 DynamicContext，供节点读取
            dynamicContext.setValue("trading_context", stateContext.getTradingContext());

            // 4. 同步 pipeline 路径不设置真实 Driver，避免旧 Dispatcher 重入。
            TradingDriver.clear();
            stateContext.transitionTo(TradingPhase.INIT);
            tradingPipeline.execute(stateContext);
            if (stateContext.getCurrentPhase() == TradingPhase.ERROR) {
                stateContext.sendTerminalErrorOnce(stateContext.getErrorMessage());
            } else {
                boolean sent = stateContext.sendTerminalCompleteOnce();
                if (!sent) {
                    log.warn("终止完成事件发送失败，SSE连接可能已断开，跳过完整关闭");
                }
            }
        } catch (Exception e) {
            log.error("交易分析执行异常: ticker={}, error={}",
                    request != null ? request.getTicker() : null, e.getMessage(), e);
            stateContext.sendTerminalErrorOnce("交易分析执行异常: " + e.getMessage());
        } finally {
            TradingDriver.clear();
            completeOwnedSseSession(dynamicContext);
        }
    }

    private void runLegacyAsyncDispatcherWithLatch(TradingStateContext stateContext, DynamicContext dynamicContext) {
        TradingDriver driver = new TradingDriver(stateContext, tradingDispatcher);

        try {
            populateStockInfo(stateContext);
        } catch (Exception e) {
            log.error("获取股票信息失败: ticker={}", stateContext.getRequest().getTicker(), e);
            stateContext.sendError("无法获取股票信息: " + e.getMessage());
            return;
        }

        dynamicContext.setValue("trading_context", stateContext.getTradingContext());

        CountDownLatch taskLatch = new CountDownLatch(1);
        dynamicContext.setValue("taskLatch", taskLatch);

        try {
            TradingDriver.setCurrent(driver);
            stateContext.transitionTo(TradingPhase.INIT);
            tradingDispatcher.onEvent(TradingEvent.START_TRADING, stateContext);
        } finally {
            TradingDriver.clear();
            try {
                taskLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("taskLatch await 被中断: {}", e.getMessage());
            }
            taskLatch.countDown();
            log.info("taskLatch 已倒计时，异步任务全部完成");
            finishAndCloseSse(stateContext, dynamicContext);
        }
    }

    /**
     * 发送最终 SSE 并关闭 emitter。
     * 必须在 latch.await() 返回后再调用，避免发送时 emitter 已关闭。
     */
    private void finishAndCloseSse(TradingStateContext stateContext, DynamicContext dynamicContext) {
        try {
            if (stateContext.getCurrentPhase() == TradingPhase.FINAL_REPORT) {
                stateContext.sendTerminalCompleteOnce();
            }
        } catch (Exception e) {
            log.warn("发送完成事件异常，不影响 emitter 关闭: {}", e.getMessage());
        }

        completeOwnedSseSession(dynamicContext);
    }

    private void completeOwnedSseSession(DynamicContext dynamicContext) {
        SseEventSink sink = dynamicContext.getValue(SSE_EVENT_SINK_KEY);
        if (sink != null) {
            sink.complete();
            log.info("SSE sink close requested: state={}", sink.state());
            return;
        }
        log.info("SSE emitter close deferred to outer owner: owner=auto_agent");
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

    /**
     * 为多任务执行场景准备的同步执行方法。
     * <p>
     * 与 start() 的区别：
     * - 不发送 SSE 事件（emitter 为 null 时降级）
     * - 不关闭 emitter
     * - 直接执行并返回交易结果文本
     * </p>
     *
     * @param subTaskContent   子任务内容（从 SubTask.content 获取）
     * @param slots            子任务槽位信息（包含 stockCode 等）
     * @param dynamicContext    动态上下文
     * @return 交易分析结果的文本描述
     */
    @SuppressWarnings("unchecked")
    public String startForSubTask(String subTaskContent, Map<String, Object> slots,
                                  DynamicContext dynamicContext) {
        log.info("TradingStarter.startForSubTask: content={}, slots={}", subTaskContent, slots);

        String stockCode = slots != null && slots.get("stockCode") != null
                ? slots.get("stockCode").toString() : subTaskContent;

        StockAnalysisRequestVO request = StockAnalysisRequestVO.builder()
                .ticker(stockCode)
                .sessionId(dynamicContext.getValue("sessionId") != null
                        ? dynamicContext.getValue("sessionId").toString() : null)
                .build();

        StringBuilder resultBuilder = new StringBuilder();
        SseEventSender noOpSseSender = (type, event) -> true;

        TradingStateContext stateContext = new TradingStateContext(request, dynamicContext, noOpSseSender);

        if (!syncPipelineEnabled) {
            return startForSubTaskLegacy(dynamicContext, stateContext);
        }

        try {
            populateStockInfo(stateContext);
        } catch (Exception e) {
            log.error("获取股票信息失败: ticker={}", request.getTicker(), e);
            return "无法获取股票信息: " + e.getMessage();
        }

        dynamicContext.setValue("trading_context", stateContext.getTradingContext());

        try {
            TradingDriver.clear();
            stateContext.transitionTo(TradingPhase.INIT);
            tradingPipeline.execute(stateContext);
            if (stateContext.getCurrentPhase() == TradingPhase.ERROR) {
                return "交易分析执行异常: " + stateContext.getErrorMessage();
            }
        } catch (TradingPipelineException e) {
            log.error("交易分析执行异常: {}", e.getMessage(), e);
            return "交易分析执行异常: " + e.getMessage();
        } catch (Exception e) {
            log.error("交易分析执行异常: {}", e.getMessage(), e);
            return "交易分析执行异常: " + e.getMessage();
        } finally {
            TradingDriver.clear();
        }

        String result = buildResultFromContext(stateContext);
        if (result == null || result.isBlank()) {
            return "交易分析完成（无详细结果）";
        }
        return result;
    }

    private String startForSubTaskLegacy(DynamicContext dynamicContext, TradingStateContext stateContext) {
        try {
            populateStockInfo(stateContext);
        } catch (Exception e) {
            log.error("获取股票信息失败: ticker={}", stateContext.getRequest().getTicker(), e);
            return "无法获取股票信息: " + e.getMessage();
        }

        dynamicContext.setValue("trading_context", stateContext.getTradingContext());

        CountDownLatch taskLatch = new CountDownLatch(1);
        dynamicContext.setValue("taskLatch", taskLatch);

        TradingDriver driver = new TradingDriver(stateContext, tradingDispatcher);
        try {
            TradingDriver.setCurrent(driver);
            stateContext.transitionTo(TradingPhase.INIT);
            tradingDispatcher.onEvent(TradingEvent.START_TRADING, stateContext);
        } catch (Exception e) {
            log.error("交易分析执行异常: {}", e.getMessage(), e);
            return "交易分析执行异常: " + e.getMessage();
        } finally {
            TradingDriver.clear();
            try {
                taskLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("taskLatch await 被中断: {}", e.getMessage());
            }
            log.info("子任务异步任务完成，taskLatch 已倒计时");
        }

        String result = buildResultFromContext(stateContext);
        if (result == null || result.isBlank()) {
            return "交易分析完成（无详细结果）";
        }
        return result;
    }

    private String buildResultFromContext(TradingStateContext stateContext) {
        StringBuilder sb = new StringBuilder();
        TradingContextVO ctx = stateContext.getTradingContext();
        if (ctx == null) {
            return null;
        }
        if (ctx.getStockInfo() != null) {
            sb.append("【股票信息】").append(ctx.getStockInfo()).append("\n");
        }
        if (ctx.getTechnicalReport() != null) {
            sb.append("【技术分析】").append(ctx.getTechnicalReport()).append("\n");
        }
        if (ctx.getFundamentalReport() != null) {
            sb.append("【基本面分析】").append(ctx.getFundamentalReport()).append("\n");
        }
        if (ctx.getSentimentReport() != null) {
            sb.append("【情绪分析】").append(ctx.getSentimentReport()).append("\n");
        }
        if (ctx.getNewsReport() != null) {
            sb.append("【新闻分析】").append(ctx.getNewsReport()).append("\n");
        }
        if (ctx.getInvestmentDebate() != null && ctx.getInvestmentDebate().getConclusion() != null) {
            sb.append("【投资辩论结论】").append(ctx.getInvestmentDebate().getConclusion()).append("\n");
        }
        if (ctx.getFinalDecision() != null) {
            sb.append("【最终决策】").append(ctx.getFinalDecision()).append("\n");
        }
        return sb.toString().trim();
    }
}
