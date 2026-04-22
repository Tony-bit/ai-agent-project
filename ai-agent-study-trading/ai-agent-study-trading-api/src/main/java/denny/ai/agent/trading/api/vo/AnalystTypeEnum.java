package denny.ai.agent.trading.api.vo;

/**
 * 分析师类型枚举，定义四类分析师角色。
 */
public enum AnalystTypeEnum {

    /**
     * 基本面分析师
     */
    FUNDAMENTAL("FUNDAMENTAL", "基本面分析师", 1),

    /**
     * 技术面分析师
     */
    TECHNICAL("TECHNICAL", "技术面分析师", 2),

    /**
     * 情绪面分析师
     */
    SENTIMENT("SENTIMENT", "情绪面分析师", 3),

    /**
     * 新闻面分析师
     */
    NEWS("NEWS", "新闻面分析师", 4);

    private final String code;
    private final String description;
    private final int sortOrder;

    AnalystTypeEnum(String code, String description, int sortOrder) {
        this.code = code;
        this.description = description;
        this.sortOrder = sortOrder;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
