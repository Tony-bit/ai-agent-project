package denny.ai.agent.domain.model.valobj;

import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.valobj.stock.StockAnalysisMode;
import denny.ai.agent.domain.model.valobj.stock.StockNameIndexStatus;
import denny.ai.agent.domain.model.valobj.stock.StockNameRecord;
import denny.ai.agent.domain.model.valobj.stock.StockNameResolutionResult;
import denny.ai.agent.domain.model.valobj.stock.StockNameResolutionStatus;
import denny.ai.agent.domain.model.valobj.stock.StockRequestRouteDecisionType;
import denny.ai.agent.domain.model.valobj.stock.StockRequestRoutingDecision;
import denny.ai.agent.domain.model.valobj.stock.StockResolutionPending;
import denny.ai.agent.domain.model.valobj.stock.StockResolutionPendingStatus;
import denny.ai.agent.domain.model.valobj.stock.StockTargetStatus;
import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class StockSlotCompatibilityTest {

    @Test
    public void shouldDeserializeLegacyExchangeAndNewStockFieldsTogether() {
        StockSlot stockSlot = JSON.parseObject("""
                {
                  "stockNameQuery":"华创",
                  "stockCode":"002371",
                  "stockName":"北方华创",
                  "analysisMode":"FULL",
                  "stockQueryType":"TECHNICAL",
                  "timeRange":"近三个月",
                  "exchange":"SZ"
                }
                """, StockSlot.class);

        assertEquals("华创", stockSlot.getStockNameQuery());
        assertEquals("002371", stockSlot.getStockCode());
        assertEquals("北方华创", stockSlot.getStockName());
        assertEquals("FULL", stockSlot.getAnalysisMode());
        assertEquals("SZ", stockSlot.getExchange());
    }

    @Test
    public void shouldAllowCodeOnlyStockSlot() {
        StockSlot stockSlot = StockSlot.builder()
                .stockCode("600519")
                .analysisMode(StockAnalysisMode.QUICK.name())
                .build();

        assertEquals("600519", stockSlot.getStockCode());
        assertNull(stockSlot.getStockNameQuery());
        assertNull(stockSlot.getStockName());
        assertEquals("QUICK", stockSlot.getAnalysisMode());
    }

    @Test
    public void shouldBuildTaskOneStockContracts() {
        StockNameRecord record = StockNameRecord.builder()
                .stockName("北方华创")
                .stockCode("002371")
                .build();
        StockNameResolutionResult result = StockNameResolutionResult.builder()
                .status(StockNameResolutionStatus.AMBIGUOUS)
                .stockNameQuery("华创")
                .candidates(List.of(record))
                .totalMatches(1)
                .message("候选存在")
                .build();
        StockResolutionPending pending = StockResolutionPending.builder()
                .version("v1")
                .status(StockResolutionPendingStatus.PENDING)
                .originalQuery("分析华创")
                .stockNameQuery("华创")
                .targetStatus(StockTargetStatus.AMBIGUOUS)
                .orderedCandidates(List.of(record))
                .analysisMode(StockAnalysisMode.UNRESOLVED)
                .createdAt(Instant.parse("2026-08-04T00:00:00Z"))
                .expiresAt(Instant.parse("2026-08-04T00:10:00Z"))
                .build();
        StockRequestRoutingDecision decision = StockRequestRoutingDecision.builder()
                .decisionType(StockRequestRouteDecisionType.CLARIFY_TARGET)
                .stockTargetStatus(StockTargetStatus.AMBIGUOUS)
                .analysisMode(StockAnalysisMode.UNRESOLVED)
                .stockSlot(StockSlot.builder().stockNameQuery("华创").build())
                .clarificationPrompt("请选择股票")
                .build();

        assertEquals(StockNameIndexStatus.NOT_READY, StockNameIndexStatus.valueOf("NOT_READY"));
        assertEquals("002371", result.getCandidates().get(0).getStockCode());
        assertEquals(StockTargetStatus.AMBIGUOUS, pending.getTargetStatus());
        assertEquals(StockRequestRouteDecisionType.CLARIFY_TARGET, decision.getDecisionType());
    }
}
