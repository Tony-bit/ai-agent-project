package denny.ai.agent.trading.api.vo;

/**
 * 交易决策枚举，定义最终交易决策类型。
 */
public enum TradeDecisionEnum {

    /**
     * 买入
     */
    BUY("BUY", "买入", 1),

    /**
     * 卖出
     */
    SELL("SELL", "卖出", 2),

    /**
     * 持有
     */
    HOLD("HOLD", "持有", 3),

    /**
     * 跳过（不推荐操作）
     */
    SKIP("SKIP", "跳过", 0);

    private final String code;
    private final String description;
    private final int priority;

    TradeDecisionEnum(String code, String description, int priority) {
        this.code = code;
        this.description = description;
        this.priority = priority;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public int getPriority() {
        return priority;
    }
}
