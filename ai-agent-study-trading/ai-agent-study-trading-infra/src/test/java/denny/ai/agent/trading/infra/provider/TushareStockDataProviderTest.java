package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.infra.calculator.TechnicalIndicatorCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TushareStockDataProvider 单元测试。
 *
 * 注意：Mockito mock/spy 对 TushareApiClient 无法正常工作（Spring Boot / Logback 类加载问题
 * 导致 JDK Proxy/CGlib 拦截失败），因此所有测试改用匿名内部类覆盖 call() 方法来提供可控数据。
 */
class TushareStockDataProviderTest {

    private TechnicalIndicatorCalculator indicatorCalculator;
    private INewsSearchProvider mockNewsSearchProvider;

    @BeforeEach
    void setUp() {
        indicatorCalculator = new TechnicalIndicatorCalculator();
        mockNewsSearchProvider = (keyword, limit) -> Collections.emptyList();
    }

    @FunctionalInterface
    interface TushareCallHandler {
        List<Map<String, String>> handle(String apiName, Map<String, Object> params, String fields);
    }

    /**
     * 创建测试用 TushareApiClient。
     * @param handler 自定义 call 方法行为。
     *                handler 返回 null 表示让真实实现执行（用于模拟网络错误等场景）。
     */
    private TushareApiClient createTestClient(TushareCallHandler handler) {
        return new TestableTushareApiClient("fake_token", handler);
    }

    /**
     * 可测试的 TushareApiClient 匿名子类。
     * 用命名内部类而非匿名类，以便在覆盖方法中通过 super 调用父类实现。
     */
    private static class TestableTushareApiClient extends TushareApiClient {
        private final TushareCallHandler handler;

        TestableTushareApiClient(String token, TushareCallHandler handler) {
            super(token);
            this.handler = handler;
        }

        @Override
        public List<Map<String, String>> call(String apiName, Map<String, Object> params, String fields) {
            List<Map<String, String>> result = handler.handle(apiName, params, fields);
            if (result != null) {
                return result;
            }
            return super.call(apiName, params, fields);
        }

        @Override
        public <T> List<T> callGeneric(Class<T> dtoClass, String apiName,
                                       Map<String, Object> params, String fields) {
            List<Map<String, String>> result = handler.handle(apiName, params, fields);
            if (result == null) {
                return super.callGeneric(dtoClass, apiName, params, fields);
            }
            return convertRows(dtoClass, result);
        }

        @Override
        public <T> List<T> callGenericStrict(Class<T> dtoClass, String apiName,
                                             Map<String, Object> params, String fields) {
            List<Map<String, String>> result = handler.handle(apiName, params, fields);
            if (result == null) {
                return super.callGenericStrict(dtoClass, apiName, params, fields);
            }
            return convertRows(dtoClass, result);
        }

        private <T> List<T> convertRows(Class<T> dtoClass, List<Map<String, String>> result) {
            List<T> converted = new ArrayList<>(result.size());
            for (Map<String, String> row : result) {
                try {
                    T dto = dtoClass.getDeclaredConstructor().newInstance();
                    var objectMapperField = TushareApiClient.class.getDeclaredField("objectMapper");
                    objectMapperField.setAccessible(true);
                    var objectMapper = (com.fasterxml.jackson.databind.ObjectMapper) objectMapperField.get(this);
                    converted.add(objectMapper.convertValue(row, dtoClass));
                } catch (Exception e) {
                    throw new RuntimeException("测试 DTO 转换失败", e);
                }
            }
            return converted;
        }
    }

    // ==================== getStockInfo 测试 ====================

    @Test
    void getStockInfo_success() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("stock_basic".equals(apiName)) {
                return List.of(Map.of(
                        "ts_code", "600000.SH",
                        "name", "浦发银行",
                        "exchange", "SSE"
                ));
            }
            if ("daily".equals(apiName)) {
                if (fields.contains("close")) {
                    return List.of(Map.of(
                            "ts_code", "600000.SH",
                            "trade_date", "20240101",
                            "close", "10.5",
                            "vol", "1000000"
                    ));
                } else {
                    return List.of(
                            Map.of("high", "12.0", "low", "8.5"),
                            Map.of("high", "11.5", "low", "9.0")
                    );
                }
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        StockInfoVO result = provider.getStockInfo("600000");

        assertNotNull(result);
        assertEquals("600000", result.getTicker());
        assertEquals("浦发银行", result.getName());
        assertEquals("SSE", result.getExchange());
        assertEquals(new BigDecimal("10.5"), result.getCurrentPrice());
        assertEquals(1000000L, result.getVolume());
        assertEquals(new BigDecimal("12.0"), result.getWeek52High());
        assertEquals(new BigDecimal("8.5"), result.getWeek52Low());
    }

    @Test
    void getStockInfoUsesDailyBasicFromPriceTradeDate() {
        TushareApiClient client = createTestClient((apiName, params, fields) -> {
            if ("stock_basic".equals(apiName)) {
                return List.of(Map.of(
                        "ts_code", "600285.SH",
                        "name", "羚锐制药",
                        "exchange", "SSE"));
            }
            if ("daily".equals(apiName) && fields.contains("close")) {
                return List.of(Map.of(
                        "ts_code", "600285.SH",
                        "trade_date", "20260807",
                        "close", "22.24",
                        "vol", "92306"));
            }
            if ("daily".equals(apiName)) {
                return List.of(Map.of("high", "24.75", "low", "19.00"));
            }
            if ("daily_basic".equals(apiName)) {
                assertEquals("20260807", params.get("trade_date"));
                return List.of(Map.of(
                        "ts_code", "600285.SH",
                        "trade_date", "20260807",
                        "pe", "16.8",
                        "pe_ttm", "16.6",
                        "pb", "3.2",
                        "total_mv", "1257000",
                        "circ_mv", "1249000"));
            }
            return Collections.emptyList();
        });

        StockInfoVO result = new TushareStockDataProvider(
                client, indicatorCalculator, mockNewsSearchProvider).getStockInfo("600285.SH");

        assertEquals(16.6, result.getPeTtm());
        assertEquals("2026-08-07", result.getValuationTradeDate());
        assertEquals(0, new BigDecimal("1257000").compareTo(result.getTotalMv()));
    }

    @Test
    void getStockInfo_tickerConversion_shanghai() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> Collections.emptyList());
        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        assertEquals("600000.SH", provider.toTsCode("600000"));
    }

    @Test
    void getStockInfo_tickerConversion_shenzhen() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> Collections.emptyList());
        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        assertEquals("000001.SZ", provider.toTsCode("000001"));
    }

    @Test
    void getStockInfo_tickerConversion_beijing() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> Collections.emptyList());
        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        assertEquals("430001.BJ", provider.toTsCode("430001"));
    }

    @Test
    void getStockInfo_invalidTicker() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> Collections.emptyList());
        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        assertThrows(IllegalArgumentException.class, () -> provider.toTsCode("NVDA"));
        assertThrows(IllegalArgumentException.class, () -> provider.toTsCode(null));
        assertThrows(IllegalArgumentException.class, () -> provider.toTsCode("12345"));
    }

    @Test
    void getStockInfo_error() {
        TushareApiClient errorClient = createTestClient((apiName, params, fields) -> {
            throw new RuntimeException("API Error");
        });
        TushareStockDataProvider provider = new TushareStockDataProvider(errorClient, indicatorCalculator, mockNewsSearchProvider);
        assertThrows(RuntimeException.class, () -> provider.getStockInfo("600000"));
    }

    // ==================== getHistoricalBars 测试 ====================

    @Test
    void getHistoricalBars_success() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("daily".equals(apiName)) {
                assertEquals("trade_date,open,high,low,close,vol,amount,change,pct_chg", fields);
                return List.of(
                        Map.of(
                                "trade_date", "20240101",
                                "open", "10.0",
                                "high", "10.8",
                                "low", "9.8",
                                "close", "10.5",
                                "vol", "1000000",
                                "amount", "10500000.50",
                                "change", "0.50",
                                "pct_chg", "5.00"
                        ),
                        Map.of(
                                "trade_date", "20240102",
                                "open", "10.5",
                                "high", "11.0",
                                "low", "10.2",
                                "close", "10.8",
                                "vol", "1200000",
                                "amount", "12960000.00",
                                "change", "0.30",
                                "pct_chg", "2.86"
                        )
                );
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        List<OHLCVBarVO> result = provider.getHistoricalBars("600000", "2024-01-01", "2024-01-05");

        assertNotNull(result);
        assertEquals(2, result.size());

        OHLCVBarVO bar1 = result.get(0);
        assertEquals("2024-01-01", bar1.getDate());
        assertEquals(new BigDecimal("10.0"), bar1.getOpen());
        assertEquals(new BigDecimal("10.5"), bar1.getClose());
        assertEquals(new BigDecimal("10.8"), bar1.getHigh());
        assertEquals(new BigDecimal("9.8"), bar1.getLow());
        assertEquals(new BigDecimal("10.5"), bar1.getAdjustedClose());
        assertEquals(1000000L, bar1.getVolume());
        assertEquals(new BigDecimal("10500000.50"), bar1.getAmount());
        assertEquals(new BigDecimal("0.50"), bar1.getChange());
        assertEquals(5.00, bar1.getPctChg());

        OHLCVBarVO bar2 = result.get(1);
        assertEquals("2024-01-02", bar2.getDate());
        assertEquals(new BigDecimal("10.5"), bar2.getOpen());
        assertEquals(new BigDecimal("11.0"), bar2.getHigh());
        assertEquals(new BigDecimal("10.2"), bar2.getLow());
        assertEquals(new BigDecimal("10.8"), bar2.getClose());
        assertEquals(1200000L, bar2.getVolume());
        assertEquals(new BigDecimal("12960000.00"), bar2.getAmount());
        assertEquals(new BigDecimal("0.30"), bar2.getChange());
        assertEquals(2.86, bar2.getPctChg());
    }

    @Test
    void getHistoricalBars_emptyData() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> Collections.emptyList());
        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        List<OHLCVBarVO> result = provider.getHistoricalBars("600000", "2024-01-01", "2024-01-05");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== getFundamentalData 测试 ====================

    @Test
    void findStockIdentities_preservesAuthoritativeRecordsForDomainValidation() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if (!"stock_basic".equals(apiName)) {
                return Collections.emptyList();
            }
            return List.of(
                    Map.of("ts_code", "601318.SH", "name", "中国平安", "industry", "保险"),
                    Map.of("ts_code", "601318.SH", "name", "中国平安", "industry", "保险"));
        });
        TushareStockDataProvider provider = new TushareStockDataProvider(
                testClient, indicatorCalculator, mockNewsSearchProvider);

        List<StockIdentityVO> identities = provider.findStockIdentities("601318");

        assertEquals(2, identities.size());
        assertEquals("601318.SH", identities.get(0).targetId());
        assertEquals("中国平安", identities.get(0).stockName());
        assertEquals("保险", identities.get(0).industry());
    }

    @Test
    void getFundamentalData_success() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("fina_indicator".equals(apiName)) {
                assertEquals("600285.SH", params.get("ts_code"));
                assertTrue(fields.contains("roa"));
                assertTrue(fields.contains("tr_yoy"));
                assertTrue(fields.contains("netprofit_yoy"));
                Map<String, String> row = new HashMap<>();
                row.put("ann_date", "20260428");
                row.put("end_date", "20260331");
                row.put("update_flag", "1");
                row.put("roe", "6.9887");
                row.put("roa", "4.815");
                row.put("grossprofit_margin", "78.1878");
                row.put("netprofit_margin", "21.9738");
                row.put("debt_to_assets", "38.2246");
                row.put("current_ratio", "1.1712");
                row.put("eps", "0.435");
                row.put("bps", "6.4341");
                row.put("tr_yoy", "10.3177");
                row.put("netprofit_yoy", "13.6133");
                return List.of(row);
            }
            if ("daily_basic".equals(apiName)) {
                assertTrue(fields.contains("ps_ttm"));
                assertTrue(fields.contains("dv_ratio"));
                return List.of(Map.of(
                        "trade_date", "20260807",
                        "pe", "16.6064",
                        "pe_ttm", "15.9852",
                        "pb", "3.4566",
                        "ps", "3.2731",
                        "ps_ttm", "3.1861",
                        "dv_ratio", "4.0468",
                        "total_mv", "1261264.872",
                        "circ_mv", "1259874.872"));
            }
            if ("income".equals(apiName)) {
                assertEquals("600285.SH", params.get("ts_code"));
                assertEquals("20260331", params.get("period"));
                assertEquals("ts_code,ann_date,end_date,update_flag,revenue,n_income_attr_p", fields);
                return List.of(Map.of(
                        "ann_date", "20260428",
                        "end_date", "20260331",
                        "update_flag", "1",
                        "revenue", "1126074496.05",
                        "n_income_attr_p", "246333622.73"));
            }
            if ("balancesheet".equals(apiName)) {
                assertEquals("600285.SH", params.get("ts_code"));
                assertEquals("20260331", params.get("period"));
                assertEquals("ts_code,ann_date,end_date,update_flag,total_assets,total_liab", fields);
                return List.of(Map.of(
                        "ann_date", "20260428",
                        "end_date", "20260331",
                        "update_flag", "1",
                        "total_assets", "5965684837.28",
                        "total_liab", "2280359405.02"));
            }
            if ("cashflow".equals(apiName)) {
                assertEquals("600285.SH", params.get("ts_code"));
                assertEquals("20260331", params.get("period"));
                assertEquals("ts_code,ann_date,end_date,update_flag,n_cashflow_act,c_pay_acq_const_fiolta", fields);
                return List.of(Map.of(
                        "ann_date", "20260428",
                        "end_date", "20260331",
                        "update_flag", "1",
                        "n_cashflow_act", "268450155.61",
                        "c_pay_acq_const_fiolta", "13131261.23"));
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        FundamentalDataVO result = provider.getFundamentalData("600285.SH");

        assertNotNull(result);
        assertAll(
                () -> assertEquals(6.9887, result.getRoe()),
                () -> assertEquals(4.815, result.getRoa()),
                () -> assertEquals(78.1878, result.getGrossMargin()),
                () -> assertEquals(21.9738, result.getNetMargin()),
                () -> assertEquals(38.2246, result.getDebtToAssets()),
                () -> assertEquals(1.1712, result.getCurrentRatio()),
                () -> assertEquals(new BigDecimal("0.435"), result.getEps()),
                () -> assertEquals(new BigDecimal("6.4341"), result.getBookValuePerShare()),
                () -> assertEquals(new BigDecimal("1126074496.05"), result.getRevenue()),
                () -> assertEquals(new BigDecimal("246333622.73"), result.getNetIncome()),
                () -> assertEquals(new BigDecimal("5965684837.28"), result.getTotalAssets()),
                () -> assertEquals(new BigDecimal("2280359405.02"), result.getTotalDebt()),
                () -> assertEquals(new BigDecimal("268450155.61"), result.getOperatingCashFlow()),
                () -> assertEquals(new BigDecimal("255318894.38"), result.getFreeCashFlow()),
                () -> assertEquals(10.3177, result.getRevenueGrowth()),
                () -> assertEquals(13.6133, result.getNetIncomeGrowth()),
                () -> assertEquals(result.getNetIncomeGrowth(), result.getEarningsGrowth()),
                () -> assertEquals(16.6064, result.getPe()),
                () -> assertEquals(15.9852, result.getPeTtm()),
                () -> assertEquals(3.4566, result.getPb()),
                () -> assertEquals(3.1861, result.getPsRatio()),
                () -> assertEquals(4.0468, result.getDividendYield()),
                () -> assertEquals(15.9852 / 13.6133, result.getPegRatio(), 0.0001));
    }

    @Test
    void getFundamentalDataUsesLatestDailyBasicPeTtm() {
        TushareApiClient client = createTestClient((apiName, params, fields) -> {
            if ("fina_indicator".equals(apiName) && fields.contains("roe")) {
                return List.of(Map.of("eps", "0.435", "roe", "6.9887"));
            }
            if ("daily_basic".equals(apiName)) {
                return List.of(Map.of("trade_date", "20260807", "pe_ttm", "16.6"));
            }
            return Collections.emptyList();
        });

        FundamentalDataVO result = new TushareStockDataProvider(
                client, indicatorCalculator, mockNewsSearchProvider).getFundamentalData("600285.SH");

        assertEquals(16.6, result.getPeTtm());
        assertEquals(new BigDecimal("0.435"), result.getEps());
        assertEquals("2026-08-07", result.getValuationTradeDate());
    }

    @Test
    void missingDailyBasicKeepsValuationNull() {
        TushareApiClient client = createTestClient((apiName, params, fields) -> {
            if ("fina_indicator".equals(apiName) && fields.contains("roe")) {
                return List.of(Map.of("eps", "0.435", "roe", "6.9887"));
            }
            return Collections.emptyList();
        });

        FundamentalDataVO result = new TushareStockDataProvider(
                client, indicatorCalculator, mockNewsSearchProvider).getFundamentalData("600285.SH");

        assertNull(result.getPeTtm());
        assertEquals(new BigDecimal("0.435"), result.getEps());
    }

    @Test
    void getFundamentalData_zeroNetProfitGrowthKeepsPegNull() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("fina_indicator".equals(apiName)) {
                return List.of(Map.of(
                        "roe", "12.0",
                        "tr_yoy", "10.0",
                        "netprofit_yoy", "0"));
            }
            if ("daily_basic".equals(apiName)) {
                return List.of(Map.of("trade_date", "20260807", "pe_ttm", "15.0"));
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        FundamentalDataVO result = provider.getFundamentalData("600000");

        assertEquals(10.0, result.getRevenueGrowth());
        assertEquals(0.0, result.getNetIncomeGrowth());
        assertNull(result.getPegRatio());
    }

    // ==================== getSentiment 测试 ====================

    @Test
    void getSentiment_derivation() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("daily".equals(apiName)) {
                return List.of(
                        Map.of("trade_date", "20240101", "open", "10.0", "high", "10.8", "low", "9.8", "close", "10.5", "vol", "1000000"),
                        Map.of("trade_date", "20240102", "open", "10.5", "high", "11.0", "low", "10.2", "close", "10.8", "vol", "1200000"),
                        Map.of("trade_date", "20240103", "open", "10.8", "high", "11.2", "low", "10.6", "close", "11.0", "vol", "1100000"),
                        Map.of("trade_date", "20240104", "open", "11.0", "high", "11.5", "low", "10.8", "close", "11.3", "vol", "1300000"),
                        Map.of("trade_date", "20240105", "open", "11.3", "high", "11.8", "low", "11.1", "close", "11.5", "vol", "1400000")
                );
            }
            if ("fina_indicator".equals(apiName)) {
                return List.of(Map.of(
                        "roe", "15.0",
                        "grossprofit_margin", "30.0",
                        "netprofit_margin", "15.0",
                        "debt_to_assets", "0.65",
                        "current_ratio", "1.5",
                        "pe", "10.0"
                ));
            }
            if ("stock_basic".equals(apiName)) {
                return List.of(Map.of("name", "测试股票", "exchange", "SSE"));
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        SentimentDataVO result = provider.getSentiment("600000");

        assertNotNull(result);
        assertNotNull(result.getOverallScore());
        assertNotNull(result.getShortTermScore());
        assertNotNull(result.getMediumTermScore());
        assertNotNull(result.getBullRatio());
        assertNotNull(result.getBearRatio());
        assertNotNull(result.getFearGreedIndex());

        // 验证 fearGreedIndex 在 0-100 范围内
        assertTrue(result.getFearGreedIndex() >= 0);
        assertTrue(result.getFearGreedIndex() <= 100);

        // 验证 bullRatio + bearRatio <= 1
        assertTrue(result.getBullRatio() + result.getBearRatio() <= 1.01); // 允许浮点误差
    }

    // ==================== getNews 测试 ====================

    @Test
    void getNews_notImplemented() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> Collections.emptyList());
        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        List<NewsItemVO> result = provider.getNews("600000", 10);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== getStockInfo 补充场景 ====================

    @Test
    void getStockInfo_stockBasicEmpty() {
        // stock_basic 返回空列表，验证抛出明确运行时异常（对应 Q6）
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("stock_basic".equals(apiName)) {
                return Collections.emptyList();
            }
            return null; // 其他接口让真实实现执行
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> provider.getStockInfo("600000"));
        // 验证异常消息包含股票基本信息查询失败（被 catch 块包装，原消息在 cause 中）
        assertTrue(exception.getMessage().contains("获取股票信息失败"));
        assertNotNull(exception.getCause());
        assertTrue(exception.getCause().getMessage().contains("股票基本信息查询失败"));
    }

    @Test
    void getStockInfo_dailyEmpty() {
        // daily 返回空（既没有当天也没有最近交易日），验证 currentPrice=null
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("stock_basic".equals(apiName)) {
                return List.of(Map.of("ts_code", "600000.SH", "name", "浦发银行", "exchange", "SSE"));
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        StockInfoVO result = provider.getStockInfo("600000");

        assertNotNull(result);
        assertEquals("600000", result.getTicker());
        assertEquals("浦发银行", result.getName());
        assertNull(result.getCurrentPrice());
        assertNull(result.getVolume());
    }

    @Test
    void getStockInfo_week52HighLowEmpty() {
        // 52周历史数据为空，验证 week52High=null，week52Low=null 不抛异常
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("stock_basic".equals(apiName)) {
                return List.of(Map.of("ts_code", "600000.SH", "name", "浦发银行", "exchange", "SSE"));
            }
            if ("daily".equals(apiName)) {
                if (fields.contains("close")) {
                    return List.of(Map.of(
                            "ts_code", "600000.SH", "trade_date", "20240101", "close", "10.5", "vol", "1000000"));
                } else {
                    return Collections.emptyList();
                }
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        StockInfoVO result = provider.getStockInfo("600000");

        assertNotNull(result);
        assertEquals(new BigDecimal("10.5"), result.getCurrentPrice());
        assertNull(result.getWeek52High());
        assertNull(result.getWeek52Low());
    }

    // ==================== getHistoricalBars 补充场景 ====================

    @Test
    void getHistoricalBars_invalidDateFormat() {
        // 传入非标准日期字符串（如 20240101），验证 convertToTushareDate 容错处理
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("daily".equals(apiName)) {
                return List.of(
                        Map.of("trade_date", "20240101", "open", "10.0", "high", "10.8", "low", "9.8", "close", "10.5", "vol", "1000000")
                );
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        // 传入 yyyyMMdd 格式而非 yyyy-MM-dd
        List<OHLCVBarVO> result = provider.getHistoricalBars("600000", "20240101", "20240105");

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("2024-01-01", result.get(0).getDate());
    }

    // ==================== getFundamentalData 补充场景 ====================

    @Test
    void getFundamentalData_finaIndicatorEmpty() {
        // fina_indicator 返回空（新股/无权限），验证所有字段为 null，不抛异常
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> Collections.emptyList());

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        FundamentalDataVO result = provider.getFundamentalData("600000");

        assertNotNull(result);
        assertNull(result.getRoe());
        assertNull(result.getGrossMargin());
        assertNull(result.getNetMargin());
        assertNull(result.getPeTtm());
        assertNull(result.getRevenueGrowth());
        assertNull(result.getNetIncomeGrowth());
        assertNull(result.getFreeCashFlow());
    }

    @Test
    void getFundamentalData_lastYearDataEmpty() {
        // 只有本期数据无去年同期数据，验证 revenueGrowth=null，netIncomeGrowth=null
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("fina_indicator".equals(apiName)) {
                if (fields.contains("roe")) {
                    Map<String, String> row = new HashMap<>();
                    row.put("roe", "12.5");
                    row.put("revenue", "50000");
                    row.put("net_profit", "8000");
                    return List.of(row);
                } else {
                    return Collections.emptyList();
                }
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        FundamentalDataVO result = provider.getFundamentalData("600000");

        assertNotNull(result);
        assertEquals(12.5, result.getRoe());
        assertNull(result.getRevenueGrowth());
        assertNull(result.getNetIncomeGrowth());
    }

    @Test
    void getFundamentalData_cashFlowEmpty() {
        // cash_flow 返回空，验证 freeCashFlow=null，其他字段正常
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("fina_indicator".equals(apiName)) {
                Map<String, String> row = new HashMap<>();
                row.put("roe", "12.5");
                row.put("revenue", "50000");
                row.put("net_profit", "8000");
                return List.of(row);
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        FundamentalDataVO result = provider.getFundamentalData("600000");

        assertNotNull(result);
        assertEquals(12.5, result.getRoe());
        assertNull(result.getFreeCashFlow());
    }

    @Test
    void getFundamentalData_fcfCapexPositive() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("fina_indicator".equals(apiName)) {
                return List.of(Map.of(
                        "end_date", "20260331",
                        "roe", "12.5"));
            }
            if ("cashflow".equals(apiName)) {
                return List.of(Map.of(
                        "end_date", "20260331",
                        "n_cashflow_act", "50000000",
                        "c_pay_acq_const_fiolta", "10000000"));
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        FundamentalDataVO result = provider.getFundamentalData("600000");

        assertNotNull(result);
        assertEquals(40000000L, result.getFreeCashFlow().longValue());
    }

    @Test
    void getFundamentalData_prefersLatestRevisionForSamePeriod() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("fina_indicator".equals(apiName)) {
                return List.of(
                        Map.of("ann_date", "20260420", "end_date", "20260331",
                                "update_flag", "0", "tr_yoy", "8.0", "netprofit_yoy", "9.0"),
                        Map.of("ann_date", "20260428", "end_date", "20260331",
                                "update_flag", "1", "tr_yoy", "12.0", "netprofit_yoy", "15.0"));
            }
            if ("income".equals(apiName)) {
                return List.of(
                        Map.of("ann_date", "20260420", "end_date", "20260331",
                                "update_flag", "0", "revenue", "100000000",
                                "n_income_attr_p", "20000000"),
                        Map.of("ann_date", "20260428", "end_date", "20260331",
                                "update_flag", "1", "revenue", "120000000",
                                "n_income_attr_p", "24000000"));
            }
            return Collections.emptyList();
        });

        FundamentalDataVO result = new TushareStockDataProvider(
                testClient, indicatorCalculator, mockNewsSearchProvider)
                .getFundamentalData("600285.SH");

        assertEquals(12.0, result.getRevenueGrowth());
        assertEquals(15.0, result.getNetIncomeGrowth());
        assertEquals(new BigDecimal("120000000"), result.getRevenue());
        assertEquals(new BigDecimal("24000000"), result.getNetIncome());
    }

    @Test
    void getFundamentalData_emptyBalanceSheetKeepsOtherSources() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("fina_indicator".equals(apiName)) {
                return List.of(Map.of("end_date", "20260331", "roe", "12.0"));
            }
            if ("income".equals(apiName)) {
                return List.of(Map.of("end_date", "20260331", "revenue", "120000000"));
            }
            if ("cashflow".equals(apiName)) {
                return List.of(Map.of("end_date", "20260331", "n_cashflow_act", "30000000"));
            }
            if ("daily_basic".equals(apiName)) {
                return List.of(Map.of("trade_date", "20260807", "pe_ttm", "15.0"));
            }
            return Collections.emptyList();
        });

        FundamentalDataVO result = new TushareStockDataProvider(
                testClient, indicatorCalculator, mockNewsSearchProvider)
                .getFundamentalData("600285.SH");

        assertNull(result.getTotalAssets());
        assertNull(result.getTotalDebt());
        assertEquals(new BigDecimal("120000000"), result.getRevenue());
        assertEquals(new BigDecimal("30000000"), result.getOperatingCashFlow());
        assertEquals(15.0, result.getPeTtm());
    }

    @Test
    void getFundamentalData_missingGrowthKeepsPegNull() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("fina_indicator".equals(apiName)) {
                return List.of(Map.of("end_date", "20260331", "roe", "12.0"));
            }
            if ("daily_basic".equals(apiName)) {
                return List.of(Map.of("trade_date", "20260807", "pe_ttm", "15.0"));
            }
            return Collections.emptyList();
        });

        FundamentalDataVO result = new TushareStockDataProvider(
                testClient, indicatorCalculator, mockNewsSearchProvider)
                .getFundamentalData("600285.SH");

        assertNull(result.getNetIncomeGrowth());
        assertNull(result.getPegRatio());
    }

    @Test
    void getFundamentalData_negativeGrowth() {
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("fina_indicator".equals(apiName)) {
                return List.of(Map.of(
                        "roe", "10.0",
                        "tr_yoy", "-20.0",
                        "netprofit_yoy", "-37.5"));
            }
            if ("daily_basic".equals(apiName)) {
                return List.of(Map.of("trade_date", "20260807", "pe_ttm", "15.0"));
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        FundamentalDataVO result = provider.getFundamentalData("600000");

        assertNotNull(result);
        assertEquals(-20.0, result.getRevenueGrowth(), 0.01);
        assertEquals(-37.5, result.getNetIncomeGrowth(), 0.01);
        assertNull(result.getPegRatio());
    }

    // ==================== getSentiment 补充场景 ====================

    @Test
    void getSentiment_fundamentalDataFailed() {
        // 基本面接口失败（无专业版权限），验证 analystScore=null，情绪推导仍可继续（对应 Q8）
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("daily".equals(apiName)) {
                return List.of(
                        Map.of("trade_date", "20240101", "open", "10.0", "high", "10.8", "low", "9.8", "close", "10.5", "vol", "1000000"),
                        Map.of("trade_date", "20240102", "open", "10.5", "high", "11.0", "low", "10.2", "close", "10.8", "vol", "1200000"),
                        Map.of("trade_date", "20240103", "open", "10.8", "high", "11.2", "low", "10.6", "close", "11.0", "vol", "1100000"),
                        Map.of("trade_date", "20240104", "open", "11.0", "high", "11.5", "low", "10.8", "close", "11.3", "vol", "1300000"),
                        Map.of("trade_date", "20240105", "open", "11.3", "high", "11.8", "low", "11.1", "close", "11.5", "vol", "1400000")
                );
            }
            if ("stock_basic".equals(apiName)) {
                return List.of(Map.of("name", "测试股票", "exchange", "SSE"));
            }
            if ("fina_indicator".equals(apiName)) {
                throw new RuntimeException("权限不足");
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        SentimentDataVO result = provider.getSentiment("600000");

        assertNotNull(result);
        assertNotNull(result.getShortTermScore());
        // analystScore 应该为 null（基本面失败）
        assertNull(result.getAnalystScore());
        assertNotNull(result.getOverallScore());
    }

    @Test
    void getSentiment_peNegative() {
        // PE 为负数（亏损公司），验证 deriveAnalystScore 不会除零或抛异常
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("daily".equals(apiName)) {
                return List.of(
                        Map.of("trade_date", "20240101", "open", "10.0", "high", "10.8", "low", "9.8", "close", "10.5", "vol", "1000000"),
                        Map.of("trade_date", "20240102", "open", "10.5", "high", "11.0", "low", "10.2", "close", "10.8", "vol", "1200000"),
                        Map.of("trade_date", "20240103", "open", "10.8", "high", "11.2", "low", "10.6", "close", "11.0", "vol", "1100000"),
                        Map.of("trade_date", "20240104", "open", "11.0", "high", "11.5", "low", "10.8", "close", "11.3", "vol", "1300000"),
                        Map.of("trade_date", "20240105", "open", "11.3", "high", "11.8", "low", "11.1", "close", "11.5", "vol", "1400000")
                );
            }
            if ("stock_basic".equals(apiName)) {
                return List.of(Map.of("name", "亏损股票", "exchange", "SSE"));
            }
            if ("fina_indicator".equals(apiName)) {
                return List.of(Map.of(
                        "roe", "-5.0",
                        "grossprofit_margin", "20.0",
                        "netprofit_margin", "-10.0",
                        "debt_to_assets", "0.7",
                        "current_ratio", "0.8",
                        "pe", "-10.5"
                ));
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        SentimentDataVO result = provider.getSentiment("600000");

        assertNotNull(result);
        // PE 为负数，deriveAnalystScore 应返回 null（不会除零或抛异常）
        assertNull(result.getAnalystScore());
        assertNotNull(result.getShortTermScore());
    }

    @Test
    void getSentiment_noBarsForMa5() {
        // 历史数据不足 5 条，验证 ma5=null 时 deriveShortTermSentiment 返回 0.0 而非抛异常
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("daily".equals(apiName)) {
                return List.of(
                        Map.of("trade_date", "20240101", "open", "10.0", "high", "10.8", "low", "9.8", "close", "10.5", "vol", "1000000"),
                        Map.of("trade_date", "20240102", "open", "10.5", "high", "11.0", "low", "10.2", "close", "10.8", "vol", "1200000")
                );
            }
            if ("stock_basic".equals(apiName)) {
                return List.of(Map.of("name", "测试股票", "exchange", "SSE"));
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        SentimentDataVO result = provider.getSentiment("600000");

        assertNotNull(result);
        // 历史数据不足，shortTermScore 应为 0.0
        assertEquals(0.0, result.getShortTermScore());
    }

    @Test
    void getSentiment_allNull() {
        // 所有数据都为空，验证返回的 SentimentDataVO 各字段不为 null（NPE 检查）
        TushareApiClient testClient = createTestClient((apiName, params, fields) -> {
            if ("stock_basic".equals(apiName)) {
                return List.of(Map.of("name", "测试股票", "exchange", "SSE"));
            }
            return Collections.emptyList();
        });

        TushareStockDataProvider provider = new TushareStockDataProvider(testClient, indicatorCalculator, mockNewsSearchProvider);
        SentimentDataVO result = provider.getSentiment("600000");

        assertNotNull(result);
        assertNotNull(result.getOverallScore());
        assertNotNull(result.getSocialMediaScore());
        assertNotNull(result.getNewsScore());
        assertNull(result.getAnalystScore());
        assertNotNull(result.getShortTermScore());
        assertNotNull(result.getMediumTermScore());
        assertNotNull(result.getLongTermScore());
        assertNotNull(result.getBullRatio());
        assertNotNull(result.getBearRatio());
        assertNotNull(result.getSocialBuzz());
        assertNotNull(result.getFearGreedIndex());
    }
}
