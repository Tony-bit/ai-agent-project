package denny.ai.agent.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 离线评测样本表。
 */
@Data
public class RagEvalCasePO {

    private Long id;

    /**
     * 租户/用户隔离维度。
     */
    private String userId;

    /**
     * 评测 query。
     */
    private String query;

    /**
     * 标准答案文档ID列表(JSON字符串)。
     */
    private String goldDocIds;

    /**
     * 状态(0:无效,1:有效)。
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
