package denny.ai.agent.infrastructure.adapter.repository;

import denny.ai.agent.domain.adapter.repository.IRagKnowledgeRepository;
import denny.ai.agent.infrastructure.adapter.repository.model.RagRetrievedDoc;
import denny.ai.agent.infrastructure.adapter.repository.model.RagSimpleDoc;
import denny.ai.agent.infrastructure.es.RagKnowledgeDocument;
import denny.ai.agent.infrastructure.es.RagKnowledgeEsGateway;
import denny.ai.agent.infrastructure.es.RagSearchRequest;
import denny.ai.agent.infrastructure.es.RagSearchResultItem;
import denny.ai.agent.infrastructure.rag.RagCrossEncoderService;
import denny.ai.agent.infrastructure.rag.RagDocumentParser;
import denny.ai.agent.infrastructure.rag.RagEmbeddingService;
import denny.ai.agent.infrastructure.rag.RagTextSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;

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

    private static final int RRF_K = 60;

    private final PgVectorStore pgVectorStore;
    private final RagKnowledgeEsGateway esGateway;
    private final RagDocumentParser documentParser;
    private final RagTextSplitter textSplitter;
    private final RagEmbeddingService embeddingService;
    private final RagCrossEncoderService crossEncoderService;

    @Override
    public String retrieveContext(String userId, String question, int topK) {
        if (question == null || question.trim().isEmpty()) {
            return "";
        }

        List<RagSimpleDoc> sorted = retrieveRankedDocsInternal(userId, question, topK);
        if (sorted.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (RagSimpleDoc d : sorted) {
            sb.append("【文档 ").append(idx++).append(" | 来源: ").append(d.getSource()).append("】\n");
            if (d.getTitle() != null && !d.getTitle().isEmpty()) {
                sb.append("标题: ").append(d.getTitle()).append("\n");
            }
            sb.append("内容: ").append(Optional.ofNullable(d.getContent()).orElse("")).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 评测专用检索接口：返回最终有序候选文档，用于计算 Hit@K / MRR。
     */
    public List<RagRetrievedDoc> retrieveRankedDocsForEval(String userId, String question, int topK) {
        List<RagSimpleDoc> sorted = retrieveRankedDocsInternal(userId, question, topK);
        return sorted.stream().map(d -> {
            RagRetrievedDoc rd = new RagRetrievedDoc();
            rd.setDocId(d.uniqueKey());
            rd.setSource(d.getSource());
            rd.setTitle(d.getTitle());
            rd.setContent(d.getContent());
            rd.setRrfScore(d.getRrfScore());
            rd.setRerankScore(d.getRerankScore());
            return rd;
        }).collect(Collectors.toList());
    }

    private List<RagSimpleDoc> retrieveRankedDocsInternal(String userId, String question, int topK) {
        try {
            int recallSize = Math.max(topK * 5, topK);
            int rerankCandidateSize = Math.min(100, Math.max(topK * 5, topK));

            List<Document> vectorDocs = retrieveVectorDocs(userId, question, recallSize);
            List<RagSearchResultItem> esDocs = retrieveEsDocs(userId, question, recallSize);

            if (vectorDocs.isEmpty() && esDocs.isEmpty()) {
                log.warn("未检索到相关文档，userId={}, question={}", userId, question);
                return Collections.emptyList();
            }

            List<RagSimpleDoc> vectorList = new ArrayList<>();
            for (int i = 0; i < vectorDocs.size(); i++) {
                Document d = vectorDocs.get(i);
                RagSimpleDoc sd = new RagSimpleDoc();
                sd.setSource("vector");
                Object titleMeta = d.getMetadata().get("title");
                sd.setTitle(titleMeta != null ? String.valueOf(titleMeta) : "");
                sd.setContent(Optional.ofNullable(d.getText()).orElse(""));
                sd.setScore(d.getScore() != null ? d.getScore().floatValue() : 1.0f);
                sd.setVectorRank(i + 1);
                vectorList.add(sd);
            }

            List<RagSimpleDoc> esList = new ArrayList<>();
            for (int i = 0; i < esDocs.size(); i++) {
                RagSearchResultItem item = esDocs.get(i);
                RagSimpleDoc sd = new RagSimpleDoc();
                sd.setSource("es");
                sd.setTitle(Optional.ofNullable(item.getTitle()).orElse(""));
                sd.setContent(Optional.ofNullable(item.getContent()).orElse(""));
                sd.setScore(Optional.ofNullable(item.getScore()).orElse(1.0f));
                sd.setEsRank(i + 1);
                esList.add(sd);
            }

            Map<String, RagSimpleDoc> merged = new LinkedHashMap<>();

            for (RagSimpleDoc d : vectorList) {
                String key = d.uniqueKey();
                merged.putIfAbsent(key, d);
                RagSimpleDoc m = merged.get(key);
                m.setVectorRank(Math.min(m.getVectorRank(), d.getVectorRank()));
                m.setRrfScore(m.getRrfScore() + (1.0d / (RRF_K + d.getVectorRank())));
            }

            for (RagSimpleDoc d : esList) {
                String key = d.uniqueKey();
                merged.putIfAbsent(key, d);
                RagSimpleDoc m = merged.get(key);
                m.setEsRank(Math.min(m.getEsRank(), d.getEsRank()));
                m.setRrfScore(m.getRrfScore() + (1.0d / (RRF_K + d.getEsRank())));
            }

            List<RagSimpleDoc> rrfCandidates = merged.values().stream()
                    .sorted(Comparator.comparing(RagSimpleDoc::getRrfScore).reversed())
                    .limit(rerankCandidateSize)
                    .collect(Collectors.toList());

            if (rrfCandidates.isEmpty()) {
                log.warn("RRF 融合后无可用候选，userId={}, question={}", userId, question);
                return Collections.emptyList();
            }

            List<RagSimpleDoc> sorted;
            try {
                List<String> passages = rrfCandidates.stream()
                        .map(d -> (d.getTitle() == null || d.getTitle().isEmpty() ? "" : "标题: " + d.getTitle() + "\n")
                                + Optional.ofNullable(d.getContent()).orElse(""))
                        .collect(Collectors.toList());

                List<Double> ceScores = crossEncoderService.score(question, passages);
                for (int i = 0; i < rrfCandidates.size(); i++) {
                    double ce = (ceScores != null && i < ceScores.size() && ceScores.get(i) != null)
                            ? ceScores.get(i)
                            : -1e9;
                    rrfCandidates.get(i).setRerankScore(ce);
                }

                sorted = rrfCandidates.stream()
                        .sorted(Comparator.comparing(RagSimpleDoc::getRerankScore).reversed())
                        .limit(topK)
                        .collect(Collectors.toList());
            } catch (Exception ceEx) {
                log.warn("Cross-Encoder 重排失败，降级使用 RRF 结果，userId={}, question={}", userId, question, ceEx);
                sorted = rrfCandidates.stream().limit(topK).collect(Collectors.toList());
            }

            log.info("RAG 混合检索完成，userId={}, vector召回={}, es召回={}, RRF候选={}, 最终返回={}",
                    userId, vectorDocs.size(), esDocs.size(), rrfCandidates.size(), sorted.size());
            return sorted;
        } catch (Exception e) {
            log.error("RAG 混合检索发生异常，userId={}, question={}", userId, question, e);
            return Collections.emptyList();
        }
    }

    private List<Document> retrieveVectorDocs(String userId, String question, int recallSize) {
        SearchRequest publicVectorReq = SearchRequest.builder()
                .query(question)
                .topK(recallSize)
                .build();

        CompletableFuture<List<Document>> publicVectorFuture = CompletableFuture.supplyAsync(() -> {
            try {
                List<Document> docs = Optional.ofNullable(pgVectorStore.similaritySearch(publicVectorReq))
                        .orElseGet(ArrayList::new);
                return docs.stream()
                        .filter(doc -> {
                            Object uid = doc.getMetadata().get("user_id");
                            return uid == null || String.valueOf(uid).isBlank();
                        })
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("PgVector 公共向量检索异常，userId={}, question={}", userId, question, e);
                return new ArrayList<>();
            }
        });

        CompletableFuture<List<Document>> privateVectorFuture;
        if (userId != null && !userId.isBlank()) {
            SearchRequest privateVectorReq = SearchRequest.builder()
                    .query(question)
                    .topK(recallSize)
                    .filterExpression("user_id == '" + userId + "'")
                    .build();

            privateVectorFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return Optional.ofNullable(pgVectorStore.similaritySearch(privateVectorReq))
                            .orElseGet(ArrayList::new);
                } catch (Exception e) {
                    log.error("PgVector 私有向量检索异常，userId={}, question={}", userId, question, e);
                    return new ArrayList<>();
                }
            });
        } else {
            privateVectorFuture = CompletableFuture.completedFuture(new ArrayList<>());
        }

        Map<String, Document> vectorMergedMap = new LinkedHashMap<>();
        for (Document doc : publicVectorFuture.join()) {
            String key = Optional.ofNullable(doc.getId())
                    .orElse(Optional.ofNullable(doc.getText()).orElse(""));
            vectorMergedMap.putIfAbsent(key, doc);
        }
        for (Document doc : privateVectorFuture.join()) {
            String key = Optional.ofNullable(doc.getId())
                    .orElse(Optional.ofNullable(doc.getText()).orElse(""));
            vectorMergedMap.putIfAbsent(key, doc);
        }

        return new ArrayList<>(vectorMergedMap.values());
    }

    private List<RagSearchResultItem> retrieveEsDocs(String userId, String question, int recallSize) {
        RagSearchRequest publicEsReq = new RagSearchRequest();
        publicEsReq.setUserId(null);
        publicEsReq.setQueryText(question);
        publicEsReq.setSize(recallSize);
        publicEsReq.setPhrasePreferred(true);

        CompletableFuture<List<RagSearchResultItem>> publicEsFuture = CompletableFuture.supplyAsync(() -> {
            try {
                List<RagSearchResultItem> items = Optional.ofNullable(esGateway.search(publicEsReq))
                        .orElseGet(ArrayList::new);
                return items.stream()
                        .filter(item -> item.getUserId() == null || item.getUserId().isBlank())
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("ES 公共关键词检索异常，userId={}, question={}", userId, question, e);
                return new ArrayList<>();
            }
        });

        CompletableFuture<List<RagSearchResultItem>> privateEsFuture;
        if (userId != null && !userId.isBlank()) {
            RagSearchRequest privateEsReq = new RagSearchRequest();
            privateEsReq.setUserId(userId);
            privateEsReq.setQueryText(question);
            privateEsReq.setSize(recallSize);
            privateEsReq.setPhrasePreferred(true);

            privateEsFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return Optional.ofNullable(esGateway.search(privateEsReq))
                            .orElseGet(ArrayList::new);
                } catch (Exception e) {
                    log.error("ES 私有关键词检索异常，userId={}, question={}", userId, question, e);
                    return new ArrayList<>();
                }
            });
        } else {
            privateEsFuture = CompletableFuture.completedFuture(new ArrayList<>());
        }

        Map<String, RagSearchResultItem> esMergedMap = new LinkedHashMap<>();
        for (RagSearchResultItem item : publicEsFuture.join()) {
            esMergedMap.putIfAbsent(Optional.ofNullable(item.getId()).orElse(item.getTitle() + "||" + item.getContent()), item);
        }
        for (RagSearchResultItem item : privateEsFuture.join()) {
            esMergedMap.putIfAbsent(Optional.ofNullable(item.getId()).orElse(item.getTitle() + "||" + item.getContent()), item);
        }

        return new ArrayList<>(esMergedMap.values());
    }

    @Override
    public String uploadAndIndex(String userId, MultipartFile file, String fileName) {
        if (file == null || file.isEmpty()) {
            return "文件为空";
        }

        try {
            log.info("开始 RAG Pipeline，userId={}, fileName={}", userId, fileName);

            List<Document> documents = documentParser.parse(file, fileName != null ? fileName : file.getOriginalFilename());
            if (documents.isEmpty()) {
                return "文件解析失败：未提取到文本内容";
            }
            log.info("文件解析完成，文档数={}", documents.size());

            List<Document> chunks = textSplitter.split(documents);
            log.info("文本切分完成，chunk 数={}", chunks.size());

            embeddingService.embed(chunks);
            log.info("向量化完成");

            for (Document chunk : chunks) {
                chunk.getMetadata().put("user_id", userId);
                chunk.getMetadata().put("title", fileName != null ? fileName : file.getOriginalFilename());
            }
            pgVectorStore.add(chunks);
            log.info("向量存储完成，已写入 PgVector");

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
            long esDeleted = esGateway.deleteByUserId(userId);

            log.warn("PgVector 删除功能需要根据实际实现调整，当前仅删除 ES 文档");

            log.info("删除用户知识库完成，userId={}, ES 删除数={}", userId, esDeleted);
            return (int) esDeleted;
        } catch (Exception e) {
            log.error("删除用户知识库失败，userId={}", userId, e);
            return 0;
        }
    }

}
