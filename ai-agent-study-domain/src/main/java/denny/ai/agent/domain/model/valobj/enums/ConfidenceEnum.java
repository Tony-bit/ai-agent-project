package denny.ai.agent.domain.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 意图识别置信度枚举
 *
 * @author denny
 * 2026/5/10
 */
@Getter
@AllArgsConstructor
public enum ConfidenceEnum {

    HIGH("HIGH", "高置信度"),
    MEDIUM("MEDIUM", "中置信度"),
    LOW("LOW", "低置信度");

    private final String code;
    private final String description;

    public static ConfidenceEnum fromCode(String code) {
        for (ConfidenceEnum confidence : values()) {
            if (confidence.code.equalsIgnoreCase(code)) {
                return confidence;
            }
        }
        return LOW;
    }
}
