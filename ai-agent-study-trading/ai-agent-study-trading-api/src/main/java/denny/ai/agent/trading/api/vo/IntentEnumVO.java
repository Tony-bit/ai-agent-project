package denny.ai.agent.trading.api.vo;

/**
 * 用户意图枚举，用于识别用户请求的类型。
 */
public enum IntentEnumVO {

    /**
     * 股票分析意图
     */
    STOCK_ANALYSIS("STOCK_ANALYSIS", "股票分析意图"),

    /**
     * 普通对话意图
     */
    GENERAL_CHAT("GENERAL_CHAT", "普通对话意图"),

    /**
     * 未知意图
     */
    UNKNOWN("UNKNOWN", "未知意图");

    private final String code;
    private final String description;

    IntentEnumVO(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
