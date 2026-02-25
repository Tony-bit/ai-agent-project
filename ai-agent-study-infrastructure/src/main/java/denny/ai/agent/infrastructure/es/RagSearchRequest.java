package denny.ai.agent.infrastructure.es;

import lombok.Data;

/**
 * RAG/BM25 关键词检索请求参数
 */
@Data
public class RagSearchRequest {

    /** 查询文本 */
    private String queryText;

    /** 是否更偏向短语精确匹配 */
    private boolean phrasePreferred = true;

    /** 返回条数 */
    private int size = 5;

    /** 最小得分（可选），用于过滤噪音 */
    private Float minScore;
}
