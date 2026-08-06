package denny.ai.agent.domain.service.stock;

import denny.ai.agent.domain.model.valobj.stock.StockNameIndex;
import denny.ai.agent.domain.model.valobj.stock.StockNameRecord;
import denny.ai.agent.domain.model.valobj.stock.StockNameResolutionResult;
import denny.ai.agent.domain.model.valobj.stock.StockNameResolutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockNameResolutionServiceTest {

    private final StockNameResolutionService service = new StockNameResolutionService(3);

    @Test
    void resolve_returnsResolvedForExactUniqueMatch() {
        StockNameResolutionResult result = service.resolve(indexOf(
                record("北方华创", "002371"),
                record("华创云信", "600155")
        ), "北方华创");

        assertEquals(StockNameResolutionStatus.RESOLVED, result.getStatus());
        assertEquals(record("北方华创", "002371"), result.getResolvedRecord());
        assertEquals(1, result.getTotalMatches());
    }

    @Test
    void resolve_returnsResolvedForUniqueMiddleSubstringMatch() {
        StockNameResolutionResult result = service.resolve(indexOf(
                record("东方甄选科技", "300999"),
                record("北方华创", "002371")
        ), "甄选");

        assertEquals(StockNameResolutionStatus.RESOLVED, result.getStatus());
        assertEquals(record("东方甄选科技", "300999"), result.getResolvedRecord());
    }

    @Test
    void resolve_returnsAmbiguousForMultipleMatchesWithinLimit() {
        StockNameResolutionResult result = service.resolve(indexOf(
                record("北方华创", "002371"),
                record("华创云信", "600155")
        ), "华创");

        assertEquals(StockNameResolutionStatus.AMBIGUOUS, result.getStatus());
        assertEquals(2, result.getTotalMatches());
        assertEquals(2, result.getCandidates().size());
    }

    @Test
    void resolve_returnsTooManyCandidatesWhenMatchesExceedLimit() {
        StockNameResolutionResult result = service.resolve(indexOf(
                record("华创一号", "000001"),
                record("华创二号", "000002"),
                record("华创三号", "000003"),
                record("华创四号", "000004")
        ), "华创");

        assertEquals(StockNameResolutionStatus.TOO_MANY_CANDIDATES, result.getStatus());
        assertEquals(4, result.getTotalMatches());
        assertEquals(3, result.getCandidates().size());
    }

    @Test
    void resolve_returnsNotFoundWhenNoCandidateExists() {
        StockNameResolutionResult result = service.resolve(indexOf(
                record("北方华创", "002371"),
                record("华创云信", "600155")
        ), "不存在");

        assertEquals(StockNameResolutionStatus.NOT_FOUND, result.getStatus());
        assertEquals(0, result.getTotalMatches());
    }

    @Test
    void resolve_prefersExactMatchOverFuzzyScan() {
        StockNameResolutionResult result = service.resolve(indexOf(
                record("华创", "000001"),
                record("华创云信", "600155"),
                record("北方华创", "002371")
        ), " 华 创 ");

        assertEquals(StockNameResolutionStatus.RESOLVED, result.getStatus());
        assertEquals(record("华创", "000001"), result.getResolvedRecord());
        assertEquals(1, result.getTotalMatches());
    }

    @Test
    void resolve_handlesAboutSixThousandEntriesWithinTargetLatency() {
        List<StockNameRecord> records = new ArrayList<>();
        for (int i = 0; i < 6000; i++) {
            String code = String.format("%06d", i);
            records.add(record("样例股票" + i, code));
        }
        records.add(record("北方华创", "002371"));
        records.add(record("华创云信", "600155"));

        StockNameIndex index = StockNameIndex.of(records);
        List<String> queries = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            queries.add("样例股票" + (i % 6000));
            queries.add("华创");
        }

        for (int i = 0; i < 1000; i++) {
            service.resolve(index, queries.get(i % queries.size()));
        }

        List<Long> elapsedNanos = new ArrayList<>(queries.size());
        for (String query : queries) {
            long start = System.nanoTime();
            service.resolve(index, query);
            elapsedNanos.add(System.nanoTime() - start);
        }

        elapsedNanos.sort(Comparator.naturalOrder());
        long p95Nanos = percentile(elapsedNanos, 0.95);
        long p99Nanos = percentile(elapsedNanos, 0.99);

        assertTrue(Duration.ofNanos(p95Nanos).toMillis() < 5, "P95 should be below 5 ms");
        assertTrue(Duration.ofNanos(p99Nanos).toMillis() < 10, "P99 should be below 10 ms");
    }

    private static long percentile(List<Long> values, double percentile) {
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private static StockNameIndex indexOf(StockNameRecord... records) {
        return StockNameIndex.of(List.of(records));
    }

    private static StockNameRecord record(String stockName, String stockCode) {
        return StockNameRecord.builder()
                .stockName(stockName)
                .stockCode(stockCode)
                .build();
    }
}
