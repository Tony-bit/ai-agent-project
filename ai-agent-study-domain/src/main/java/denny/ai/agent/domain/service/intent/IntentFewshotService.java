package denny.ai.agent.domain.service.intent;

import denny.ai.agent.domain.adapter.repository.IIntentFewshotSampleRepository;
import denny.ai.agent.domain.model.entity.IntentFewshotSample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 意图识别 Few-Shot 管理服务
 * <p>
 * 职责：样本 CRUD + PGvector Top-K 检索
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
@Slf4j
@Service
public class IntentFewshotService {

    @Resource
    private PgVectorStore intentFewshotVectorStore;

    @Resource
    private EmbeddingModel embeddingModel;

    @Resource
    private ChatClient chatClient;

    @Resource
    private IIntentFewshotSampleRepository intentFewshotSampleRepository;

    @Value("${intent.routing.fewshot.top-k:5}")
    private int defaultTopK = 5;

    /**
     * 从 PGvector 检索 Top-K 相似样本
     *
     * @param query 用户查询
     * @param k     检索数量
     * @return 相似样本列表
     */
    public List<IntentFewshotSample> retrieveTopK(String query, int k) {
        if (!StringUtils.hasText(query)) {
            log.warn("Few-Shot 检索 query 为空，降级为空列表");
            return List.of();
        }
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(k)
                    .build();
            List<Document> docs = intentFewshotVectorStore.similaritySearch(request);
            return docs.stream()
                    .map(this::documentToSample)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Few-Shot PGvector 检索异常，降级为空列表: query={}, error={}",
                    query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 新增样本
     * <p>
     * 注意：embedding 由 PgVectorStore 自动生成并存储
     * </p>
     *
     * @param queryText   用户 query 原文
     * @param intentCode 意图编码
     * @param exampleJson LLM 应返回的完整 JSON 示例
     */
    public void addSample(String queryText, String intentCode, String exampleJson) {
        IntentFewshotSample sample = IntentFewshotSample.builder()
                .queryText(queryText)
                .intentCode(intentCode)
                .exampleJson(exampleJson)
                .status(IntentFewshotSample.STATUS_ENABLED)
                .build();
        // 保存到 MySQL（仅存储文本信息）
        intentFewshotSampleRepository.save(sample);
        // 同步到 PGvector（PgVectorStore 自动生成 embedding）
        syncToVectorStore(sample);
        log.info("Few-Shot 样本新增完成: id={}, intentCode={}", sample.getId(), intentCode);
    }

    /**
     * 软删除样本
     *
     * @param id 样本 ID
     */
    public void deleteSample(Long id) {
        intentFewshotSampleRepository.delete(id);
        log.info("Few-Shot 样本软删除完成: id={}", id);
    }

    /**
     * 更新样本
     *
     * @param id          样本 ID
     * @param exampleJson 新的 JSON 示例
     */
    public void updateSample(Long id, String exampleJson) {
        IntentFewshotSample sample = intentFewshotSampleRepository.queryById(id);
        if (sample == null) {
            log.warn("Few-Shot 样本更新失败，样本不存在: id={}", id);
            return;
        }
        sample.setExampleJson(exampleJson);
        intentFewshotSampleRepository.update(sample);
        syncToVectorStore(sample);
        log.info("Few-Shot 样本更新完成: id={}", id);
    }

    /**
     * 根据意图编码查询样本
     *
     * @param intentCode 意图编码
     * @return 样本列表
     */
    public List<IntentFewshotSample> queryByIntentCode(String intentCode) {
        return intentFewshotSampleRepository.queryByIntentCode(intentCode);
    }

    private void syncToVectorStore(IntentFewshotSample sample) {
        try {
            Document doc = sampleToDocument(sample);
            List<Document> docs = new ArrayList<>();
            docs.add(doc);
            // PgVectorStore 会在 accept 时自动为 doc 生成 embedding 并存储
            intentFewshotVectorStore.accept(docs);
            log.debug("样本同步到 PGvector 完成: id={}", sample.getId());
        } catch (Exception e) {
            log.warn("样本同步到 PGvector 失败: id={}, error={}",
                    sample.getId(), e.getMessage());
        }
    }

    private IntentFewshotSample documentToSample(Document doc) {
        String idStr = doc.getId();
        Long id = null;
        if (StringUtils.hasText(idStr)) {
            try {
                id = Long.parseLong(idStr);
            } catch (NumberFormatException ignored) {
            }
        }
        IntentFewshotSample sample = IntentFewshotSample.builder()
                .id(id)
                .queryText(doc.getText())
                .status(IntentFewshotSample.STATUS_ENABLED)
                .build();
        doc.getMetadata().forEach((k, v) -> {
            if ("intentCode".equals(k)) {
                sample.setIntentCode(v.toString());
            } else if ("exampleJson".equals(k)) {
                sample.setExampleJson(v.toString());
            }
        });
        return sample;
    }

    private Document sampleToDocument(IntentFewshotSample sample) {
        Document doc = new Document(sample.getQueryText());
        doc.getMetadata().put("id", String.valueOf(sample.getId()));
        doc.getMetadata().put("intentCode", sample.getIntentCode());
        doc.getMetadata().put("exampleJson", sample.getExampleJson());
        return doc;
    }
}
