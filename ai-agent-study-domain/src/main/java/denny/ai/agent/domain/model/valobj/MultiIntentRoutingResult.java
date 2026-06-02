package denny.ai.agent.domain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 多意图分解结果 VO
 *
 * @author denny
 * 2026/05/31
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MultiIntentRoutingResult {

    /**
     * 是否为多任务
     */
    private Boolean multiTask;

    /**
     * 是否需要信息补全
     */
    private Boolean needsClarification;

    /**
     * 缺失信息列表
     */
    private List<String> missingInfo;

    /**
     * 补全提示语
     */
    private String clarificationPrompt;

    /**
     * 分解后的子任务列表
     */
    private List<SubTask> taskList;

    /**
     * 分解判断理由
     */
    private String reasoning;
}
