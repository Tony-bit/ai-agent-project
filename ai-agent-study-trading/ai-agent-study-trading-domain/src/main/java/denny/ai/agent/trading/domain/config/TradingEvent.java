package denny.ai.agent.trading.domain.config;

/**
 * 交易 Agent 状态机事件枚举。
 * <p>
 * 节点 doApply() 末尾通过 Driver 发送这些事件，驱动状态机流转。
 */
public enum TradingEvent {

    /**
     * 启动交易分析流程（INIT → ANALYST_COLLECTION）
     */
    START_TRADING,

    /**
     * 单个分析师完成（ANALYST_COLLECTION 自循环）
     */
    ANALYST_COMPLETE,

    /**
     * 所有分析师完成（ANALYST_COLLECTION → INVESTMENT_DEBATE）
     */
    ALL_ANALYSTS_COMPLETE,

    /**
     * 辩论节点完成（INVESTMENT_DEBATE 自循环路由）
     */
    INVESTMENT_DEBATE_COMPLETE,

    /**
     * RM 决定继续辩论
     */
    CONTINUE_DEBATE,

    /**
     * RM 决定结束辩论（→ TRADER_DECISION）
     */
    DEBATE_FINISH,

    /**
     * 交易员完成（TRADER_DECISION → RISK_MANAGEMENT）
     */
    TRADER_COMPLETE,

    /**
     * 风控节点完成（RISK_MANAGEMENT 自循环）
     */
    RISK_DEBATE_COMPLETE,

    /**
     * 组合经理完成（RISK_MANAGEMENT → FINAL_REPORT）
     */
    PORTFOLIO_COMPLETE,

    /**
     * 异常发生（任意状态 → ERROR）
     */
    ERROR_OCCURRED
}
