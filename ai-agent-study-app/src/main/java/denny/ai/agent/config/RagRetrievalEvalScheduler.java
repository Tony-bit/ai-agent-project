package denny.ai.agent.config;

import com.alibaba.fastjson2.JSONObject;
import denny.ai.agent.domain.service.observability.ObservabilityService;
import denny.ai.agent.infrastructure.adapter.repository.RagKnowledgeRepository;
import denny.ai.agent.infrastructure.adapter.repository.model.RagRetrievedDoc;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagRetrievalEvalScheduler {

    private static final int TOP_K = 10;

    @Value("classpath:data/file2.text")
    private org.springframework.core.io.Resource evalCaseFileResource;

    private final RagKnowledgeRepository ragKnowledgeRepository;
    private final ObservabilityService observabilityService;

    @Scheduled(cron = "0 0 2 * * ?")
    public void runDailyEval() {
        runEval("scheduled-daily");
    }

    public void runManualEval() {
        runEval("manual");
    }

    private void runEval(String triggerType) {
        List<EvalCase> evalCases = loadEvalCases();
        if (evalCases.isEmpty()) {
            log.warn("RAG 离线评测无样本，跳过执行");
            return;
        }

        Map<String, Object> traceMeta = new HashMap<>();
        traceMeta.put("scene", "rag_retrieval_eval");
        traceMeta.put("triggerType", triggerType);
        traceMeta.put("sampleSize", evalCases.size());
        traceMeta.put("topK", TOP_K);
        String traceId = observabilityService.startTrace("rag-eval-session", "RAG retrieval evaluation", traceMeta);

        String batchSpanId = observabilityService.startSpan(traceId, "rag_retrieval_eval_batch", traceMeta);

        int hitAt5Count = 0;
        int hitAt10Count = 0;
        double rrAt10Sum = 0.0d;

        try {
            for (EvalCase evalCase : evalCases) {
                String caseSpanId = observabilityService.startSpan(traceId, "rag_retrieval_eval_case", Map.of(
                        "caseId", evalCase.getCaseId(),
                        "difficulty", evalCase.getDifficulty(),
                        "type", evalCase.getType()
                ));

                try {
                    List<RagRetrievedDoc> retrieved = ragKnowledgeRepository
                            .retrieveRankedDocsForEval(evalCase.getUserId(), evalCase.getQuery(), TOP_K);

                    EvalMetrics metrics = computeMetrics(retrieved, evalCase.getGoldDocIds(), 5, 10);
                    if (metrics.isHitAt5()) {
                        hitAt5Count++;
                    }
                    if (metrics.isHitAt10()) {
                        hitAt10Count++;
                    }
                    rrAt10Sum += metrics.getRrAt10();

                    Map<String, Object> caseMeta = new HashMap<>();
                    caseMeta.put("caseId", evalCase.getCaseId());
                    caseMeta.put("difficulty", evalCase.getDifficulty());
                    caseMeta.put("type", evalCase.getType());
                    caseMeta.put("goldDocIds", evalCase.getGoldDocIds());
                    caseMeta.put("retrievedTopDocIds", topDocIds(retrieved, 10));

                    observabilityService.logScore(traceId, "retrieval_hit_at_5", metrics.isHitAt5() ? 1.0d : 0.0d,
                            "RAG检索命中@5", caseMeta);
                    observabilityService.logScore(traceId, "retrieval_hit_at_10", metrics.isHitAt10() ? 1.0d : 0.0d,
                            "RAG检索命中@10", caseMeta);
                    observabilityService.logScore(traceId, "retrieval_rr_at_10", metrics.getRrAt10(),
                            "RAG检索RR@10", caseMeta);

                    observabilityService.endSpan(caseSpanId, true, null);
                } catch (Exception e) {
                    observabilityService.endSpan(caseSpanId, false, e.getMessage());
                    log.warn("RAG 评测 case 执行失败, caseId={}, err={}", evalCase.getCaseId(), e.getMessage(), e);
                }
            }

            int total = evalCases.size();
            double hitAt5 = total == 0 ? 0.0d : (double) hitAt5Count / total;
            double hitAt10 = total == 0 ? 0.0d : (double) hitAt10Count / total;
            double mrrAt10 = total == 0 ? 0.0d : rrAt10Sum / total;

            Map<String, Object> summaryMeta = new HashMap<>();
            summaryMeta.put("triggerType", triggerType);
            summaryMeta.put("sampleSize", total);
            summaryMeta.put("topK", TOP_K);
            summaryMeta.put("time", LocalDateTime.now().toString());

            observabilityService.logScore(traceId, "retrieval_hit_at_5_avg", hitAt5, "RAG检索平均命中@5", summaryMeta);
            observabilityService.logScore(traceId, "retrieval_hit_at_10_avg", hitAt10, "RAG检索平均命中@10", summaryMeta);
            observabilityService.logScore(traceId, "retrieval_mrr_at_10", mrrAt10, "RAG检索MRR@10", summaryMeta);

            String summary = String.format("RAG 离线评测完成, sampleSize=%d, hit@5=%.4f, hit@10=%.4f, mrr@10=%.4f",
                    total, hitAt5, hitAt10, mrrAt10);
            log.info("{}", summary);

            observabilityService.endSpan(batchSpanId, true, null);
            observabilityService.endTrace(traceId, summary, summaryMeta);
        } catch (Exception e) {
            observabilityService.endSpan(batchSpanId, false, e.getMessage());
            observabilityService.endTrace(traceId, "", Map.of("error", e.getMessage(), "triggerType", triggerType));
            throw e;
        }
    }

    private EvalMetrics computeMetrics(List<RagRetrievedDoc> retrieved,
                                       List<String> goldDocIds,
                                       int hitK,
                                       int mrrK) {
        Set<String> goldSet = new HashSet<>(goldDocIds);

        boolean hitAt5 = false;
        boolean hitAt10 = false;
        double rrAt10 = 0.0d;

        int limit = Math.min(retrieved.size(), mrrK);
        for (int i = 0; i < limit; i++) {
            String normalizedDocId = normalizeDocId(retrieved.get(i));
            boolean hit = goldSet.contains(normalizedDocId);

            if (hit && i < hitK) {
                hitAt5 = true;
            }
            if (hit) {
                hitAt10 = true;
                rrAt10 = 1.0d / (i + 1);
                break;
            }
        }

        return EvalMetrics.builder()
                .hitAt5(hitAt5)
                .hitAt10(hitAt10)
                .rrAt10(rrAt10)
                .build();
    }

    private List<EvalCase> loadEvalCases() {
        List<EvalCase> cases = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(evalCaseFileResource.getFile().toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String row = line == null ? "" : line.trim();
                if (row.isEmpty()) {
                    continue;
                }
                JSONObject obj = JSONObject.parseObject(row);
                if (obj == null) {
                    continue;
                }

                List<String> goldDocIds = obj.getList("golden_doc_ids", String.class);
                if (goldDocIds == null) {
                    goldDocIds = new ArrayList<>();
                }

                cases.add(EvalCase.builder()
                        .caseId(obj.getString("id"))
                        .userId("eval-expense-v1")
                        .query(obj.getString("question"))
                        .goldDocIds(goldDocIds)
                        .type(obj.getString("type"))
                        .difficulty(obj.getString("difficulty"))
                        .build());
            }
        } catch (Exception e) {
            log.error("加载评测样本失败: {}", e.getMessage(), e);
        }
        return cases;
    }

    private String normalizeDocId(RagRetrievedDoc doc) {
        String title = doc == null ? "" : doc.getTitle();
        if (StringUtils.isBlank(title)) {
            return "";
        }
        int index = title.indexOf('-');
        if (index <= 0) {
            return title;
        }
        return title.substring(0, index);
    }

    private List<String> topDocIds(List<RagRetrievedDoc> retrieved, int size) {
        List<String> ids = new ArrayList<>();
        int limit = Math.min(retrieved.size(), size);
        for (int i = 0; i < limit; i++) {
            ids.add(normalizeDocId(retrieved.get(i)));
        }
        return ids;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class EvalCase {
        private String caseId;
        private String userId;
        private String query;
        private List<String> goldDocIds;
        private String type;
        private String difficulty;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class EvalMetrics {
        private boolean hitAt5;
        private boolean hitAt10;
        private double rrAt10;
    }
}
