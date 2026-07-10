package denny.ai.agent.domain.model.valobj;

import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 子任务 VO
 * 用于表示意图分解后的单个任务
 *
 * @author denny
 * 2026/05/31
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SubTask {

    /**
     * 任务 ID（全局唯一）
     */
    private String taskId;

    /**
     * 任务序号（从 1 开始）
     */
    private Integer taskIndex;

    /**
     * 任务总数
     */
    private Integer totalTasks;

    /**
     * 任务内容（LLM 解析的原始任务描述）
     */
    private String content;

    /**
     * 任务意图类型
     */
    private IntentTypeEnum intent;

    /**
     * 执行节点名称（Spring Bean 名称）
     * 例如：tradingStarter、generalChatNode、step1AnalyzerNode
     */
    private String executorNode;

    /**
     * 置信度
     */
    private ConfidenceEnum confidence;

    /**
     * 任务专属槽位（如股票代码、查询类型等）
     */
    private Map<String, Object> slots;

    /**
     * 依赖的前置任务 ID 列表
     */
    private List<String> dependsOn;

    /**
     * 任务状态
     */
    private SubTaskStatus status;

    /**
     * 任务执行结果
     */
    private String result;

    /**
     * 任务执行耗时（ms）
     */
    private Long latencyMs;

    /**
     * 任务执行错误信息
     */
    private String errorMessage;

    /**
     * 模型配置类型（用于选择 ChatClient）
     * 默认 0 表示使用通用模型
     */
    private Integer taskType = 0;

    /**
     * 任务状态枚举
     */
    public enum SubTaskStatus {
        PENDING,    // 待执行
        IN_PROGRESS,// 执行中
        COMPLETED,  // 已完成
        FAILED      // 执行失败
    }
}
