package denny.ai.agent.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 意图识别 Few-Shot 样本 PO
 *
 * @author denny
 * 2026/5/11
 */
@Data
public class IntentFewshotSamplePO {

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
     * 向量数据（字符串形式，PGvector 内部管理）
     */
    private String embedding;

    /**
     * 状态：1=启用 0=禁用
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
