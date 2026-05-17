package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.infra.calculator.TechnicalIndicatorCalculator;
import denny.ai.agent.trading.infra.provider.tushare.dto.*;
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
            List<TushareStockBasicDTO> basicData = apiClient.callGeneric(
                    TushareStockBasicDTO.class, "stock_basic",
                    Map.of("ts_code", tsCode),
                    "ts_code,name,exchange,market");

            if (basicData.isEmpty()) {
                throw new RuntimeException("股票基本信息查询失败，可能是股票代码不存在或 Token 无权限: " + tsCode);
            }

            TushareStockBasicDTO basic = basicData.get(0);
            String name = basic.getName() != null ? basic.getName() : ticker;
            String exchange = basic.getExchange() != null ? basic.getExchange() : inferExchange(ticker);
            String market = basic.getMarket() != null ? basic.getMarket() : inferMarket(ticker);

            // 查询最新日线数据
            String today = LocalDate.now().format(TUSHARE_DATE_FORMAT);
            List<TushareDailyDTO> dailyData = apiClient.callGeneric(
                    TushareDailyDTO.class, "daily",
                    Map.of("ts_code", tsCode, "trade_date", today),
                    "ts_code,trade_date,close,vol");

            BigDecimal currentPrice = null;
            Long volume = null;
            if (dailyData.isEmpty()) {
                // 如果没有当天数据，查询最近交易日
                dailyData = apiClient.callGeneric(
                        TushareDailyDTO.class, "daily",
                        Map.of("ts_code", tsCode, "end_date", today, "limit", 1),
                        "ts_code,trade_date,close,vol");
            }
            if (!dailyData.isEmpty()) {
                TushareDailyDTO daily = dailyData.get(0);
                currentPrice = daily.getClose();
                volume = daily.getVol();
            }

            // 查询 52 周高低
            LocalDate oneYearAgo = LocalDate.now().minusYears(1);
            List<TushareDailyDTO> historyData = apiClient.callGeneric(
                    TushareDailyDTO.class, "daily",
                    Map.of("ts_code", tsCode,
                            "start_date", oneYearAgo.format(TUSHARE_DATE_FORMAT),
                            "end_date", today),
                    "high,low");

            BigDecimal week52High = null;
            BigDecimal week52Low = null;
            if (!historyData.isEmpty()) {
                BigDecimal maxHigh = BigDecimal.ZERO;
                BigDecimal minLow = new BigDecimal("999999999");
                for (TushareDailyDTO bar : historyData) {
                    BigDecimal high = bar.getHigh();
                    BigDecimal low = bar.getLow();
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
            String tushareStartDate = convertToTushareDate(startDate);
            String tushareEndDate = convertToTushareDate(endDate);

            List<TushareDailyDTO> data = apiClient.callGeneric(
                    TushareDailyDTO.class, "daily",
                    Map.of("ts_code", tsCode, "start_date", tushareStartDate, "end_date", tushareEndDate),
                    "trade_date,open,high,low,close,vol");

            return data.stream()
                    .map(this::toOHLCVBar)
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
            List<TushareFinaIndicatorDTO> finaData = apiClient.callGeneric(
                    TushareFinaIndicatorDTO.class, "fina_indicator",
                    Map.of("ts_code", tsCode, "limit", 1, "sort", "ann_date", "order", "desc"),
                    "roe,grossprofit_margin,netprofit_margin,debt_to_assets,current_ratio," +
                            "pe,pb_ratio,ps_ratio,peg,eps,revenue,net_profit,div_ratio,total_assets");

            // 查询去年同期数据用于计算增长率
            LocalDate lastYear = LocalDate.now().minusYears(1);
            List<TushareFinaIndicatorDTO> lastYearData = apiClient.callGeneric(
                    TushareFinaIndicatorDTO.class, "fina_indicator",
                    Map.of("ts_code", tsCode, "end_date", lastYear.format(TUSHARE_DATE_FORMAT),
                            "limit", 1, "sort", "end_date", "order", "desc"),
                    "revenue,net_profit");

            Double revenueGrowth = null;
            Double netIncomeGrowth = null;

            if (!finaData.isEmpty() && !lastYearData.isEmpty()) {
                BigDecimal currentRev = finaData.get(0).getRevenueYuan();
                BigDecimal lastRev = lastYearData.get(0).getRevenueYuan();
                BigDecimal currentNi = finaData.get(0).getNetProfitYuan();
                BigDecimal lastNi = lastYearData.get(0).getNetProfitYuan();

                if (currentRev != null && lastRev != null && lastRev.compareTo(BigDecimal.ZERO) != 0) {
                    revenueGrowth = currentRev.subtract(lastRev)
                            .divide(lastRev, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).doubleValue();
                }
                if (currentNi != null && lastNi != null && lastNi.compareTo(BigDecimal.ZERO) != 0) {
                    netIncomeGrowth = currentNi.subtract(lastNi)
                            .divide(lastNi, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).doubleValue();
                }
            }

            // 查询现金流数据（计算 freeCashFlow）
            List<TushareCashFlowDTO> cashFlowData = apiClient.callGeneric(
                    TushareCashFlowDTO.class, "cashflow",
                    Map.of("ts_code", tsCode, "limit", 1, "order", "desc"),
                    "im_net_incr_cash_equv,pay_for_fixed_assets,oper_net_cash_flow");

            BigDecimal freeCashFlow = null;
            if (!cashFlowData.isEmpty()) {
                TushareCashFlowDTO cf = cashFlowData.get(0);
                freeCashFlow = cf.getFreeCashFlowYuan();
            }

            // 构建返回值
            FundamentalDataVO.FundamentalDataVOBuilder builder = FundamentalDataVO.builder();

            if (!finaData.isEmpty()) {
                TushareFinaIndicatorDTO fina = finaData.get(0);
                builder.roe(fina.getRoe())
                       .grossMargin(fina.getGrossprofitMargin())
                       .netMargin(fina.getNetprofitMargin())
                       .debtToEquity(fina.getDebtToAssets())
                       .currentRatio(fina.getCurrentRatio())
                       .peRatio(fina.getPe())
                       .pbRatio(fina.getPbRatio())
                       .psRatio(fina.getPsRatio())
                       .pegRatio(fina.getPeg())
                       .eps(fina.getEps())
                       .revenue(fina.getRevenueYuan())
                       .netIncome(fina.getNetProfitYuan())
                       .totalAssets(fina.getTotalAssets() != null
                               ? fina.getTotalAssets().multiply(new BigDecimal("10000")) : null)
                       .dividendYield(fina.getDivRatio());
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
            StockInfoVO stockInfo = getStockInfo(ticker);

            String endDate = LocalDate.now().toString();
            String startDate = LocalDate.now().minusMonths(6).toString();
            TechnicalIndicatorsVO indicators = getTechnicalIndicators(ticker, startDate, endDate);

            FundamentalDataVO fundamentals = null;
            try {
                fundamentals = getFundamentalData(ticker);
            } catch (Exception e) {
                log.warn("获取基本面数据失败（情绪推导将降级），可能是无专业版权限或股票数据缺失: ticker={}, error={}",
                        ticker, e.getMessage());
            }

            Double shortTerm = deriveShortTermSentiment(stockInfo, indicators);
            Double analyst = deriveAnalystScore(fundamentals);
            Double overall = (analyst != null) ? round(analyst * 0.6 + shortTerm * 0.4) : shortTerm;

            Double bullRatio = deriveBullRatio(analyst);
            Double bearRatio = deriveBearRatio(analyst);
            Integer fearGreedIndex = deriveFearGreedIndex(overall);

            return SentimentDataVO.builder()
                    .overallScore(overall)
                    .socialMediaScore(0.0)
                    .newsScore(0.0)
                    .analystScore(analyst)
                    .shortTermScore(shortTerm)
                    .mediumTermScore(overall)
                    .longTermScore(overall)
                    .bullRatio(bullRatio)
                    .bearRatio(bearRatio)
                    .socialBuzz(50.0)
                    .fearGreedIndex(fearGreedIndex)
                    .platformSentiments(null)
                    .institutionalHoldingChange(null)
                    .build();
        } catch (Exception e) {
            log.error("获取情绪数据失败: ticker={}, error={}", ticker, e.getMessage());
            throw new RuntimeException("获取情绪数据失败: " + ticker, e);
        }
    }

    @Override
    public List<StockSearchResultVO> searchByName(String name) {
        try {
            List<TushareStockBasicDTO> data = apiClient.callGeneric(
                    TushareStockBasicDTO.class, "stock_basic",
                    Map.of("name", name, "list_status", "L"),
                    "ts_code,symbol,name,exchange,market");

            if (data.isEmpty()) {
                log.warn("根据名称搜索股票未找到结果: name={}", name);
                return Collections.emptyList();
            }

            return data.stream()
                    .map(dto -> StockSearchResultVO.builder()
                            .ticker(dto.getSymbol())
                            .name(dto.getName())
                            .exchange(dto.getExchange())
                            .market(dto.getMarket())
                            .tsCode(dto.getTsCode())
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("根据名称搜索股票失败: name={}, error={}", name, e.getMessage());
            return Collections.emptyList();
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
        if (normalized.matches("^[0-9]{6}\\.(SH|SZ|BJ)$")) {
            return normalized;
        }
        if (!normalized.matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("非 A股 ticker: " + ticker + "（请输入 6 位股票代码，如 603986）");
        }
        char first = normalized.charAt(0);
        if (first == '0' || first == '1' || first == '2' || first == '3') {
            return normalized + ".SZ";
        } else if (first == '4' || first == '8' || first == '9') {
            return normalized + ".BJ";
        } else {
            return normalized + ".SH";
        }
    }

    private String inferExchange(String ticker) {
        if (ticker == null || ticker.isEmpty()) return "UNKNOWN";
        char first = ticker.charAt(0);
        if (first == '0' || first == '1' || first == '2' || first == '3') {
            return "SZSE";
        } else if (first == '4' || first == '8' || first == '9') {
            return "BSE";
        } else {
            return "SSE";
        }
    }

    private String inferMarket(String ticker) {
        if (ticker == null || ticker.isEmpty()) return null;
        char first = ticker.charAt(0);
        if (first == '0' || first == '1' || first == '2') {
            return "主板";
        } else if (first == '3') {
            return "创业板";
        } else if (first == '4' || first == '8' || first == '9') {
            return "北交所";
        } else {
            return "主板";
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

    private OHLCVBarVO toOHLCVBar(TushareDailyDTO dto) {
        return OHLCVBarVO.builder()
                .date(dto.getTradeDateFormatted())
                .open(dto.getOpen())
                .high(dto.getHigh())
                .low(dto.getLow())
                .close(dto.getClose())
                .volume(dto.getVol())
                .adjustedClose(dto.getClose())
                .build();
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
            score += (pe < 15 ? 0.3 : pe > 30 ? -0.3 : 0.0);
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
