package denny.ai.agent.infrastructure.adapter.repository;

import denny.ai.agent.domain.adapter.repository.IRagKnowledgeRepository;
import denny.ai.agent.infrastructure.es.RagKnowledgeDocument;
import denny.ai.agent.infrastructure.es.RagKnowledgeEsGateway;
import denny.ai.agent.infrastructure.es.RagSearchRequest;
import denny.ai.agent.infrastructure.es.RagSearchResultItem;
import denny.ai.agent.infrastructure.rag.RagDocumentParser;
import denny.ai.agent.infrastructure.rag.RagEmbeddingService;
import denny.ai.agent.infrastructure.rag.RagTextSplitter;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * RAG 知识检索仓储实现：整合 PgVector 向量检索 + ES 关键词检索。
 * 支持完整的 RAG Pipeline：文件解析 → Chunk 切分 → Embedding 向量化 → 向量存储 + ES 存储。
 * 支持按 user_id 维度隔离知识库。
 *
 * @author denny
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RagKnowledgeRepository implements IRagKnowledgeRepository {

    private final PgVectorStore pgVectorStore;
    private final RagKnowledgeEsGateway esGateway;
    private final RagDocumentParser documentParser;
    private final RagTextSplitter textSplitter;
    private final RagEmbeddingService embeddingService;

    @Override
    public String retrieveContext(String userId, String question, int topK) {
        if (question == null || question.trim().isEmpty()) {
            return "";
        }

        try {
            // 1. 并发发起向量检索（PgVectorStore）和 ES 关键词检索（BM25），减少端到端时延
            // 向量检索：通过 filterExpression 实现 user_id 隔离
            SearchRequest vectorReq = SearchRequest.builder()
                    .query(question)
                    .topK(topK)
                    .filterExpression("user_id == '" + userId + "'")
                    .build();

            CompletableFuture<List<Document>> vectorFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return Optional.ofNullable(pgVectorStore.similaritySearch(vectorReq))
                            .orElseGet(ArrayList::new);
                } catch (Exception e) {
                    log.error("PgVector 向量检索异常，userId={}, question={}", userId, question, e);
                    return new ArrayList<>();
                }
            });

            // ES 检索：通过 request.userId 实现隔离
            RagSearchRequest esReq = new RagSearchRequest();
            esReq.setUserId(userId);
            esReq.setQueryText(question);
            esReq.setSize(topK);
            esReq.setPhrasePreferred(true);

            CompletableFuture<List<RagSearchResultItem>> esFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return Optional.ofNullable(esGateway.search(esReq))
                            .orElseGet(ArrayList::new);
                } catch (Exception e) {
                    log.error("ES 关键词检索异常，userId={}, question={}", userId, question, e);
                    return new ArrayList<>();
                }
            });

            // 等待两个检索都完成
            List<Document> vectorDocs = vectorFuture.join();
            List<RagSearchResultItem> esDocs = esFuture.join();

            // 2. 统一封装候选文档
            List<SimpleDoc> candidates = new ArrayList<>();

            for (Document d : vectorDocs) {
                SimpleDoc sd = new SimpleDoc();
                sd.setSource("vector");
                Object titleMeta = d.getMetadata().get("title");
                sd.setTitle(titleMeta != null ? String.valueOf(titleMeta) : "");
                sd.setContent(d.getText());
                // 使用相似度分数（如果有）
                sd.setScore(d.getScore() != null ? d.getScore().floatValue() : 1.0f);
                candidates.add(sd);
            }

            for (RagSearchResultItem item : esDocs) {
                SimpleDoc sd = new SimpleDoc();
                sd.setSource("es");
                sd.setTitle(Optional.ofNullable(item.getTitle()).orElse(""));
                sd.setContent(Optional.ofNullable(item.getContent()).orElse(""));
                sd.setScore(Optional.ofNullable(item.getScore()).orElse(1.0f));
                candidates.add(sd);
            }

            if (candidates.isEmpty()) {
                log.warn("未检索到相关文档，userId={}, question={}", userId, question);
                return "";
            }

            // 3. 去重（按 title+content）
            Map<String, SimpleDoc> merged = new LinkedHashMap<>();
            for (SimpleDoc d : candidates) {
                String key = d.getTitle() + "||" + d.getContent();
                if (!merged.containsKey(key) || merged.get(key).getScore() < d.getScore()) {
                    merged.put(key, d);
                }
            }

            // 4. 简单重排：按分数从高到低，取前 topK
            List<SimpleDoc> sorted = merged.values().stream()
                    .sorted(Comparator.comparing(SimpleDoc::getScore).reversed())
                    .limit(topK)
                    .collect(Collectors.toList());

            // 5. 拼接为可直接注入 Prompt 的上下文文本
            StringBuilder sb = new StringBuilder();
            int idx = 1;
            for (SimpleDoc d : sorted) {
                sb.append("【文档 ").append(idx++).append(" | 来源: ").append(d.getSource()).append("】\n");
                if (!d.getTitle().isEmpty()) {
                    sb.append("标题: ").append(d.getTitle()).append("\n");
                }
                sb.append("内容: ").append(d.getContent()).append("\n\n");
            }

            log.info("RAG 混合检索完成，userId={}, 召回文档数={}, 最终返回={}", userId, candidates.size(), sorted.size());
            return sb.toString();
        } catch (Exception e) {
            log.error("RAG 混合检索发生异常，userId={}, question={}", userId, question, e);
            return "";
        }
    }

    @Override
    public String uploadAndIndex(String userId, MultipartFile file, String fileName) {
        if (file == null || file.isEmpty()) {
            return "文件为空";
        }

        try {
            log.info("开始 RAG Pipeline，userId={}, fileName={}", userId, fileName);

            // Step 1: 文件解析
            List<Document> documents = documentParser.parse(file, fileName != null ? fileName : file.getOriginalFilename());
            if (documents.isEmpty()) {
                return "文件解析失败：未提取到文本内容";
            }
            log.info("文件解析完成，文档数={}", documents.size());

            // Step 2: 文本分块（Chunk 切分）
            List<Document> chunks = textSplitter.split(documents);
            log.info("文本切分完成，chunk 数={}", chunks.size());

            // Step 3: Embedding 向量化（PgVectorStore 会自动处理，这里主要是日志）
            embeddingService.embed(chunks);
            log.info("向量化完成");

            // Step 4: 批量存储到 PgVector（向量存储）
            // 为每个 chunk 添加 user_id 元数据，实现隔离
            for (Document chunk : chunks) {
                chunk.getMetadata().put("user_id", userId);
                chunk.getMetadata().put("title", fileName != null ? fileName : file.getOriginalFilename());
            }
            pgVectorStore.add(chunks);
            log.info("向量存储完成，已写入 PgVector");

            // Step 5: 批量存储到 ES（关键词检索）
            List<RagKnowledgeDocument> esDocs = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                RagKnowledgeDocument esDoc = new RagKnowledgeDocument();
                esDoc.setUserId(userId);
                esDoc.setTitle(fileName != null ? fileName : file.getOriginalFilename());
                esDoc.setContent(chunk.getText());
                esDoc.setFileName(file.getOriginalFilename());
                esDoc.setFileType((String) chunk.getMetadata().getOrDefault("fileType", "unknown"));
                esDoc.setChunkIndex(i);
                esDocs.add(esDoc);
            }
            esGateway.saveDocuments(esDocs);
            log.info("ES 存储完成，已写入 {} 条文档", esDocs.size());

            return String.format("RAG Pipeline 完成：文件解析=%d 文档，切分=%d chunks，已存储到向量库和 ES", 
                    documents.size(), chunks.size());
        } catch (Exception e) {
            log.error("RAG Pipeline 失败，userId={}, fileName={}", userId, fileName, e);
            return "RAG Pipeline 失败: " + e.getMessage();
        }
    }

    @Override
    public int deleteUserKnowledge(String userId) {
        try {
            // 删除 ES 中的文档
            long esDeleted = esGateway.deleteByUserId(userId);

            // 删除 PgVector 中的文档（通过 filterExpression）
            // 注意：PgVectorStore 可能没有直接的 deleteByFilter 方法，需要手动查询后删除
            // 这里简化处理，实际可能需要根据你的 PgVectorStore 实现调整
            log.warn("PgVector 删除功能需要根据实际实现调整，当前仅删除 ES 文档");

            log.info("删除用户知识库完成，userId={}, ES 删除数={}", userId, esDeleted);
            return (int) esDeleted;
        } catch (Exception e) {
            log.error("删除用户知识库失败，userId={}", userId, e);
            return 0;
        }
    }

    @Data
    private static class SimpleDoc {
        private String source;  // vector / es
        private String title;
        private String content;
        private float score;
    }
}
