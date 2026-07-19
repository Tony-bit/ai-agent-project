package denny.ai.agent.domain.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 意图分类枚举
 *
 * @author denny
 * 2026/5/10
 */
@Getter
@AllArgsConstructor
public enum IntentTypeEnum {

    FINANCIAL_GENERAL("FINANCIAL_GENERAL", "通用金融查询"),
    STOCK_ANALYSIS("STOCK_ANALYSIS", "股票/市场分析"),
    PE_REASONING("PE_REASONING", "PE逻辑推理任务"),
    PE_CALCULATION("PE_CALCULATION", "PE计算任务"),
    PE_RETRIEVAL("PE_RETRIEVAL", "PE知识检索任务"),
    INSPECTION("INSPECTION", "系统巡检"),
    GENERAL_CHAT("GENERAL_CHAT", "通用对话"),
    AMBIGUOUS("AMBIGUOUS", "意图模糊需澄清"),
    UNKNOWN("UNKNOWN", "未知意图");

    private final String code;
    private final String description;

    public static IntentTypeEnum fromCode(String code) {
        for (IntentTypeEnum intent : values()) {
            if (intent.code.equals(code)) {
                return intent;
            }
        }
        return UNKNOWN;
    }
}
