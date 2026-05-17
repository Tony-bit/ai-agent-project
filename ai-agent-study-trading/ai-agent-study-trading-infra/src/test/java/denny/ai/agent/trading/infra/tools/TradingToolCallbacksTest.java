package denny.ai.agent.trading.infra.tools;

import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.StockSearchResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TradingToolCallbacks 单元测试。
 * 验证 ToolCallback 是否正确实现，以及 .tools() 方法是否能正确接收数组。
 */
class TradingToolCallbacksTest {

    private IStockDataProvider mockProvider;
    private TradingToolCallbacks tradingToolCallbacks;

    @BeforeEach
    void setUp() {
        mockProvider = mock(IStockDataProvider.class);
        tradingToolCallbacks = new TradingToolCallbacks(mockProvider);
    }

    @Test
    void testGetStockInfoCallback() {
        ToolCallback callback = tradingToolCallbacks.getStockInfoCallback();

        assertNotNull(callback);
        assertEquals("get_stock_info", callback.getToolDefinition().name());
        assertNotNull(callback.getToolDefinition().description());
        assertNotNull(callback.getToolDefinition().inputSchema());

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
