package denny.ai.agent.infrastructure.adapter.repository;

import denny.ai.agent.domain.model.valobj.stock.StockAnalysisMode;
import denny.ai.agent.domain.model.valobj.stock.StockNameRecord;
import denny.ai.agent.domain.model.valobj.stock.StockResolutionPending;
import denny.ai.agent.domain.model.valobj.stock.StockResolutionPendingStatus;
import denny.ai.agent.domain.model.valobj.stock.StockTargetStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStockResolutionPendingRepositoryTest {

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisStockResolutionPendingRepository repository;

    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        repository = new RedisStockResolutionPendingRepository(stringRedisTemplate);
    }

    @Test
    void createOrReplace_setsValueWithDefaultTtl() {
        repository.createOrReplace("session-1", pending("v1"));

        verify(valueOperations).set(
                eq("trading:stock-resolution:session-1"),
                any(String.class),
                eq(600000L),
                eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void findBySessionId_deserializesPendingDocument() {
        when(valueOperations.get("trading:stock-resolution:session-1")).thenReturn("""
                {"version":"v1","status":"PENDING","originalQuery":"分析华创","stockNameQuery":"华创",
                "targetStatus":"AMBIGUOUS","orderedCandidates":[{"stockName":"北方华创","stockCode":"002371"}],
                "analysisMode":"UNRESOLVED","createdAt":"2026-08-04T00:00:00Z","expiresAt":"2026-08-04T00:10:00Z"}
                """);

        Optional<StockResolutionPending> result = repository.findBySessionId("session-1");

        assertTrue(result.isPresent());
        assertEquals("v1", result.orElseThrow().getVersion());
        assertEquals(StockResolutionPendingStatus.PENDING, result.orElseThrow().getStatus());
        assertEquals(1, result.orElseThrow().getOrderedCandidates().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void compareAndSet_returnsTrueWhenLuaReportsSuccess() {
        when(stringRedisTemplate.execute(
                any(),
                anyList(),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class))).thenReturn(1L);

        boolean updated = repository.compareAndSet("session-1", "v1", pending("v2"), true);

        assertTrue(updated);
    }

    @Test
    @SuppressWarnings("unchecked")
    void claim_returnsClaimedPendingWhenLuaReturnsJson() {
        when(stringRedisTemplate.execute(
                any(),
                anyList(),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class))).thenReturn("""
                {"version":"v1","status":"CLAIMED","claimId":"claim-1","claimExpiresAt":"2026-08-04T00:01:00Z",
                "claimExpiresAtEpochMillis":1785801660000,"originalQuery":"分析华创","stockNameQuery":"华创",
                "targetStatus":"RESOLVED","orderedCandidates":[],"resolvedStockName":"北方华创",
                "resolvedStockCode":"002371","analysisMode":"FULL","createdAt":"2026-08-04T00:00:00Z",
                "expiresAt":"2026-08-04T00:10:00Z"}
                """);

        Optional<StockResolutionPending> claimed = repository.claim(
                "session-1",
                "v1",
                "claim-1",
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:01:00Z"));

        assertTrue(claimed.isPresent());
        assertEquals(StockResolutionPendingStatus.CLAIMED, claimed.orElseThrow().getStatus());
        assertEquals("claim-1", claimed.orElseThrow().getClaimId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseClaim_returnsFalseWhenLuaRejectsVersionOrClaimMismatch() {
        when(stringRedisTemplate.execute(
                any(),
                anyList(),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class))).thenReturn(0L);

        boolean released = repository.releaseClaim("session-1", "v1", "claim-1", pending("v2"));

        assertFalse(released);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteClaimed_returnsTrueWhenLuaDeletesMatchingPending() {
        when(stringRedisTemplate.execute(
                any(),
                anyList(),
                any(String.class),
                any(String.class))).thenReturn(1L);

        boolean deleted = repository.deleteClaimed("session-1", "v2", "claim-2");

        assertTrue(deleted);
    }

    private static StockResolutionPending pending(String version) {
        return StockResolutionPending.builder()
                .version(version)
                .status(StockResolutionPendingStatus.PENDING)
                .originalQuery("分析华创")
                .stockNameQuery("华创")
                .targetStatus(StockTargetStatus.AMBIGUOUS)
                .orderedCandidates(List.of(
                        StockNameRecord.builder().stockName("北方华创").stockCode("002371").build()))
                .analysisMode(StockAnalysisMode.UNRESOLVED)
                .createdAt(Instant.parse("2026-08-04T00:00:00Z"))
                .expiresAt(Instant.parse("2026-08-04T00:10:00Z"))
                .build();
    }
}
