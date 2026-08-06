package denny.ai.agent.domain.service.stock;

import denny.ai.agent.domain.model.valobj.stock.StockAnalysisMode;
import denny.ai.agent.domain.model.valobj.stock.StockNameRecord;
import denny.ai.agent.domain.model.valobj.stock.StockResolutionPending;
import denny.ai.agent.domain.model.valobj.stock.StockResolutionPendingStatus;
import denny.ai.agent.domain.model.valobj.stock.StockTargetStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StockResolutionPendingRepositoryContractTest {

    @Test
    void shouldExposeFixedRedisContractDefaults() {
        assertEquals("trading:stock-resolution:", StockResolutionPendingRepository.KEY_PREFIX);
        assertEquals(Duration.ofMinutes(10), StockResolutionPendingRepository.DEFAULT_TTL);
        assertEquals(Duration.ofSeconds(60), StockResolutionPendingRepository.DEFAULT_CLAIM_TIMEOUT);
    }

    @Test
    void shouldModelSinglePendingForOneSession() {
        StockResolutionPending pending = StockResolutionPending.builder()
                .version("v2")
                .status(StockResolutionPendingStatus.PENDING)
                .originalQuery("分析华创")
                .stockNameQuery("华创")
                .targetStatus(StockTargetStatus.AMBIGUOUS)
                .orderedCandidates(List.of(
                        StockNameRecord.builder().stockName("北方华创").stockCode("002371").build(),
                        StockNameRecord.builder().stockName("华创云信").stockCode("600155").build()))
                .analysisMode(StockAnalysisMode.UNRESOLVED)
                .createdAt(Instant.parse("2026-08-04T00:00:00Z"))
                .expiresAt(Instant.parse("2026-08-04T00:10:00Z"))
                .build();

        assertEquals("v2", pending.getVersion());
        assertEquals(StockResolutionPendingStatus.PENDING, pending.getStatus());
        assertEquals(StockTargetStatus.AMBIGUOUS, pending.getTargetStatus());
        assertEquals(StockAnalysisMode.UNRESOLVED, pending.getAnalysisMode());
        assertEquals(2, pending.getOrderedCandidates().size());
        assertEquals(Instant.parse("2026-08-04T00:10:00Z"), pending.getExpiresAt());
    }
}
