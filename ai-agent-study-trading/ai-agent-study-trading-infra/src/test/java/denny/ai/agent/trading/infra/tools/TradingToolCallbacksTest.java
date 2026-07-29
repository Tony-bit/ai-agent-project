package denny.ai.agent.trading.infra.tools;

import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.context.TradingTargetContextKeys;
import denny.ai.agent.trading.api.metrics.TradingRolloutMonitor;
import denny.ai.agent.trading.api.vo.OHLCVBarVO;
import denny.ai.agent.trading.api.vo.StockSearchResultVO;
import denny.ai.agent.trading.api.vo.TargetContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ToolContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.util.List;
import java.time.LocalDate;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TradingToolCallbacks 单元测试。
 * 验证 ToolCallback 是否正确实现，以及 .tools() 方法是否能正确接收数组。
 */
class TradingToolCallbacksTest {

    private IStockDataProvider mockProvider;
    private TradingToolCallbacks tradingToolCallbacks;
    private TradingRolloutMonitor rolloutMonitor;

    @BeforeEach
    void setUp() {
        mockProvider = mock(IStockDataProvider.class);
        rolloutMonitor = new TradingRolloutMonitor();
        tradingToolCallbacks = new TradingToolCallbacks(mockProvider, rolloutMonitor);
    }

    @Test
    void testGetStockInfoCallback() {
        ToolCallback callback = tradingToolCallbacks.getStockInfoCallback();

        assertNotNull(callback);
        assertEquals("get_stock_info", callback.getToolDefinition().name());
        assertNotNull(callback.getToolDefinition().description());
        assertNotNull(callback.getToolDefinition().inputSchema());
        assertFalse(callback.getToolDefinition().inputSchema().contains("ticker"));

        System.out.println("=== get_stock_info ===");
        System.out.println("Name: " + callback.getToolDefinition().name());
        System.out.println("Description: " + callback.getToolDefinition().description());
        System.out.println("InputSchema: " + callback.getToolDefinition().inputSchema());
    }

    @Test
    void testSearchStockByNameCallback() {
        ToolCallback callback = tradingToolCallbacks.searchStockByNameCallback();

        assertNotNull(callback);
        assertEquals("search_stock_by_name", callback.getToolDefinition().name());
        assertNotNull(callback.getToolDefinition().description());
        assertNotNull(callback.getToolDefinition().inputSchema());

        System.out.println("=== search_stock_by_name ===");
        System.out.println("Name: " + callback.getToolDefinition().name());
        System.out.println("Description: " + callback.getToolDefinition().description());
        System.out.println("InputSchema: " + callback.getToolDefinition().inputSchema());
    }

    @Test
    void testAllCallbacksAreToolCallbackInstances() {
        ToolCallback[] callbacks = new ToolCallback[] {
            tradingToolCallbacks.getStockInfoCallback(),
            tradingToolCallbacks.getHistoricalBarsCallback(),
            tradingToolCallbacks.getTechnicalIndicatorsCallback(),
            tradingToolCallbacks.getFundamentalDataCallback(),
            tradingToolCallbacks.getSentimentCallback(),
            tradingToolCallbacks.getStockNewsCallback(),
            tradingToolCallbacks.searchStockByNameCallback()
        };

        for (ToolCallback cb : callbacks) {
            assertNotNull(cb, "Callback should not be null");
            assertTrue(cb instanceof ToolCallback, "Should be instance of ToolCallback: " + cb.getClass().getName());
            System.out.println("Valid callback: " + cb.getToolDefinition().name() + " (" + cb.getClass().getName() + ")");
        }

        assertEquals(7, callbacks.length, "Should have 7 callbacks");
    }

    @Test
    void testToolCallbackArrayCreation() {
        // 验证可以创建 ToolCallback 数组
        ToolCallback[] callbacks = {
            tradingToolCallbacks.getStockInfoCallback(),
            tradingToolCallbacks.searchStockByNameCallback()
        };

        assertEquals(2, callbacks.length);

        // 验证类型正确
        for (ToolCallback cb : callbacks) {
            assertTrue(cb instanceof ToolCallback);
            assertNotNull(cb.getToolDefinition());
        }

        System.out.println("=== Array creation test passed ===");
    }

    @Test
    void testSearchStockByNameExecution() {
        // 设置 mock 返回值
        List<StockSearchResultVO> searchResults = List.of(
            StockSearchResultVO.builder().ticker("603259").name("药明康德").exchange("SSE").market("主板").build()
        );
        when(mockProvider.searchByName("药明康德")).thenReturn(searchResults);

        ToolCallback callback = tradingToolCallbacks.searchStockByNameCallback();

        String input = "{\"name\":\"药明康德\"}";
        String result = callback.call(input);

        System.out.println("=== search_stock_by_name execution result ===");
        System.out.println(result);

        assertNotNull(result);
        assertFalse(result.contains("工具执行失败"), "Should not contain error message");
        assertTrue(result.contains("药明康德") || result.contains("603259"), "Should contain stock info");

        verify(mockProvider).searchByName("药明康德");
    }

    @Test
    void testToolCallbacksCanBePassedToMethods() {
        // 模拟 Spring 容器获取的 ToolCallback 数组
        ToolCallback[] callbacks = new ToolCallback[] {
            tradingToolCallbacks.getStockInfoCallback(),
            tradingToolCallbacks.getHistoricalBarsCallback(),
            tradingToolCallbacks.getTechnicalIndicatorsCallback(),
            tradingToolCallbacks.getFundamentalDataCallback(),
            tradingToolCallbacks.getSentimentCallback(),
            tradingToolCallbacks.getStockNewsCallback(),
            tradingToolCallbacks.searchStockByNameCallback()
        };

        // 验证数组可以传递给方法（模拟 .tools(ToolCallback[]) 的场景）
        assertDoesNotThrow(() -> {
            validateToolCallbacks(callbacks);
        });

        System.out.println("=== ToolCallbacks can be passed to methods: PASSED ===");
    }

    @Test
    void testHistoricalBarsExecution_includesAmountAndPctChg() {
        List<OHLCVBarVO> bars = List.of(
                OHLCVBarVO.builder()
                        .date("2024-01-01")
                        .open(new BigDecimal("10.0"))
                        .high(new BigDecimal("10.8"))
                        .low(new BigDecimal("9.8"))
                        .close(new BigDecimal("10.5"))
                        .volume(1000000L)
                        .amount(new BigDecimal("10500000.50"))
                        .change(new BigDecimal("0.50"))
                        .pctChg(5.00)
                        .adjustedClose(new BigDecimal("10.5"))
                        .build()
        );
        when(mockProvider.getHistoricalBars("600000.SH", "2024-01-01", "2024-01-05")).thenReturn(bars);

        ToolCallback callback = tradingToolCallbacks.getHistoricalBarsCallback();
        String input = "{\"ticker\":\"001309\",\"startDate\":\"2024-01-01\",\"endDate\":\"2024-01-05\"}";
        String result = callback.call(input, toolContext(target()));

        assertNotNull(result);
        assertFalse(result.contains("工具执行失败"));
        assertTrue(result.contains("成交额"));
        assertTrue(result.contains("涨跌额"));
        assertTrue(result.contains("涨跌幅"));
        assertTrue(result.contains("10500000.50"));
        assertTrue(result.contains("5.00%"));

        verify(mockProvider).getHistoricalBars("600000.SH", "2024-01-01", "2024-01-05");
    }

    @Test
    void targetBoundToolRejectsSingleArgumentCallsWithoutToolContext() {
        ToolCallback callback = tradingToolCallbacks.getStockInfoCallback();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> callback.call("{}"));

        assertTrue(error.getMessage().startsWith("IDENTITY_BOUNDARY_VIOLATION"));
        verifyNoInteractions(mockProvider);
    }

    @Test
    void targetBoundToolUsesToolContextAndOverridesModelTicker() {
        ToolCallback callback = tradingToolCallbacks.getStockInfoCallback();
        TargetContext target = target();

        String result = callback.call("{\"ticker\":\"000001.SZ\"}", toolContext(target));

        assertEquals("未找到该股票信息", result);
        verify(mockProvider).getStockInfo(target.targetId());
        assertEquals(1, rolloutMonitor.snapshot().toolTargetOverrides());
    }

    @Test
    void allTargetBoundToolsUseAuthoritativeTarget() {
        TargetContext target = target();
        ToolContext toolContext = toolContext(target);

        tradingToolCallbacks.getStockInfoCallback().call("{}", toolContext);
        tradingToolCallbacks.getHistoricalBarsCallback().call(
                "{\"startDate\":\"2024-01-01\",\"endDate\":\"2024-01-05\"}", toolContext);
        tradingToolCallbacks.getTechnicalIndicatorsCallback().call(
                "{\"startDate\":\"2024-01-01\",\"endDate\":\"2024-01-05\"}", toolContext);
        tradingToolCallbacks.getFundamentalDataCallback().call("{}", toolContext);
        tradingToolCallbacks.getSentimentCallback().call("{}", toolContext);
        tradingToolCallbacks.getStockNewsCallback().call("{\"limit\":3}", toolContext);

        verify(mockProvider).getStockInfo(target.targetId());
        verify(mockProvider).getHistoricalBars(target.targetId(), "2024-01-01", "2024-01-05");
        verify(mockProvider).getTechnicalIndicators(target.targetId(), "2024-01-01", "2024-01-05");
        verify(mockProvider).getFundamentalData(target.targetId());
        verify(mockProvider).getSentiment(target.targetId());
        verify(mockProvider).getNews(target.targetId(), 3);
    }

    @Test
    void targetBoundToolRejectsNullAndInvalidToolContext() {
        ToolCallback callback = tradingToolCallbacks.getStockInfoCallback();
        ToolContext invalid = new ToolContext(Map.of(
                TradingTargetContextKeys.TARGET_CONTEXT, "600000.SH"));

        IllegalStateException nullError = assertThrows(IllegalStateException.class,
                () -> callback.call("{}", null));
        IllegalStateException invalidError = assertThrows(IllegalStateException.class,
                () -> callback.call("{}", invalid));

        assertTrue(nullError.getMessage().startsWith("IDENTITY_BOUNDARY_VIOLATION"));
        assertTrue(invalidError.getMessage().startsWith("IDENTITY_BOUNDARY_VIOLATION"));
        assertEquals(2, rolloutMonitor.snapshot().identityBoundaryViolations());
        verifyNoInteractions(mockProvider);
    }

    @Test
    void searchToolRejectsTargetContextAndWorksWithEmptyContext() {
        List<StockSearchResultVO> results = List.of(
                StockSearchResultVO.builder().ticker("603259").name("药明康德").build());
        when(mockProvider.searchByName("药明康德")).thenReturn(results);
        ToolCallback callback = tradingToolCallbacks.searchStockByNameCallback();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> callback.call("{\"name\":\"药明康德\"}", toolContext(target())));
        String result = callback.call("{\"name\":\"药明康德\"}", new ToolContext(Map.of()));

        assertTrue(error.getMessage().startsWith("IDENTITY_BOUNDARY_VIOLATION"));
        assertTrue(result.contains("药明康德"));
        verify(mockProvider).searchByName("药明康德");
    }

    @Test
    void targetBoundToolWorksOnBoundedElastic() {
        TargetContext target = target();

        String result = Mono.fromCallable(() -> tradingToolCallbacks.getStockInfoCallback()
                        .call("{}", toolContext(target)))
                .subscribeOn(Schedulers.boundedElastic())
                .block();

        assertEquals("未找到该股票信息", result);
        verify(mockProvider).getStockInfo(target.targetId());
    }

    @Test
    void concurrentToolContextsDoNotCrossTargets() {
        TargetContext first = target("600000.SH");
        TargetContext second = target("000001.SZ");
        ToolCallback callback = tradingToolCallbacks.getStockInfoCallback();

        CompletableFuture<String> firstCall = CompletableFuture.supplyAsync(
                () -> callback.call("{}", toolContext(first)));
        CompletableFuture<String> secondCall = CompletableFuture.supplyAsync(
                () -> callback.call("{}", toolContext(second)));

        assertEquals("未找到该股票信息", firstCall.join());
        assertEquals("未找到该股票信息", secondCall.join());
        verify(mockProvider).getStockInfo(first.targetId());
        verify(mockProvider).getStockInfo(second.targetId());
    }

    @Test
    void providerFailureKeepsExistingErrorConversion() {
        TargetContext target = target();
        when(mockProvider.getStockInfo(target.targetId()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        String result = tradingToolCallbacks.getStockInfoCallback()
                .call("{}", toolContext(target));

        assertTrue(result.contains("工具执行失败: provider unavailable"));
    }

    private TargetContext target() {
        return target("600000.SH");
    }

    private TargetContext target(String targetId) {
        return new TargetContext(UUID.randomUUID().toString(), targetId,
                "浦发银行", "银行", LocalDate.of(2026, 7, 28));
    }

    private ToolContext toolContext(TargetContext target) {
        return new ToolContext(Map.of(TradingTargetContextKeys.TARGET_CONTEXT, target));
    }

    /**
     * 模拟 ChatClient.tools(ToolCallback[]) 的方法签名
     */
    private void validateToolCallbacks(ToolCallback[] callbacks) {
        for (ToolCallback cb : callbacks) {
            assertNotNull(cb.getToolDefinition());
            assertNotNull(cb.getToolDefinition().name());
        }
    }
}
