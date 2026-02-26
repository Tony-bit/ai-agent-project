package denny.ai.agent.infrastructure.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 使用 ElasticsearchClient 的简单 RAG 知识库网关示例。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagKnowledgeEsGateway {

    private static final String INDEX = "rag-knowledge";

    private final ElasticsearchClient esClient;

    /**
     * 写入一条文档到 ES（支持 user_id 隔离）。
     */
    public String saveDocument(RagKnowledgeDocument doc) throws IOException {
        IndexRequest<RagKnowledgeDocument> request = IndexRequest.of(b -> b
                .index(INDEX)
                .document(doc)
                .refresh(co.elastic.clients.elasticsearch._types.Refresh.True)
        );

        IndexResponse response = esClient.index(request);
        String id = response.id();
        log.info("Saved rag-knowledge doc id={}, userId={}, title={}", id, doc.getUserId(), doc.getTitle());
        return id;
    }

    /**
     * 批量写入文档到 ES。
     */
    public void saveDocuments(List<RagKnowledgeDocument> documents) throws IOException {
        for (RagKnowledgeDocument doc : documents) {
            saveDocument(doc);
        }
        log.info("批量写入 ES 完成，文档数={}", documents.size());
    }

    /**
     * 按 user_id 删除知识库文档。
     */
    public long deleteByUserId(String userId) throws IOException {
        // 使用 delete by query API
        var deleteResponse = esClient.deleteByQuery(d -> d
                .index(INDEX)
                .query(q -> q
                        .term(t -> t
                                .field("user_id")
                                .value(userId)
                        )
                )
        );
        long deleted = deleteResponse.deleted();
        log.info("删除用户知识库完成，userId={}, 删除文档数={}", userId, deleted);
        return deleted;
    }

    /**
     * 写入一条示例文档（兼容旧接口）。
     */
    public String saveSampleDoc() throws IOException {
        RagKnowledgeDocument doc = new RagKnowledgeDocument();
        doc.setUserId("sample_user");
        doc.setTitle("下单接口错误码说明");
        doc.setContent("本接口用于创建订单，常见错误码包括：ORDER_DUPLICATED, STOCK_NOT_ENOUGH, INVALID_COUPON 等。");
        return saveDocument(doc);
    }

    /**
     * 基于 BM25 的精确匹配搜索（支持 user_id 隔离）。
     */
    public List<RagSearchResultItem> search(RagSearchRequest request) throws IOException {
        String q = request.getQueryText();
        String userId = request.getUserId();

        SearchRequest esRequest = SearchRequest.of(b -> b
                .index(INDEX)
                .size(request.getSize())
                .query(queryBuilder -> queryBuilder
                        .bool(bool -> {
                            // 0. 必须匹配 user_id（知识库隔离）
                            if (userId != null && !userId.trim().isEmpty()) {
                                bool.must(m -> m
                                        .term(t -> t
                                                .field("user_id")
                                                .value(userId)
                                        )
                                );
                            }

                            // 1. 精确短语匹配优先，权重更高
                            if (request.isPhrasePreferred()) {
                                bool.should(s -> s
                                        .matchPhrase(mp -> mp
                                                .field("title")
                                                .query(q)
                                                .boost(3.0f)
                                        )
                                );
                                bool.should(s -> s
                                        .matchPhrase(mp -> mp
                                                .field("content")
                                                .query(q)
                                                .boost(2.0f)
                                        )
                                );
                            }

                            // 2. 普通多字段关键词匹配 (BM25)
                            bool.should(s -> s
                                    .multiMatch(mm -> mm
                                            .fields("title^2", "content")
                                            .query(q)
                                    )
                            );

                            return bool;
                        })
                )
        );

        if (request.getMinScore() != null) {
            Query query = esRequest.query();
            esRequest = SearchRequest.of(b -> b
                    .index(INDEX)
                    .size(request.getSize())
                    .query(query)
                    .minScore(request.getMinScore().doubleValue())
            );
        }

        SearchResponse<RagKnowledgeDocument> response = esClient.search(esRequest, RagKnowledgeDocument.class);

        List<RagSearchResultItem> results = new ArrayList<>();
        response.hits().hits().forEach(hit -> {
            RagKnowledgeDocument doc = hit.source();
            if (doc != null) {
                RagSearchResultItem item = new RagSearchResultItem();
                item.setId(hit.id());
                item.setTitle(doc.getTitle());
                item.setContent(doc.getContent());
                item.setScore(hit.score() != null ? hit.score().floatValue() : null);
                results.add(item);
            }
        });

        return results;
    }

    /**
     * 方便调试：用关键词直接搜索并打印。
     */
    public void searchByKeyword(String keyword) throws IOException {
        RagSearchRequest req = new RagSearchRequest();
        req.setQueryText(keyword);
        List<RagSearchResultItem> results = search(req);
        results.forEach(item -> log.info("found doc id={}, title={}, score={}",
                item.getId(), item.getTitle(), item.getScore()));
    }
}
