package denny.ai.agent.trading.domain.config;

/**
 * 交易状态机驱动类（请求级实例，ThreadLocal 传递）。
 * 职责：
 * 1.持有本次请求的 TradingStateContext 和 TradingDispatcher
 * 2.节点 doApply() 末尾通过 ThreadLocal 调用，驱动状态机流转
 * 3.提供 SSE 发送接口
 */
public class TradingDriver {

    private static final ThreadLocal<TradingDriver> CURRENT = new ThreadLocal<>();

    public static void setCurrent(TradingDriver driver) {
        CURRENT.set(driver);
    }

    public static TradingDriver getCurrent() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    private final TradingStateContext stateContext;
    private final TradingDispatcher dispatcher;

    public TradingDriver(TradingStateContext stateContext, TradingDispatcher dispatcher) {
        this.stateContext = stateContext;
        this.dispatcher = dispatcher;
    }

    public TradingStateContext getStateContext() {
        return stateContext;
    }

    public void analystComplete() {
        dispatcher.onEvent(TradingEvent.ANALYST_COMPLETE, stateContext);
    }

    public void allAnalystsComplete() {
        dispatcher.onEvent(TradingEvent.ALL_ANALYSTS_COMPLETE, stateContext);
    }

    public void debateComplete() {
        dispatcher.onEvent(TradingEvent.INVESTMENT_DEBATE_COMPLETE, stateContext);
    }

    public void debateContinue() {
        dispatcher.onEvent(TradingEvent.CONTINUE_DEBATE, stateContext);
    }

    public void debateFinish() {
        dispatcher.onEvent(TradingEvent.DEBATE_FINISH, stateContext);
    }

    public void recommendationComplete() {
        dispatcher.onEvent(TradingEvent.RECOMMENDATION_COMPLETE, stateContext);
    }

    public void riskDebateComplete() {
        dispatcher.onEvent(TradingEvent.RISK_DEBATE_COMPLETE, stateContext);
    }

    public void portfolioComplete() {
        dispatcher.onEvent(TradingEvent.PORTFOLIO_COMPLETE, stateContext);
    }

    public void errorOccurred(String msg) {
        stateContext.sendError(msg);
    }

    public void sendSseResult(String type, String subType, String content, boolean completed) {
        stateContext.sendSseResult(type, subType, content, completed);
    }
}
