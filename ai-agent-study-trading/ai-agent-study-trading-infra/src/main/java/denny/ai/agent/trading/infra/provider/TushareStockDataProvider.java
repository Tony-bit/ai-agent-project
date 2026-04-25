package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.infra.calculator.TechnicalIndicatorCalculator;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tushare A股数据 Provider 实现。
 * <p>
 * 从 Tushare Pro API 获取 A股（沪深北交所）数据。
 * ticker 格式转换：
 * <ul>
 *   <li>"000001" → "000001.SZ" (深交所)</li>
 *   <li>"600000" → "600000.SH" (上交所)</li>
 *   <li>"430001" → "430001.BJ" (北交所)</li>
 *   <li>"NVDA" → 抛 IllegalArgumentException（非A股）</li>
 * </ul>
 */
@Slf4j
public class TushareStockDataProvider implements IStockDataProvider {

    private static final DateTimeFormatter TUSHARE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter OUTPUT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TushareApiClient apiClient;
    private final TechnicalIndicatorCalculator indicatorCalculator;
    private final INewsSearchProvider newsSearchProvider;

    public TushareStockDataProvider(TushareApiClient apiClient,
                                    TechnicalIndicatorCalculator indicatorCalculator,
                                    INewsSearchProvider newsSearchProvider) {
        this.apiClient = apiClient;
        this.indicatorCalculator = indicatorCalculator;
        this.newsSearchProvider = newsSearchProvider;
    }

    @Override
    public StockInfoVO getStockInfo(String ticker) {
        String tsCode = toTsCode(ticker);

        try {
            // 查询股票基本信息
            Map<String, Object> basicParams = new HashMap<>();
            basicParams.put("ts_code", tsCode);
            basicParams.put("list_status", "L");
            List<Map<String, String>> basicData = apiClient.call("stock_basic", basicParams, "ts_code,name,exchange");

            if (basicData.isEmpty()) {
                throw new RuntimeException("股票基本信息查询失败，可能是股票代码不存在或 Token 无权限: " + tsCode);
            }

            String name = ticker;
            String exchange = inferExchange(ticker);
            Map<String, String> basic = basicData.get(0);
            name = basic.getOrDefault("name", ticker);
            String ex = basic.get("exchange");
            if (ex != null) {
                exchange = ex;
            }

            // 查询最新日线数据
            Map<String, Object> dailyParams = new HashMap<>();
            dailyParams.put("ts_code", tsCode);
            dailyParams.put("trade_date", LocalDate.now().format(TUSHARE_DATE_FORMAT));
            List<Map<String, String>> dailyData = apiClient.call("daily", dailyParams, "ts_code,trade_date,close,vol");

            BigDecimal currentPrice = null;
            Long volume = null;
            if (dailyData.isEmpty()) {
                // 如果没有当天数据，查询最近交易日
                dailyParams.remove("trade_date");
                dailyParams.put("end_date", LocalDate.now().format(TUSHARE_DATE_FORMAT));
                dailyParams.put("limit", "1");
                dailyData = apiClient.call("daily", dailyParams, "ts_code,trade_date,close,vol");
            }
            if (!dailyData.isEmpty()) {
                Map<String, String> daily = dailyData.get(0);
                currentPrice = parseDecimal(daily.get("close"));
                volume = parseLong(daily.get("vol"));
            }

            // 查询 52 周高低
            LocalDate oneYearAgo = LocalDate.now().minusYears(1);
            Map<String, Object> historyParams = new HashMap<>();
            historyParams.put("ts_code", tsCode);
            historyParams.put("start_date", oneYearAgo.format(TUSHARE_DATE_FORMAT));
            historyParams.put("end_date", LocalDate.now().format(TUSHARE_DATE_FORMAT));
            List<Map<String, String>> historyData = apiClient.call("daily", historyParams,
                    "high,low");

            BigDecimal week52High = null;
            BigDecimal week52Low = null;
            if (!historyData.isEmpty()) {
                BigDecimal maxHigh = BigDecimal.ZERO;
                BigDecimal minLow = new BigDecimal("999999999");
                for (Map<String, String> bar : historyData) {
                    BigDecimal high = parseDecimal(bar.get("high"));
                    BigDecimal low = parseDecimal(bar.get("low"));
                    if (high != null && high.compareTo(maxHigh) > 0) {
                        maxHigh = high;
                    }
                    if (low != null && low.compareTo(minLow) < 0) {
                        minLow = low;
                    }
                }
                week52High = maxHigh.compareTo(BigDecimal.ZERO) > 0 ? maxHigh : null;
                week52Low = minLow.compareTo(new BigDecimal("999999999")) < 0 ? minLow : null;
            }

            return StockInfoVO.builder()
                    .ticker(ticker)
                    .name(name)
                    .exchange(exchange)
                    .currentPrice(currentPrice)
                    .volume(volume)
                    .week52High(week52High)
                    .week52Low(week52Low)
                    .build();
        } catch (Exception e) {
            log.error("获取股票信息失败: ticker={}, error={}", ticker, e.getMessage());
            throw new RuntimeException("获取股票信息失败: " + ticker, e);
        }
    }

    @Override
    public List<OHLCVBarVO> getHistoricalBars(String ticker, String startDate, String endDate) {
        String tsCode = toTsCode(ticker);

        try {
            // 转换日期格式
            String tushareStartDate = convertToTushareDate(startDate);
            String tushareEndDate = convertToTushareDate(endDate);

            Map<String, Object> params = new HashMap<>();
            params.put("ts_code", tsCode);
            params.put("start_date", tushareStartDate);
            params.put("end_date", tushareEndDate);

            List<Map<String, String>> data = apiClient.call("daily", params,
                    "trade_date,open,high,low,close,vol");

            return data.stream()
                    .map(this::convertToOHLCVBar)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("获取历史K线失败: ticker={}, error={}", ticker, e.getMessage());
            throw new RuntimeException("获取历史K线失败: " + ticker, e);
        }
    }

    @Override
    public TechnicalIndicatorsVO getTechnicalIndicators(String ticker, String startDate, String endDate) {
        try {
            List<OHLCVBarVO> bars = getHistoricalBars(ticker, startDate, endDate);
            return indicatorCalculator.calculate(ticker, bars);
        } catch (Exception e) {
            log.error("计算技术指标失败: ticker={}, error={}", ticker, e.getMessage());
            throw new RuntimeException("计算技术指标失败: " + ticker, e);
        }
    }

    @Override
    public FundamentalDataVO getFundamentalData(String ticker) {
        String tsCode = toTsCode(ticker);

        try {
            // 查询财务指标（最新一期）
            Map<String, Object> finaParams = new HashMap<>();
            finaParams.put("ts_code", tsCode);
            finaParams.put("limit", "1");
            finaParams.put("sort", "ann_date");
            finaParams.put("order", "desc");
            List<Map<String, String>> finaData = apiClient.call("fina_indicator", finaParams,
                    "roe, grossprofit_margin, netprofit_margin, debt_to_assets, current_ratio, " +
                    "pe_ratio, pb_ratio, ps_ratio, peg, eps, revenue, net_profit, div_ratio");

            // 查询去年同期数据用于计算增长率
            LocalDate lastYear = LocalDate.now().minusYears(1);
            Map<String, Object> lastYearParams = new HashMap<>();
            lastYearParams.put("ts_code", tsCode);
            lastYearParams.put("end_date", lastYear.format(TUSHARE_DATE_FORMAT));
            lastYearParams.put("limit", "1");
            lastYearParams.put("sort", "end_date");
            lastYearParams.put("order", "desc");
            List<Map<String, String>> lastYearData = apiClient.call("fina_indicator", lastYearParams,
                    "revenue, net_profit");

            Double revenueGrowth = null;
            Double netIncomeGrowth = null;
            BigDecimal currentRevenue = null;
            BigDecimal currentNetIncome = null;
            BigDecimal lastYearRevenue = null;
            BigDecimal lastYearNetIncome = null;

            if (!finaData.isEmpty()) {
                Map<String, String> fina = finaData.get(0);
                currentRevenue = parseDecimalWan(fina.get("revenue"));
                currentNetIncome = parseDecimalWan(fina.get("net_profit"));
            }

            if (!lastYearData.isEmpty()) {
                Map<String, String> lastYearFina = lastYearData.get(0);
                lastYearRevenue = parseDecimalWan(lastYearFina.get("revenue"));
                lastYearNetIncome = parseDecimalWan(lastYearFina.get("net_profit"));
            }

            if (currentRevenue != null && lastYearRevenue != null && lastYearRevenue.compareTo(BigDecimal.ZERO) != 0) {
                revenueGrowth = currentRevenue.subtract(lastYearRevenue)
                        .divide(lastYearRevenue, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).doubleValue();
            }

            if (currentNetIncome != null && lastYearNetIncome != null && lastYearNetIncome.compareTo(BigDecimal.ZERO) != 0) {
                netIncomeGrowth = currentNetIncome.subtract(lastYearNetIncome)
                        .divide(lastYearNetIncome, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).doubleValue();
            }

            // 查询现金流数据（计算 freeCashFlow）
            Map<String, Object> cashFlowParams = new HashMap<>();
            cashFlowParams.put("ts_code", tsCode);
            cashFlowParams.put("limit", "1");
            cashFlowParams.put("sort", "ann_date");
            cashFlowParams.put("order", "desc");
            List<Map<String, String>> cashFlowData = apiClient.call("cash_flow", cashFlowParams,
                    "im_net_incr_cash_equv, pay_for_fixed_assets");

            BigDecimal freeCashFlow = null;
            if (!cashFlowData.isEmpty()) {
                Map<String, String> cf = cashFlowData.get(0);
                BigDecimal operatingCashFlow = parseDecimalWan(cf.get("im_net_incr_cash_equv"));
                BigDecimal capex = parseDecimalWan(cf.get("pay_for_fixed_assets"));
                if (operatingCashFlow != null) {
                    // 单位：万元 → 元（乘以10000）
                    freeCashFlow = operatingCashFlow.multiply(new BigDecimal("10000"));
                    if (capex != null) {
                        // pay_for_fixed_assets 字段语义：正值表示投资支出（减少自由现金流），负值表示资产处置（增加）
                        // 取绝对值处理，Tushare 返回正数表示实际资本支出
                        freeCashFlow = freeCashFlow.subtract(capex.abs().multiply(new BigDecimal("10000")));
                    }
                }
            }

            // 构建返回值
            FundamentalDataVO.FundamentalDataVOBuilder builder = FundamentalDataVO.builder();

            if (!finaData.isEmpty()) {
                Map<String, String> fina = finaData.get(0);
                builder.roe(parsePercent(fina.get("roe")))
                       .grossMargin(parsePercent(fina.get("grossprofit_margin")))
                       .netMargin(parsePercent(fina.get("netprofit_margin")))
                       .debtToEquity(parseDouble(fina.get("debt_to_assets")))
                       .currentRatio(parseDouble(fina.get("current_ratio")))
                       .peRatio(parseDouble(fina.get("pe_ratio")))
                       .pbRatio(parseDouble(fina.get("pb_ratio")))
                       .psRatio(parseDouble(fina.get("ps_ratio")))
                       .pegRatio(parseDouble(fina.get("peg")))
                       .eps(parseDecimalWan(fina.get("eps")))
                       .revenue(currentRevenue != null ? currentRevenue.multiply(new BigDecimal("10000")) : null)
                       .netIncome(currentNetIncome != null ? currentNetIncome.multiply(new BigDecimal("10000")) : null)
                       .dividendYield(parsePercent(fina.get("div_ratio")));
            }

            return builder
                    .revenueGrowth(revenueGrowth)
                    .netIncomeGrowth(netIncomeGrowth)
                    .freeCashFlow(freeCashFlow)
                    .build();
        } catch (Exception e) {
            log.error("获取基本面数据失败: ticker={}, error={}", ticker, e.getMessage());
            throw new RuntimeException("获取基本面数据失败: " + ticker, e);
        }
    }

    @Override
    public List<NewsItemVO> getNews(String ticker, int limit) {
        if (newsSearchProvider == null) {
            log.warn("新闻搜索 Provider 未配置，getNews 返回空列表: ticker={}", ticker);
            return Collections.emptyList();
        }
        try {
            return newsSearchProvider.searchNews(ticker, limit);
        } catch (Exception e) {
            log.error("新闻搜索失败: ticker={}, error={}", ticker, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public SentimentDataVO getSentiment(String ticker) {
        try {
            // 获取股价数据用于情绪推导
            StockInfoVO stockInfo = getStockInfo(ticker);

            // 获取技术指标
            String endDate = LocalDate.now().toString();
            String startDate = LocalDate.now().minusMonths(6).toString();
            TechnicalIndicatorsVO indicators = getTechnicalIndicators(ticker, startDate, endDate);

            // 获取基本面数据（PE）
            FundamentalDataVO fundamentals = null;
            try {
                fundamentals = getFundamentalData(ticker);
            } catch (Exception e) {
                log.warn("获取基本面数据失败（情绪推导将降级），可能是无专业版权限或股票数据缺失: ticker={}, error={}", ticker, e.getMessage());
            }

            // 推导短期情绪
            Double shortTerm = deriveShortTermSentiment(stockInfo, indicators);

            // 推导分析师评分
            Double analyst = deriveAnalystScore(fundamentals);

            // 计算综合评分
            Double overall = (analyst != null) ? round(analyst * 0.6 + shortTerm * 0.4) : shortTerm;

            // 推导其他情绪指标
            Double bullRatio = deriveBullRatio(analyst);
            Double bearRatio = deriveBearRatio(analyst);
            Integer fearGreedIndex = deriveFearGreedIndex(overall);

            return SentimentDataVO.builder()
                    .overallScore(overall)
                    .socialMediaScore(0.0) // Tushare 无此数据
                    .newsScore(0.0)       // Tushare 无此数据
                    .analystScore(analyst)
                    .shortTermScore(shortTerm)
                    .mediumTermScore(overall)
                    .longTermScore(overall)
                    .bullRatio(bullRatio)
                    .bearRatio(bearRatio)
                    .socialBuzz(50.0)      // 默认中等热度
                    .fearGreedIndex(fearGreedIndex)
                    .platformSentiments(null)
                    .institutionalHoldingChange(null)
                    .build();
        } catch (Exception e) {
            log.error("获取情绪数据失败: ticker={}, error={}", ticker, e.getMessage());
            throw new RuntimeException("获取情绪数据失败: " + ticker, e);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 将 A股 ticker 转换为 Tushare ts_code 格式。
     */
    String toTsCode(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker 不能为空");
        }
        String normalized = ticker.trim();
        if (!normalized.matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("非 A股 ticker: " + ticker + "（Tushare 仅支持 A股）");
        }
        char first = normalized.charAt(0);
        if (first == '0' || first == '1' || first == '2' || first == '3') {
            return normalized + ".SZ"; // 深圳交易所（含创业板 3 开头）
        } else if (first == '4' || first == '8' || first == '9') {
            return normalized + ".BJ"; // 北交所
        } else {
            return normalized + ".SH"; // 上海交易所（6 开头）
        }
    }

    private String inferExchange(String ticker) {
        if (ticker == null || ticker.length() < 1) return "UNKNOWN";
        char first = ticker.charAt(0);
        if (first == '0' || first == '1' || first == '2' || first == '3') {
            return "SZSE";
        } else if (first == '4' || first == '8' || first == '9') {
            return "BSE";
        } else {
            return "SSE";
        }
    }

    private String convertToTushareDate(String date) {
        if (date == null || date.isBlank()) {
            return LocalDate.now().format(TUSHARE_DATE_FORMAT);
        }
        try {
            LocalDate localDate = LocalDate.parse(date, OUTPUT_DATE_FORMAT);
            return localDate.format(TUSHARE_DATE_FORMAT);
        } catch (Exception e) {
            return date.replace("-", "");
        }
    }

    private OHLCVBarVO convertToOHLCVBar(Map<String, String> row) {
        return OHLCVBarVO.builder()
                .date(convertOutputDate(row.get("trade_date")))
                .open(parseDecimal(row.get("open")))
                .high(parseDecimal(row.get("high")))
                .low(parseDecimal(row.get("low")))
                .close(parseDecimal(row.get("close")))
                .volume(parseLong(row.get("vol")))
                .adjustedClose(parseDecimal(row.get("close")))
                .build();
    }

    private String convertOutputDate(String tushareDate) {
        if (tushareDate == null || tushareDate.length() != 8) {
            return tushareDate;
        }
        return tushareDate.substring(0, 4) + "-" + tushareDate.substring(4, 6) + "-" + tushareDate.substring(6, 8);
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseDecimalWan(String value) {
        // Tushare fina_indicator 返回的单位是万元
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parsePercent(String value) {
        // Tushare 返回的 margin 类字段是百分比数值（如 15.5 表示 15.5%），直接返回
        return parseDouble(value);
    }

    // ==================== 情绪推导方法 ====================

    private Double deriveShortTermSentiment(StockInfoVO stockInfo, TechnicalIndicatorsVO indicators) {
        if (stockInfo == null || stockInfo.getCurrentPrice() == null) return 0.0;
        try {
            BigDecimal price = stockInfo.getCurrentPrice();
            BigDecimal ma5 = indicators.getMa5();
            if (ma5 == null || ma5.compareTo(BigDecimal.ZERO) <= 0) {
                return 0.0;
            }
            double maScore = price.subtract(ma5).divide(ma5, 4, RoundingMode.HALF_UP).doubleValue() * 10;
            maScore = Math.max(-1, Math.min(1, maScore));
            return round(maScore);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private Double deriveAnalystScore(FundamentalDataVO fundamentals) {
        if (fundamentals == null) return null;
        try {
            Double pe = fundamentals.getPeRatio();
            Double roe = fundamentals.getRoe();
            if (pe == null || pe <= 0) return null;
            double score = 0;
            // PE 合理区间 10-30，低于10偏多，高于30偏空
            score += (pe < 15 ? 0.3 : pe > 30 ? -0.3 : 0.0);
            // ROE > 15% 偏多
            if (roe != null && roe > 15) score += 0.3;
            return round(Math.max(-1, Math.min(1, score)));
        } catch (Exception e) {
            return null;
        }
    }

    private Double deriveBullRatio(Double analystScore) {
        if (analystScore == null) return 0.33;
        return round(Math.max(0, analystScore + 0.33));
    }

    private Double deriveBearRatio(Double analystScore) {
        if (analystScore == null) return 0.33;
        return round(Math.max(0, 0.33 - analystScore));
    }

    private Integer deriveFearGreedIndex(Double overallScore) {
        if (overallScore == null) return 50;
        return (int) Math.round((overallScore + 1) / 2 * 100);
    }

    private Double round(Double value) {
        if (value == null) return null;
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
