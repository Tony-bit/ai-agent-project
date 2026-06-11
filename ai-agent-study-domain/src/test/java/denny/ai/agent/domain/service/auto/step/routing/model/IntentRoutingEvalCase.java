package denny.ai.agent.domain.service.auto.step.routing.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Intent Routing 评测 Case 模型
 * <p>
 * 映射 intent-routing-cases.json 中的单条 case 结构。
 * response 字段在 fallback 类 case 中为纯字符串，
 * 在结构化 case 中为 JSON 字符串。
 *
 * @author denny
 * 2026/06/08
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IntentRoutingEvalCase {

    /**
     * 唯一标识 case ID
     */
    private String caseId;

    /**
     * 执行状态：pending / pass / fail
     */
    private String status;

    /**
     * 用例分类：single-task / multi-task / clarification / fallback
     */
    private String category;

    /**
     * 用例描述
     */
    private String description;

    /**
     * 模拟 LLM 返回内容。
     * fallback 类 case 为纯字符串（如 "invalid json"），
     * 结构化 case 为 JSON 字符串（经 JSON.toJSONString 序列化后存入）。
     */
    private String response;

    /**
     * 期望断言结果
     */
    private ExpectedResult expected;

    /**
     * 标签列表
     */
    private List<String> tags;

    /**
     * 期望结果对象
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ExpectedResult {

        /**
         * 是否多任务
         */
        private Boolean multiTask;

        /**
         * 是否需要澄清
         */
        private Boolean needsClarification;

        /**
         * 期望任务数量
         */
        private Integer taskCount;

        /**
         * 期望任务意图列表（顺序敏感）
         */
        private List<String> taskIntents;

        /**
         * 期望执行节点列表（顺序敏感）
         */
        private List<String> executorNodes;

        /**
         * Expected confidence values, in task order.
         */
        private List<String> confidences;

        /**
         * Expected task types, in task order.
         */
        private List<Integer> taskTypes;

        /**
         * Expected task statuses, in task order.
         */
        private List<String> taskStatuses;

        /**
         * 期望缺失信息列表（clarification 专属）
         */
        private List<String> missingInfo;

        /**
         * Expected clarification prompt after defaulting.
         */
        private String clarificationPrompt;

        /**
         * Optional stable fragment expected in the reasoning text.
         */
        private String reasoningContains;
    }
}
