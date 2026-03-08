package denny.ai.agent.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 离线评测运行结果表。
 */
@Data
public class RagEvalRunPO {

    private Long id;

    private Integer sampleSize;

    private Double hitAt5;

    private Double hitAt10;

    private Double mrrAt10;

    /**
     * 评测参数(JSON字符串)。
     */
    private String paramsJson;

    private LocalDateTime createTime;
}
