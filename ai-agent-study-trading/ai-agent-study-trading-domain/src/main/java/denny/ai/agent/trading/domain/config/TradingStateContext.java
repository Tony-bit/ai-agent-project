package denny.ai.agent.trading.domain.config;

import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory.DynamicContext;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 交易状态机请求级上下文。
 * <p>
 * 每次请求 new 一个，请求间不共享。
 * <p>
 * 持有所有共享数据：TradingContextVO、DynamicContext、SSE sender、当前阶段、索引等。
 */
@Getter
@Slf4j
public class TradingStateContext {

    private static final List<AnalystTypeEnum> DEFAULT_ANALYST_LIST = List.of(
            AnalystTypeEnum.FUNDAMENTAL, AnalystTypeEnum.TECHNICAL,
            AnalystTypeEnum.SENTIMENT, AnalystTypeEnum.NEWS);

    private final StockAnalysisRequestVO request;
    private final DynamicContext dynamicContext;
    private final BiConsumer<String, Object> sseSender;
    private final TradingContextVO tradingContext;
    private final List<AnalystTypeEnum> selectedAnalysts;

    @Getter
    private TradingPhase currentPhase;
    private int analystIndex;
    private int riskDebateRound;
    private String latestDebateSpeaker;
    private String latestRiskSpeaker;
    private String errorMessage;

    public TradingStateContext(StockAnalysisRequestVO request,
                               DynamicContext dynamicContext,
                               BiConsumer<String, Object> sseSender) {
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
     * 阶段变更
     */
    public void transitionTo(TradingPhase phase) {
        log.info("阶段变更: {} → {}", this.currentPhase, phase);
        this.currentPhase = phase;
    }

    /**
     * 设置 ERROR 状态，发送 completed=true SSE
     */
    public void sendError(String msg) {
        this.errorMessage = msg;
        this.currentPhase = TradingPhase.ERROR;
        log.error("交易流程进入 ERROR 状态: {}", msg);
        sendSseResult("trading", "error", msg != null ? msg : "交易分析过程中发生错误，请重试", true);
    }

    /**
     * 发送 SSE 结果，含异常捕获
     */
    public void sendSseResult(String type, String subType, String content, boolean completed) {
        try {
            if (sseSender == null || dynamicContext == null) {
                log.warn("SSE sender 或 dynamicContext 为空，跳过发送");
                return;
            }
            AutoAgentExecuteResultEntity event = AutoAgentExecuteResultEntity.builder()
                    .type(type)
                    .subType(subType)
                    .step(dynamicContext.getStep())
                    .content(content)
                    .completed(completed)
                    .timestamp(System.currentTimeMillis())
                    .build();
            sseSender.accept(type, event);
        } catch (Exception e) {
            log.warn("SSE 发送失败，断连或客户端异常: type={}, subType={}, error={}",
                    type, subType, e.getMessage());
        }
    }

    public void setLatestDebateSpeaker(String latestDebateSpeaker) {
        this.latestDebateSpeaker = latestDebateSpeaker;
    }

    public void setLatestRiskSpeaker(String latestRiskSpeaker) {
        this.latestRiskSpeaker = latestRiskSpeaker;
    }
}
