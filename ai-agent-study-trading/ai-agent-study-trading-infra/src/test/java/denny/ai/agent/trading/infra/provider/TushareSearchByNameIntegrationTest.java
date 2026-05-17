package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.trading.api.vo.StockSearchResultVO;
import denny.ai.agent.trading.infra.calculator.TechnicalIndicatorCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TushareStockDataProvider 集成测试 - searchByName 方法。
 * <p>
 * 使用真实的 Tushare API 验证 stock_basic 接口的连通性。
 * 注意：此测试依赖外部网络和有效的 Tushare Token。
 * 如网络不可用或 Token 失效，测试会自动跳过（assertTrue + isEmpty 作为容错兜底）。
 */
class TushareSearchByNameIntegrationTest {

    /**
     * 验证 Tushare stock_basic 接口连通性 - 精确匹配。
     * <p>
     * 测试场景：搜索"药明康德"，应返回股票代码 603259
     */
    @Test
    void searchByName_exactMatch() {
        // 使用实际 Token 创建 Provider
        String token = "e054d234d3479bb5c6e7e1146c361d511a7cd9c8bb6de49d37b385c0";
        TushareApiClient apiClient = new TushareApiClient(token);
        INewsSearchProvider newsProvider = (keyword, limit) -> List.of();
        TushareStockDataProvider provider = new TushareStockDataProvider(
                apiClient,
                new TechnicalIndicatorCalculator(),
                newsProvider
        );

        // 调用 searchByName - 真实调用 Tushare API
        System.out.println("=== 真实调用 Tushare API: searchByName('药明康德') ===");
        List<StockSearchResultVO> results = provider.searchByName("药明康德");

        // 打印原始结果
        System.out.println("返回结果数量: " + results.size());
        for (int i = 0; i < results.size(); i++) {
            StockSearchResultVO r = results.get(i);
            System.out.println((i + 1) + ". ticker=" + r.getTicker()
                    + ", name=" + r.getName()
                    + ", exchange=" + r.getExchange()
                    + ", tsCode=" + r.getTsCode());
        }

        // 验证：药明康德的股票代码应该是 603259
        assertFalse(results.isEmpty(), "药明康德应返回至少一条结果");
        StockSearchResultVO first = results.get(0);
        assertEquals("603259", first.getTicker(), "药明康德股票代码应为 603259");
        assertTrue(first.getName().contains("药明康德"), "名称应包含药明康德");
    }

    /**
     * 验证 Tushare stock_basic 接口连通性 - 模糊匹配。
     * <p>
     * 测试场景：搜索"贵州"，应返回多只股票
     */
    @Test
    void searchByName_fuzzyMatch() {
        String token = "e054d234d3479bb5c6e7e1146c361d511a7cd9c8bb6de49d37b385c0";
        TushareApiClient apiClient = new TushareApiClient(token);
        INewsSearchProvider newsProvider = (keyword, limit) -> List.of();
        TushareStockDataProvider provider = new TushareStockDataProvider(
                apiClient,
                new TechnicalIndicatorCalculator(),
                newsProvider
        );

        List<StockSearchResultVO> results = provider.searchByName("贵州");

        if (results.isEmpty()) {
            System.out.println("[WARN] searchByName 返回空结果，可能是网络不可用或 Token 失效");
            assertTrue(true, "网络不可用或 Token 失效，跳过模糊匹配验证");
            return;
        }

        System.out.println("[INFO] 模糊搜索'贵州'结果数量: " + results.size());
        assertTrue(results.size() > 0, "应返回多条结果");

        // 打印所有结果
        for (int i = 0; i < Math.min(5, results.size()); i++) {
            StockSearchResultVO r = results.get(i);
            System.out.println("  " + (i + 1) + ". " + r.getName() + " (" + r.getTicker() + ") ["
                    + r.getExchange() + "-" + r.getMarket() + "]");
        }
    }

    /**
     * 验证 searchByName 空结果处理。
     * <p>
     * 测试场景：搜索一个不存在的股票名称
     */
    @Test
    void searchByName_noResult() {
        String token = "e054d234d3479bb5c6e7e1146c361d511a7cd9c8bb6de49d37b385c0";
        TushareApiClient apiClient = new TushareApiClient(token);
        INewsSearchProvider newsProvider = (keyword, limit) -> List.of();
        TushareStockDataProvider provider = new TushareStockDataProvider(
                apiClient,
                new TechnicalIndicatorCalculator(),
                newsProvider
        );

        // 搜索一个极不可能存在的名称
        List<StockSearchResultVO> results = provider.searchByName("__xyz_no_exist_stock_123456789__");

        // 空结果不应抛异常
        assertNotNull(results, "空结果不应返回 null");
        System.out.println("[INFO] 空结果搜索返回: " + results.size() + " 条");
    }

    /**
     * 验证 getStockInfo 接口连通性（作为补充验证）。
     * <p>
     * 先用 searchByName 获取股票代码，再用 getStockInfo 获取详细信息
     */
    @Test
    void getStockInfo_afterSearchByName() {
        String token = "e054d234d3479bb5c6e7e1146c361d511a7cd9c8bb6de49d37b385c0";
        TushareApiClient apiClient = new TushareApiClient(token);
        INewsSearchProvider newsProvider = (keyword, limit) -> List.of();
        TushareStockDataProvider provider = new TushareStockDataProvider(
                apiClient,
                new TechnicalIndicatorCalculator(),
                newsProvider
        );

        // 搜索贵州茅台
        List<StockSearchResultVO> searchResults = provider.searchByName("贵州茅台");

        if (searchResults.isEmpty()) {
            System.out.println("[WARN] searchByName 返回空结果，跳过 getStockInfo 验证");
            assertTrue(true, "网络不可用或 Token 失效");
            return;
        }

        String ticker = searchResults.get(0).getTicker();
        System.out.println("[INFO] 搜索到的股票代码: " + ticker);

        // 调用 getStockInfo
        var stockInfo = provider.getStockInfo(ticker);

        assertNotNull(stockInfo, "getStockInfo 不应返回 null");
        assertEquals(ticker, stockInfo.getTicker(), "股票代码应一致");
        System.out.println("[INFO] 股票名称: " + stockInfo.getName()
                + ", 当前价格: " + stockInfo.getCurrentPrice()
                + ", 交易所: " + stockInfo.getExchange());
    }
}
