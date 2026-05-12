package denny.ai.agent.domain.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 意图识别 Few-Shot 样本实体
 *
 * @author denny
 * 2026/5/11
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IntentFewshotSample {

    private Long id;

    /**
     * 用户 query 原文
     */
    private String queryText;

    /**
     * 意图编码
     */
    private String intentCode;

    /**
     * LLM 应返回的完整 JSON 示例
     */
    private String exampleJson;

    /**
     * embedding 向量维度
     */
    private Integer dimension;

    /**
     * 向量数据（存储为字符串，PGvector 内部管理）
     */
    private String embedding;

    /**
     * 状态：1=启用 0=禁用
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public static final int STATUS_ENABLED = 1;

    public static final int STATUS_DISABLED = 0;
}
