package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory.DynamicContext;
import denny.ai.agent.domain.service.sse.SseEventSender;
import denny.ai.agent.domain.service.sse.SseEventSink;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 交易状态机请求级上下文。
 * 每次请求 new 一个，请求间不共享。
 * 持有所有共享数据：TradingContextVO、DynamicContext、SSE sender、当前阶段、索引等。
 */
@Getter
@Slf4j
public class TradingStateContext {

    private static final List<AnalystTypeEnum> DEFAULT_ANALYST_LIST = List.of(
            AnalystTypeEnum.FUNDAMENTAL, AnalystTypeEnum.TECHNICAL,
            AnalystTypeEnum.SENTIMENT, AnalystTypeEnum.NEWS);
    private static final String SSE_DISCONNECTED_KEY = "sseDisconnected";
    private static final String SSE_EVENT_SINK_KEY = "sseEventSink";

    private final StockAnalysisRequestVO request;
    private final DynamicContext dynamicContext;
    private final SseEventSender sseSender;
    private final TradingContextVO tradingContext;
    private final List<AnalystTypeEnum> selectedAnalysts;

    @Getter
    private TradingPhase currentPhase;
    private int analystIndex;
    private int riskDebateRound;
    private String latestDebateSpeaker;
    private String latestRiskSpeaker;
    private String errorMessage;
    private final AtomicBoolean terminal = new AtomicBoolean(false);

    public TradingStateContext(StockAnalysisRequestVO request,
                               DynamicContext dynamicContext,
                               SseEventSender sseSender) {
        this.request = request;
        this.dynamicContext = dynamicContext;
        this.sseSender = sseSender;
        this.tradingContext = TradingContextVO.empty();
        this.currentPhase = TradingPhase.INIT;
        this.analystIndex = 0;
        this.riskDebateRound = 0;
        this.selectedAnalysts = (request != null && request.getSelectedAnalysts() != null
                && !request.getSelectedAnalysts().isEmpty())
                ? request.getSelectedAnalysts()
                : DEFAULT_ANALYST_LIST;
    }

    public void setAnalystIndex(int index) {
        this.analystIndex = index;
    }

    /**
     * 阶段标记
     */
    public void transitionTo(TradingPhase phase) {
        log.info("阶段变更: {} → {}", this.currentPhase, phase);
        this.currentPhase = phase;
    }

    /**
     * 设置 ERROR 状态，发送 completed=true SSE
     */
    public void sendError(String msg) {
        sendTerminalErrorOnce(msg);
        countDownTaskLatch();
    }

    public boolean isTerminal() {
        return terminal.get();
    }

    public boolean sendTerminalCompleteOnce() {
        if (!terminal.compareAndSet(false, true)) {
            log.debug("终态完成事件已发送，跳过重复发送");
            return false;
        }
        boolean sent = sendSseResultBypassTerminalGuardWithResult(
                "trading", "trading_complete", "交易分析完成", true);
        if (!sent) {
            terminal.compareAndSet(true, false);
        }
        logTerminalDelivery(sent, "trading", "trading_complete");
        return sent;
    }

    public boolean sendTerminalErrorOnce(String msg) {
        this.errorMessage = msg;
        this.currentPhase = TradingPhase.ERROR;
        log.error("交易流程进入 ERROR 状态: {}", msg);

        if (!terminal.compareAndSet(false, true)) {
            log.debug("终态错误事件已发送，跳过重复发送: {}", msg);
            return false;
        }

        String friendlyMessage = getFriendlyErrorMessage(msg);
        boolean sent = sendSseResultBypassTerminalGuardWithResult(
                "trading", "error", friendlyMessage, true);
        if (!sent) {
            terminal.compareAndSet(true, false);
        }
        logTerminalDelivery(sent, "trading", "error");
        return sent;
    }

    /**
     * 将技术错误信息转换为用户友好的中文提示
     */
    private String getFriendlyErrorMessage(String originalMsg) {
        if (originalMsg == null) {
            return "交易分析过程中发生未知错误，请稍后重试";
        }

        String lowerMsg = originalMsg.toLowerCase();

        // Prompt 超长
        if (lowerMsg.contains("prompt exceeds max length") || lowerMsg.contains("max length")
                || lowerMsg.contains("1261")) {
            return "请求内容过长，AI模型处理能力有限。建议减少分析范围或简化问题描述后重试。";
        }
        // 超时
        if (lowerMsg.contains("timeout") || lowerMsg.contains("超时")) {
            return "网络请求超时，服务器响应时间过长。请检查网络连接后重试。";
        }
        // 连接失败
        if (lowerMsg.contains("connection") || lowerMsg.contains("connect")
                || lowerMsg.contains("failed to connect")) {
            return "无法连接到AI服务，请稍后重试或联系技术支持。";
        }
        // 限流
        if (lowerMsg.contains("rate limit") || lowerMsg.contains("429")
                || lowerMsg.contains("too many requests")) {
            return "请求过于频繁，请稍后再试。";
        }
        // 认证失败
        if (lowerMsg.contains("unauthorized") || lowerMsg.contains("401")
                || lowerMsg.contains("api key") || lowerMsg.contains("认证")) {
            return "认证失败，请检查账户权限设置。";
        }
        // 股票代码未找到
        if ((lowerMsg.contains("股票代码") || lowerMsg.contains("ticker"))
                && (lowerMsg.contains("不能为空") || lowerMsg.contains("null")
                    || lowerMsg.contains("not found") || lowerMsg.contains("未找到")
                    || lowerMsg.contains("不存在"))) {
            return "未能识别股票代码，请提供完整的股票代码（如600519、000858）或公司全称。";
        }
        // 分析师执行异常
        if (lowerMsg.contains("分析师执行异常") || lowerMsg.contains("analyst")) {
            return "分析过程中遇到问题，请稍后重试或调整分析参数。";
        }
        // 节点执行异常
        if (lowerMsg.contains("节点执行异常") || lowerMsg.contains("节点处理异常")) {
            return "处理过程中遇到问题，请稍后重试。";
        }

        // 默认友好消息
        return "分析过程中遇到问题（" + truncateMessage(originalMsg, 50) + "），请稍后重试或简化问题描述。";
    }

    private String truncateMessage(String msg, int maxLen) {
        if (msg == null || msg.length() <= maxLen) {
            return msg;
        }
        return msg.substring(0, maxLen) + "...";
    }

    /**
     * 发送 SSE 结果，含异常捕获
     */
    public void sendSseResult(String type, String subType, String content, boolean completed) {
        if (terminal.get()) {
            log.debug("交易流程已终态，丢弃 late SSE: type={}, subType={}", type, subType);
            return;
        }
        sendSseResultBypassTerminalGuardWithResult(type, subType, content, completed);
    }

    /**
     * Like sendSseResultBypassTerminalGuard but returns whether sending succeeded.
     * Used by terminal events to allow the caller to handle send failures.
     */
    private boolean sendSseResultBypassTerminalGuardWithResult(String type, String subType, String content, boolean completed) {
        try {
            if (sseSender == null || dynamicContext == null) {
                log.warn("SSE sender 或 dynamicContext 为空，跳过发送");
                return false;
            }
            if (!completed && isSseDisconnected()) {
                log.debug("非终止事件，SSE连接已断开，跳过发送: type={}, subType={}", type, subType);
                return false;
            }
            AutoAgentExecuteResultEntity event = AutoAgentExecuteResultEntity.builder()
                    .type(type)
                    .subType(subType)
                    .step(dynamicContext.getStep())
                    .content(content)
                    .completed(completed)
                    .timestamp(System.currentTimeMillis())
                    .build();
            return sseSender.send(type, event);
        } catch (Exception e) {
            markSseDisconnected();
            log.warn("SSE 发送失败，断连或客户端异常: type={}, subType={}, error={}",
                    type, subType, e.getMessage());
            return false;
        }
    }

    private void logTerminalDelivery(boolean sent, String type, String subType) {
        String sessionId = request != null ? request.getSessionId() : null;
        String route = dynamicContext != null && dynamicContext.getValue(SSE_EVENT_SINK_KEY) != null
                ? "sink" : "raw_emitter";
        if (sent) {
            log.info("SSE terminal delivery accepted: sessionId={}, type={}, subType={}, route={}",
                    sessionId, type, subType, route);
        } else {
            log.warn("SSE terminal delivery rejected or failed: sessionId={}, type={}, subType={}, route={}",
                    sessionId, type, subType, route);
        }
    }

    private boolean isSseDisconnected() {
        SseEventSink sink = dynamicContext.getValue(SSE_EVENT_SINK_KEY);
        if (sink != null) {
            return !sink.shouldContinue();
        }
        Object disconnected = dynamicContext.getValue(SSE_DISCONNECTED_KEY);
        if (disconnected instanceof AtomicBoolean flag) {
            return flag.get();
        }
        return Boolean.TRUE.equals(disconnected);
    }

    private void markSseDisconnected() {
        SseEventSink sink = dynamicContext.getValue(SSE_EVENT_SINK_KEY);
        if (sink != null) {
            sink.markDisconnected(null);
            return;
        }
        Object disconnected = dynamicContext.getValue(SSE_DISCONNECTED_KEY);
        if (disconnected instanceof AtomicBoolean flag) {
            flag.set(true);
        } else {
            dynamicContext.setValue(SSE_DISCONNECTED_KEY, true);
        }
    }

    /**
     * 通知交易流程结束，使 TradingStarter 的 latch countDown
     */
    public void countDownTaskLatch() {
        CountDownLatch latch = dynamicContext.getValue("taskLatch");
        if (latch != null) {
            if (latch.getCount() > 0) {
                latch.countDown();
                log.info("任务流程全部完成，taskLatch 倒计时");
            } else {
                log.debug("taskLatch 已经倒计时完成，跳过重复 countDown");
            }
        } else {
            log.warn("taskLatch 不存在，可能已倒计时");
        }
    }

    public void setLatestDebateSpeaker(String latestDebateSpeaker) {
        this.latestDebateSpeaker = latestDebateSpeaker;
    }

    public void setLatestRiskSpeaker(String latestRiskSpeaker) {
        this.latestRiskSpeaker = latestRiskSpeaker;
    }
}
