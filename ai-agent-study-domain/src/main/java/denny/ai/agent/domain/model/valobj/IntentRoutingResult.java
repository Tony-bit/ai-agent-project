package denny.ai.agent.domain.model.valobj;

import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 意图识别结果 VO（含切槽字段）
 *
 * @author denny
 * 2026/5/11
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IntentRoutingResult {

    /**
     * 识别的意图类型
     */
    private IntentTypeEnum intent;

    /**
     * 置信度
     */
    private ConfidenceEnum confidence;

    /**
     * 判断理由
     */
    private String reasoning;

    /**
     * 通用槽位（所有意图都有）
     */
    private BaseSlot baseSlot;

    /**
     * 意图专属槽位（Map 结构，STOCK_ANALYSIS 时包含 StockSlot）
     */
    private Map<String, Object> intentSpecificSlots;
}
