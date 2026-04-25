package denny.ai.agent.trading.domain.config;

/**
 * 交易 Agent 状态机阶段枚举。
 * <p>
 * 贯穿整个交易分析流程，每个阶段对应状态机的一个状态。
 */
public enum TradingPhase {

    /**
     * 起始状态
     */
    INIT,

    /**
     * 分析师收集阶段（含内部 round-robin）
     */
    ANALYST_COLLECTION,

    /**
     * 多空辩论阶段（含内部 round-robin）
     */
    INVESTMENT_DEBATE,

    /**
     * 交易员决策阶段
     */
    TRADER_DECISION,

    /**
     * 风控阶段（含内部 round-robin）
     */
    RISK_MANAGEMENT,

    /**
     * 终止状态
     */
    FINAL_REPORT,

    /**
     * 异常终止状态
     */
    ERROR
}
