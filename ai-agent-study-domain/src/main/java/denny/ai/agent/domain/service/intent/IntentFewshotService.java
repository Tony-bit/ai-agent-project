package denny.ai.agent.domain.service.intent;

import denny.ai.agent.domain.adapter.repository.IIntentFewshotSampleRepository;
import denny.ai.agent.domain.model.entity.IntentFewshotSample;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.routing.IntentRoutingProperties;
import denny.ai.agent.domain.service.auto.step.routing.RoutingStructuredOutputValidator;
import denny.ai.agent.domain.service.auto.step.routing.UnifiedRoutingOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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

    @Resource
    private RoutingStructuredOutputValidator structuredOutputValidator;

    @Resource
    private IntentRoutingProperties intentRoutingProperties;

    /**
     * 从 PGvector 检索 Top-K 相似样本
     *
     * @param query 用户查询
     * @param k     检索数量
     * @return 相似样本列表
     */
    public List<IntentFewshotSample> retrieveTopK(String query) {
        return retrieveTopK(query, intentRoutingProperties.getFewshot().getTopK());
    }

    public List<IntentFewshotSample> retrieveTopK(String query, int k) {
        if (!StringUtils.hasText(query)) {
            log.warn("Few-Shot 检索 query 为空，降级为空列表");
            return List.of();
        }
        try {
            double similarityThreshold = intentRoutingProperties.getFewshot().getSimilarityThreshold();
            logQuery(query, k, similarityThreshold);
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(k)
                    .similarityThreshold(similarityThreshold)
                    .build();
            List<Document> docs = intentFewshotVectorStore.similaritySearch(request);
            List<IntentFewshotSample> samples = docs.stream()
                    .map(this::documentToSample)
                    .filter(this::isValidPromptSample)
                    .limit(k)
                    .collect(Collectors.toList());
            logResults(docs.size(), samples);
            return samples;
        } catch (Exception e) {
            log.warn("Few-Shot PGvector 检索异常，降级为空列表: queryLength={}, error={}",
                    query.length(), e.getMessage());
            return List.of();
        }
    }

    private void logQuery(String query, int topK, double similarityThreshold) {
        IntentRoutingProperties.Debug debug = intentRoutingProperties.getDebug();
        if (log.isDebugEnabled() && debug.isEnabled() && debug.isIncludeQuery()) {
            log.debug("Intent Few-Shot retrieval: query={}, topK={}, similarityThreshold={}",
                    debug.truncate(query), topK, similarityThreshold);
        }
    }

    private void logResults(int rawCount, List<IntentFewshotSample> samples) {
        IntentRoutingProperties.Debug debug = intentRoutingProperties.getDebug();
        if (!log.isDebugEnabled() || !debug.isEnabled() || !debug.isIncludeResults()) {
            return;
        }
        List<String> summaries = samples.stream()
                .map(sample -> "id=" + sample.getId()
                        + ", intent=" + sample.getIntentCode()
                        + ", queryText=" + debug.truncate(sample.getQueryText()))
                .toList();
        log.debug("Intent Few-Shot results: rawCount={}, validCount={}, hits={}",
                rawCount, samples.size(), summaries);
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
        requireValidPromptSample(sample);
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
        deleteFromVectorStore(id);
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
        requireValidPromptSample(sample);
        intentFewshotSampleRepository.update(sample);
        deleteFromVectorStore(id);
        syncToVectorStore(sample);
        log.info("Few-Shot 样本更新完成: id={}", id);
    }

    public void migrateSample(Long id, String intentCode, String exampleJson, boolean enabled) {
        IntentFewshotSample sample = intentFewshotSampleRepository.queryById(id);
        if (sample == null) {
            throw new IllegalArgumentException("Few-Shot sample does not exist: id=" + id);
        }
        sample.setIntentCode(intentCode);
        sample.setExampleJson(exampleJson);
        sample.setStatus(enabled ? IntentFewshotSample.STATUS_ENABLED : IntentFewshotSample.STATUS_DISABLED);
        if (enabled) {
            requireValidPromptSample(sample);
        }
        intentFewshotSampleRepository.update(sample);
        deleteFromVectorStore(id);
        if (enabled) {
            syncToVectorStore(sample);
        }
        log.info("Few-Shot 样本迁移完成: id={}, intentCode={}, enabled={}", id, intentCode, enabled);
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

    private boolean syncToVectorStore(IntentFewshotSample sample) {
        try {
            Document doc = sampleToDocument(sample);
            List<Document> docs = new ArrayList<>();
            docs.add(doc);
            // PgVectorStore 会在 accept 时自动为 doc 生成 embedding 并存储
            intentFewshotVectorStore.accept(docs);
            log.debug("样本同步到 PGvector 完成: id={}", sample.getId());
            return true;
        } catch (Exception e) {
            log.warn("样本同步到 PGvector 失败: id={}, error={}",
                    sample.getId(), e.getMessage());
            return false;
        }
    }

    private IntentFewshotSample documentToSample(Document doc) {
        String idStr = null;
        Object metadataId = doc.getMetadata().get("id");
        if (metadataId != null) {
            idStr = metadataId.toString();
        }
        if (!StringUtils.hasText(idStr)) {
            idStr = doc.getId();
        }
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
                .status(metadataStatus(doc))
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
        Map<String, Object> metadata = Map.of(
                "id", String.valueOf(sample.getId()),
                "intentCode", sample.getIntentCode(),
                "exampleJson", sample.getExampleJson(),
                "status", sample.getStatus());
        return new Document(vectorDocumentId(sample.getId()), sample.getQueryText(), metadata);
    }

    private boolean isValidPromptSample(IntentFewshotSample sample) {
        try {
            requireValidPromptSample(sample);
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("过滤非法 Few-Shot 样本: id={}, intentCode={}, error={}",
                    sample.getId(), sample.getIntentCode(), e.getMessage());
            return false;
        }
    }

    private void requireValidPromptSample(IntentFewshotSample sample) {
        if (sample == null || !Integer.valueOf(IntentFewshotSample.STATUS_ENABLED).equals(sample.getStatus())) {
            throw new IllegalArgumentException("sample must be enabled");
        }
        IntentTypeEnum metadataIntent = IntentTypeEnum.fromCode(sample.getIntentCode());
        if (metadataIntent == IntentTypeEnum.UNKNOWN) {
            throw new IllegalArgumentException("unknown intentCode: " + sample.getIntentCode());
        }
        try {
            UnifiedRoutingOutput output = structuredOutputValidator.validateAndParseUnified(sample.getExampleJson());
            if (Boolean.TRUE.equals(output.getNeedsClarification())) {
                if (metadataIntent != IntentTypeEnum.AMBIGUOUS
                        || output.getMissingInfo() == null
                        || output.getMissingInfo().isEmpty()
                        || output.getTaskList() == null
                        || !output.getTaskList().isEmpty()) {
                    throw new IllegalArgumentException(
                            "clarification sample must use AMBIGUOUS, non-empty missingInfo, and empty taskList");
                }
                return;
            }
            if (metadataIntent == IntentTypeEnum.AMBIGUOUS || output.getTaskList() == null
                    || output.getTaskList().isEmpty()
                    || output.getTaskList().stream()
                    .anyMatch(task -> !sample.getIntentCode().equals(task.getIntent()))) {
                throw new IllegalArgumentException("intentCode does not match taskList intents");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid exampleJson: " + e.getMessage(), e);
        }
    }

    private Integer metadataStatus(Document doc) {
        Object status = doc.getMetadata().get("status");
        if (status instanceof Number number) {
            return number.intValue();
        }
        if (status != null) {
            try {
                return Integer.parseInt(status.toString());
            } catch (NumberFormatException ignored) {
                return IntentFewshotSample.STATUS_DISABLED;
            }
        }
        return IntentFewshotSample.STATUS_ENABLED;
    }

    private void deleteFromVectorStore(Long id) {
        if (id == null) {
            return;
        }
        try {
            intentFewshotVectorStore.delete(List.of(vectorDocumentId(id)));
        } catch (Exception e) {
            log.warn("删除 Few-Shot 向量文档失败: id={}, error={}", id, e.getMessage());
        }
    }

    private String vectorDocumentId(Long id) {
        return UUID.nameUUIDFromBytes(("intent-fewshot:" + id).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
