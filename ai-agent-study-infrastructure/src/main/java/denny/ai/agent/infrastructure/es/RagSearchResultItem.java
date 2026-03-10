package denny.ai.agent.infrastructure.es;

import lombok.Data;

/**
 * RAG/BM25 关键词检索结果项
 */
@Data
public class RagSearchResultItem {

    private String id;
    private String userId;
    private String title;
    private String content;
    private Float score;
}
