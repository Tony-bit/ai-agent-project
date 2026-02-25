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
     * 写入一条示例文档。
     */
    public String saveSampleDoc() throws IOException {
        RagKnowledgeDocument doc = new RagKnowledgeDocument();
        doc.setTitle("下单接口错误码说明");
        doc.setContent("本接口用于创建订单，常见错误码包括：ORDER_DUPLICATED, STOCK_NOT_ENOUGH, INVALID_COUPON 等。");

        IndexRequest<RagKnowledgeDocument> request = IndexRequest.of(b -> b
                .index(INDEX)
                .document(doc)
                .refresh(co.elastic.clients.elasticsearch._types.Refresh.True)
        );

        IndexResponse response = esClient.index(request);
        String id = response.id();
        log.info("Saved rag-knowledge doc id={}", id);
        return id;
    }

    /**
     * 基于 BM25 的精确匹配搜索。
     */
    public List<RagSearchResultItem> search(RagSearchRequest request) throws IOException {
        String q = request.getQueryText();

        SearchRequest esRequest = SearchRequest.of(b -> b
                .index(INDEX)
                .size(request.getSize())
                .query(queryBuilder -> queryBuilder
                        .bool(bool -> {
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
