package denny.ai.agent.domain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用槽位 VO（所有意图都携带的基础槽位）
 *
 * @author denny
 * 2026/5/11
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BaseSlot {

    /**
     * 用户查询的主题/话题
     */
    private String topic;

    /**
     * 用户情感倾向：positive / negative / neutral
     */
    private String sentiment;
}
