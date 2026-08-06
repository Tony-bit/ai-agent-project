package denny.ai.agent.domain.service.stock;

import denny.ai.agent.domain.model.valobj.stock.StockNameIndex;
import denny.ai.agent.domain.model.valobj.stock.StockNameRecord;
import denny.ai.agent.domain.model.valobj.stock.StockNameResolutionResult;
import denny.ai.agent.domain.model.valobj.stock.StockNameResolutionStatus;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Resolves stock name queries against an in-memory stock name index.
 */
public class StockNameResolutionService {

    private final int maxCandidates;
    private final StockNameMetrics stockNameMetrics;

    public StockNameResolutionService(int maxCandidates) {
        this(maxCandidates, new StockNameMetrics());
    }

    public StockNameResolutionService(int maxCandidates, StockNameMetrics stockNameMetrics) {
        if (maxCandidates <= 0) {
            throw new IllegalArgumentException("maxCandidates must be greater than 0");
        }
        this.maxCandidates = maxCandidates;
        this.stockNameMetrics = Objects.requireNonNull(stockNameMetrics, "stockNameMetrics");
    }

    public StockNameResolutionResult resolve(StockNameIndex index, String stockNameQuery) {
        Objects.requireNonNull(index, "index");
        long startedAt = System.nanoTime();

        List<StockNameRecord> exactMatches = index.findExact(stockNameQuery);
        if (!exactMatches.isEmpty()) {
            StockNameResolutionResult result = classify(stockNameQuery, exactMatches, exactMatches.size());
            recordMetrics(result, startedAt);
            return result;
        }

        StockNameIndex.SearchResult fuzzyMatches = index.findFuzzy(stockNameQuery, maxCandidates);
        StockNameResolutionResult result = classify(stockNameQuery, fuzzyMatches.candidates(), fuzzyMatches.totalMatches());
        recordMetrics(result, startedAt);
        return result;
    }

    private void recordMetrics(StockNameResolutionResult result, long startedAt) {
        stockNameMetrics.recordResolution(
                result.getStatus().name(),
                result.getCandidates() == null ? 0 : result.getCandidates().size(),
                Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private StockNameResolutionResult classify(String stockNameQuery,
                                               List<StockNameRecord> matches,
                                               int totalMatches) {
        if (totalMatches <= 0) {
            return StockNameResolutionResult.builder()
                    .status(StockNameResolutionStatus.NOT_FOUND)
                    .stockNameQuery(stockNameQuery)
                    .totalMatches(0)
                    .message("股票不存在，请检查股票名称或输入六位代码。")
                    .build();
        }

        if (totalMatches == 1) {
            StockNameRecord resolved = matches.get(0);
            return StockNameResolutionResult.builder()
                    .status(StockNameResolutionStatus.RESOLVED)
                    .stockNameQuery(stockNameQuery)
                    .resolvedRecord(resolved)
                    .candidates(List.of(resolved))
                    .totalMatches(1)
                    .build();
        }

        if (totalMatches <= maxCandidates) {
            return StockNameResolutionResult.builder()
                    .status(StockNameResolutionStatus.AMBIGUOUS)
                    .stockNameQuery(stockNameQuery)
                    .candidates(List.copyOf(matches))
                    .totalMatches(totalMatches)
                    .message("找到多个候选股票，请选择更准确的名称或代码。")
                    .build();
        }

        return StockNameResolutionResult.builder()
                .status(StockNameResolutionStatus.TOO_MANY_CANDIDATES)
                .stockNameQuery(stockNameQuery)
                .candidates(List.copyOf(matches))
                .totalMatches(totalMatches)
                .message("候选股票过多，请输入更完整的名称。")
                .build();
    }
}
