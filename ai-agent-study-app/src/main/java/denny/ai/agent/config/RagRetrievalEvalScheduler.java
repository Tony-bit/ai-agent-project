package denny.ai.agent.config;

import denny.ai.agent.infrastructure.adapter.repository.RagKnowledgeRepository;
import denny.ai.agent.infrastructure.adapter.repository.model.RagRetrievedDoc;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagRetrievalEvalScheduler {

    private static final int TOP_K = 10;

    private final RagKnowledgeRepository ragKnowledgeRepository;

    @Scheduled(cron = "0 0 2 * * ?")
    public void runDailyEval() {
        List<EvalCase> evalCases = loadEvalCases();
        if (evalCases.isEmpty()) {
            log.warn("RAG 离线评测无样本，跳过执行");
            return;
        }

        int hitAt5Count = 0;
        int hitAt10Count = 0;
        double rrAt10Sum = 0.0d;

        for (EvalCase evalCase : evalCases) {
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
        }

        int total = evalCases.size();
        double hitAt5 = total == 0 ? 0.0d : (double) hitAt5Count / total;
        double hitAt10 = total == 0 ? 0.0d : (double) hitAt10Count / total;
        double mrrAt10 = total == 0 ? 0.0d : rrAt10Sum / total;

        log.info("RAG 离线评测完成, time={}, sampleSize={}, hit@5={}, hit@10={}, mrr@10={}",
                LocalDateTime.now(), total,
                String.format("%.4f", hitAt5),
                String.format("%.4f", hitAt10),
                String.format("%.4f", mrrAt10));
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
            String docId = retrieved.get(i).getDocId();
            boolean hit = goldSet.contains(docId);

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

        cases.add(EvalCase.builder()
                .userId("eval-user")
                .query("什么是文本排序模型？")
                .goldDocIds(Arrays.asList(
                        "文本排序模型广泛用于搜索引擎和推荐系统中，它们根据文本相关性对候选文本进行排序",
                        "预训练语言模型的发展给文本排序模型带来了新的进展"
                ))
                .build());

        return cases;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class EvalCase {
        private String userId;
        private String query;
        private List<String> goldDocIds;
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
