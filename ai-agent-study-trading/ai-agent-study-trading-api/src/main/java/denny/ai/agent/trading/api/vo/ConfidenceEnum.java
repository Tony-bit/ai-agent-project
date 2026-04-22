package denny.ai.agent.trading.api.vo;

/**
 * 置信度枚举，用于表示意图识别或决策的置信水平。
 */
public enum ConfidenceEnum {

    /**
     * 高置信度
     */
    HIGH("HIGH", "高置信度", 3),

    /**
     * 中置信度
     */
    MEDIUM("MEDIUM", "中置信度", 2),

    /**
     * 低置信度
     */
    LOW("LOW", "低置信度", 1);

    private final String code;
    private final String description;
    private final int level;

    ConfidenceEnum(String code, String description, int level) {
        this.code = code;
        this.description = description;
        this.level = level;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public int getLevel() {
        return level;
    }
}
