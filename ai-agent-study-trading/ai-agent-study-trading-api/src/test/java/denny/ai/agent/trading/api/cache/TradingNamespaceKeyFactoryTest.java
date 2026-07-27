package denny.ai.agent.trading.api.cache;

import denny.ai.agent.trading.api.vo.TargetContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TradingNamespaceKeyFactoryTest {

    @Test
    void chatMemoryKeyIsIsolatedBySessionRunTargetAndNode() {
        TargetContext first = target("601318.SH", "中国平安");
        TargetContext secondRun = target("601318.SH", "中国平安");
        TargetContext otherTarget = target("001309.SZ", "德明利");

        String key = TradingNamespaceKeyFactory.chatMemory(
                "session-1", first, "BullResearcherNode");

        assertEquals("trading:session-1:" + first.runId()
                + ":601318.SH:BullResearcherNode", key);
        assertNotEquals(key, TradingNamespaceKeyFactory.chatMemory(
                "session-1", secondRun, "BullResearcherNode"));
        assertNotEquals(key, TradingNamespaceKeyFactory.chatMemory(
                "session-1", otherTarget, "BullResearcherNode"));
        assertNotEquals(key, TradingNamespaceKeyFactory.chatMemory(
                "session-1", first, "BearResearcherNode"));
    }

    @Test
    void rawDataKeyNormalizesParamsAndNeverContainsRunId() {
        Map<String, Object> firstOrder = new LinkedHashMap<>();
        firstOrder.put("limit", 10);
        firstOrder.put("adjust", "qfq");
        Map<String, Object> secondOrder = new LinkedHashMap<>();
        secondOrder.put("adjust", "qfq");
        secondOrder.put("limit", 10);

        String first = TradingNamespaceKeyFactory.rawData(
                "tushare", "daily", "601318.SH", "20260701-20260722", firstOrder, "v1");
        String second = TradingNamespaceKeyFactory.rawData(
                "tushare", "daily", "601318.SH", "20260701-20260722", secondOrder, "v1");

        assertEquals(first, second);
        assertEquals("tushare:daily:601318.SH:20260701-20260722:adjust=qfq&limit=10:v1", first);
        assertFalse(first.contains(UUID.randomUUID().toString()));
        assertNotEquals(first, TradingNamespaceKeyFactory.rawData(
                "tushare", "daily", "001309.SZ", "20260701-20260722", firstOrder, "v1"));
    }

    private TargetContext target(String targetId, String name) {
        return new TargetContext(UUID.randomUUID().toString(), targetId, name,
                null, LocalDate.of(2026, 7, 22));
    }
}
