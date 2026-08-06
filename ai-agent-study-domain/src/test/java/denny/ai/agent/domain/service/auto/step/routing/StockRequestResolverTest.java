package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.valobj.StockSlot;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.model.valobj.stock.StockAnalysisMode;
import denny.ai.agent.domain.model.valobj.stock.StockNameIndex;
import denny.ai.agent.domain.model.valobj.stock.StockNameRecord;
import denny.ai.agent.domain.model.valobj.stock.StockRequestRouteDecisionType;
import denny.ai.agent.domain.model.valobj.stock.StockRequestRoutingDecision;
import denny.ai.agent.domain.model.valobj.stock.StockResolutionPending;
import denny.ai.agent.domain.model.valobj.stock.StockResolutionPendingStatus;
import denny.ai.agent.domain.model.valobj.stock.StockTargetStatus;
import denny.ai.agent.domain.service.stock.StockNameIndexHolder;
import denny.ai.agent.domain.service.stock.StockNameResolutionService;
import denny.ai.agent.domain.service.stock.StockResolutionPendingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockRequestResolverTest {

    private final Instant now = Instant.parse("2026-08-04T08:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private StockNameIndexHolder indexHolder;
    private InMemoryPendingRepository pendingRepository;
    private StockRequestResolver resolver;

    @BeforeEach
    void setUp() {
        indexHolder = new StockNameIndexHolder(clock);
        pendingRepository = new InMemoryPendingRepository();
        resolver = new StockRequestResolver(
                indexHolder,
                new StockNameResolutionService(10),
                pendingRepository,
                new AnalysisDepthFollowUpResolver(),
                clock);
    }

    @Test
    void shouldCreateAmbiguousPendingAndClarifyTargetForMultiMatch() {
        publishIndex(
                record("北方华创", "002371"),
                record("华创云信", "600155"));

        StockRequestRoutingDecision decision = resolver.resolve(
                "s-1",
                "分析华创",
                IntentTypeEnum.STOCK_ANALYSIS,
                StockSlot.builder().stockNameQuery("华创").analysisMode("UNRESOLVED").build());

        assertEquals(StockRequestRouteDecisionType.CLARIFY_TARGET, decision.getDecisionType());
        assertEquals(StockTargetStatus.AMBIGUOUS, decision.getStockTargetStatus());
        assertEquals(StockAnalysisMode.UNRESOLVED, decision.getAnalysisMode());
        assertTrue(decision.getClarificationPrompt().contains("1. 北方华创（002371）"));
        StockResolutionPending pending = pendingRepository.findBySessionId("s-1").orElseThrow();
        assertEquals("分析华创", pending.getOriginalQuery());
        assertEquals(2, pending.getOrderedCandidates().size());
    }

    @Test
    void shouldResolveCandidateIndexThenClarifyAnalysisMode() {
        StockResolutionPending pending = ambiguousPending("分析华创", StockAnalysisMode.UNRESOLVED);
        pendingRepository.createOrReplace("s-1", pending);

        StockRequestRoutingDecision decision = resolver.resolve(
                "s-1",
                "1",
                IntentTypeEnum.GENERAL_CHAT,
                new StockSlot());

        assertEquals(StockRequestRouteDecisionType.CLARIFY_ANALYSIS_MODE, decision.getDecisionType());
        assertEquals("002371", decision.getStockSlot().getStockCode());
        assertEquals("北方华创", decision.getStockSlot().getStockName());
        StockResolutionPending updated = pendingRepository.findBySessionId("s-1").orElseThrow();
        assertEquals(StockTargetStatus.RESOLVED, updated.getTargetStatus());
        assertEquals("北方华创", updated.getResolvedStockName());
    }

    @Test
    void shouldRouteQuickAfterFullCandidateNameSelectionWhenModeAlreadyQuick() {
        StockResolutionPending pending = ambiguousPending("查华创昨天收盘价", StockAnalysisMode.QUICK);
        pendingRepository.createOrReplace("s-1", pending);

        StockRequestRoutingDecision decision = resolver.resolve(
                "s-1",
                "北方华创",
                IntentTypeEnum.GENERAL_CHAT,
                new StockSlot());

        assertEquals(StockRequestRouteDecisionType.ROUTE_GENERAL_CHAT, decision.getDecisionType());
        assertEquals("002371", decision.getStockSlot().getStockCode());
        assertTrue(decision.getExecutionQuery().contains("查华创昨天收盘价"));
        assertTrue(decision.getExecutionQuery().contains("北方华创（002371）"));
        assertNotNull(decision.getClaimId());
        assertNotNull(decision.getPendingVersion());
        assertEquals(StockResolutionPendingStatus.CLAIMED,
                pendingRepository.findBySessionId("s-1").orElseThrow().getStatus());
    }

    @Test
    void shouldRouteTradingAfterCandidateCodeSelectionWhenModeAlreadyFull() {
        StockResolutionPending pending = ambiguousPending("完整分析华创", StockAnalysisMode.FULL);
        pendingRepository.createOrReplace("s-1", pending);

        StockRequestRoutingDecision decision = resolver.resolve(
                "s-1",
                "002371",
                IntentTypeEnum.GENERAL_CHAT,
                StockSlot.builder().stockCode("002371").build());

        assertEquals(StockRequestRouteDecisionType.ROUTE_TRADING, decision.getDecisionType());
        assertEquals("北方华创", decision.getStockSlot().getStockName());
        assertEquals("002371", decision.getStockSlot().getStockCode());
        assertNull(decision.getExecutionQuery());
        assertNotNull(decision.getClaimId());
    }

    @Test
    void shouldReplacePendingWhenUserSwitchesToAnotherStockName() {
        publishIndex(record("贵州茅台", "600519"));
        StockResolutionPending pending = ambiguousPending("查华创昨天收盘价", StockAnalysisMode.FULL);
        pendingRepository.createOrReplace("s-1", pending);

        StockRequestRoutingDecision decision = resolver.resolve(
                "s-1",
                "贵州茅台",
                IntentTypeEnum.STOCK_ANALYSIS,
                StockSlot.builder().stockNameQuery("贵州茅台").build());

        assertEquals(StockRequestRouteDecisionType.ROUTE_TRADING, decision.getDecisionType());
        assertEquals("贵州茅台", decision.getStockSlot().getStockName());
        assertEquals("600519", decision.getStockSlot().getStockCode());
    }

    @Test
    void shouldReturnNotFoundWithoutCreatingPending() {
        publishIndex(record("北方华创", "002371"));

        StockRequestRoutingDecision decision = resolver.resolve(
                "s-1",
                "分析不存在的股票",
                IntentTypeEnum.STOCK_ANALYSIS,
                StockSlot.builder().stockNameQuery("不存在").build());

        assertEquals(StockRequestRouteDecisionType.NOT_FOUND, decision.getDecisionType());
        assertTrue(decision.getClarificationPrompt().contains("股票不存在"));
        assertTrue(pendingRepository.findBySessionId("s-1").isEmpty());
    }

    @Test
    void shouldCreateUnresolvedPendingWhenStockTargetIsMissingButModeIsFull() {
        StockRequestRoutingDecision decision = resolver.resolve(
                "s-1",
                "完整分析一只股票",
                IntentTypeEnum.STOCK_ANALYSIS,
                StockSlot.builder().analysisMode("FULL").build());

        assertEquals(StockRequestRouteDecisionType.CLARIFY_TARGET, decision.getDecisionType());
        assertEquals(StockAnalysisMode.FULL, decision.getAnalysisMode());
        StockResolutionPending pending = pendingRepository.findBySessionId("s-1").orElseThrow();
        assertEquals(StockTargetStatus.UNRESOLVED, pending.getTargetStatus());
        assertEquals(StockAnalysisMode.FULL, pending.getAnalysisMode());
    }

    @Test
    void shouldRouteQuickAfterPendingResolvesAndUserChoosesQuickMode() {
        StockResolutionPending pending = StockResolutionPending.builder()
                .version("v-1")
                .status(StockResolutionPendingStatus.PENDING)
                .originalQuery("我想查华创昨天的收盘价")
                .stockNameQuery("北方华创")
                .targetStatus(StockTargetStatus.RESOLVED)
                .resolvedStockName("北方华创")
                .resolvedStockCode("002371")
                .analysisMode(StockAnalysisMode.UNRESOLVED)
                .createdAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();
        pendingRepository.createOrReplace("s-1", pending);

        StockRequestRoutingDecision decision = resolver.resolve(
                "s-1",
                "快速了解",
                IntentTypeEnum.GENERAL_CHAT,
                new StockSlot());

        assertEquals(StockRequestRouteDecisionType.ROUTE_GENERAL_CHAT, decision.getDecisionType());
        assertEquals("QUICK", decision.getStockSlot().getAnalysisMode());
        assertTrue(decision.getExecutionQuery().contains("我想查华创昨天的收盘价"));
        assertTrue(decision.getExecutionQuery().contains("北方华创（002371）"));
        assertEquals(StockResolutionPendingStatus.CLAIMED,
                pendingRepository.findBySessionId("s-1").orElseThrow().getStatus());
    }

    @Test
    void shouldKeepClarifyingWhenPendingReplyIsInvalidButStillStockIntent() {
        StockResolutionPending pending = ambiguousPending("分析华创", StockAnalysisMode.UNRESOLVED);
        pendingRepository.createOrReplace("s-1", pending);

        StockRequestRoutingDecision decision = resolver.resolve(
                "s-1",
                "随便看看",
                IntentTypeEnum.STOCK_ANALYSIS,
                StockSlot.builder().analysisMode("UNRESOLVED").build());

        assertEquals(StockRequestRouteDecisionType.CLARIFY_TARGET, decision.getDecisionType());
        assertTrue(decision.getClarificationPrompt().contains("请回复序号"));
        assertEquals(pending.getVersion(), pendingRepository.findBySessionId("s-1").orElseThrow().getVersion());
    }

    private void publishIndex(StockNameRecord... records) {
        indexHolder.publish(StockNameIndex.of(List.of(records), now, now.plusSeconds(3600)));
    }

    private StockNameRecord record(String name, String code) {
        return StockNameRecord.builder().stockName(name).stockCode(code).build();
    }

    private StockResolutionPending ambiguousPending(String originalQuery, StockAnalysisMode analysisMode) {
        return StockResolutionPending.builder()
                .version("v-1")
                .status(StockResolutionPendingStatus.PENDING)
                .originalQuery(originalQuery)
                .stockNameQuery("华创")
                .targetStatus(StockTargetStatus.AMBIGUOUS)
                .orderedCandidates(List.of(
                        record("北方华创", "002371"),
                        record("华创云信", "600155")))
                .analysisMode(analysisMode)
                .createdAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();
    }

    private static final class InMemoryPendingRepository implements StockResolutionPendingRepository {
        private StockResolutionPending pending;

        @Override
        public Optional<StockResolutionPending> findBySessionId(String sessionId) {
            return Optional.ofNullable(pending);
        }

        @Override
        public void createOrReplace(String sessionId, StockResolutionPending pending) {
            this.pending = pending;
        }

        @Override
        public void delete(String sessionId) {
            this.pending = null;
        }

        @Override
        public boolean compareAndSet(String sessionId, String expectedVersion, StockResolutionPending newPending, boolean refreshTtl) {
            if (pending == null || !Objects.equals(expectedVersion, pending.getVersion())) {
                return false;
            }
            pending = newPending;
            return true;
        }

        @Override
        public Optional<StockResolutionPending> claim(String sessionId, String expectedVersion, String claimId, Instant now, Instant claimExpiresAt) {
            if (pending == null || !Objects.equals(expectedVersion, pending.getVersion())
                    || pending.getStatus() == StockResolutionPendingStatus.CLAIMED) {
                return Optional.empty();
            }
            pending = StockResolutionPending.builder()
                    .version(pending.getVersion())
                    .status(StockResolutionPendingStatus.CLAIMED)
                    .claimId(claimId)
                    .claimExpiresAt(claimExpiresAt)
                    .originalQuery(pending.getOriginalQuery())
                    .stockNameQuery(pending.getStockNameQuery())
                    .targetStatus(pending.getTargetStatus())
                    .orderedCandidates(pending.getOrderedCandidates())
                    .resolvedStockName(pending.getResolvedStockName())
                    .resolvedStockCode(pending.getResolvedStockCode())
                    .analysisMode(pending.getAnalysisMode())
                    .createdAt(pending.getCreatedAt())
                    .expiresAt(pending.getExpiresAt())
                    .build();
            return Optional.of(pending);
        }

        @Override
        public boolean releaseClaim(String sessionId, String expectedVersion, String claimId, StockResolutionPending pendingToRestore) {
            pending = pendingToRestore;
            return true;
        }

        @Override
        public boolean deleteClaimed(String sessionId, String expectedVersion, String claimId) {
            pending = null;
            return true;
        }
    }
}
