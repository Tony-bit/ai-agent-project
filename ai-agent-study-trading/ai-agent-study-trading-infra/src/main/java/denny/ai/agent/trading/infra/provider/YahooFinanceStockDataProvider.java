package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.infra.calculator.TechnicalIndicatorCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import yahoofinance.YahooFinance;
import yahoofinance.histquotes.HistoricalQuote;
import yahoofinance.histquotes.Interval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Yahoo Finance 股票数据 Provider 实现。
 * <p>
 * 从 Yahoo Finance API（com.yahoofinance-api 3.17.0）获取真实股票数据，当 API 不可用时降级到 Mock 数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YahooFinanceStockDataProvider implements IStockDataProvider {

    private final TechnicalIndicatorCalculator indicatorCalculator;

    @Override
    public StockInfoVO getStockInfo(String ticker) {
        try {
            yahoofinance.Stock stock = YahooFinance.get(normalizeTicker(ticker));
            if (stock == null || stock.getQuote() == null) {
                log.warn("Yahoo Finance returned null for {}, falling back to mock", ticker);
                return getMockStockInfo(ticker);
            }

            var quote = stock.getQuote();
            var stats = stock.getStats();
            return StockInfoVO.builder()
                    .ticker(ticker)
                    .name(stock.getName() != null ? stock.getName() : ticker)
                    .exchange(stock.getStockExchange())
                    .currentPrice(quote.getPrice())
                    .peRatio(toDouble(stats.getPe()))
                    .pbRatio(toDouble(stats.getPriceBook()))
                    .marketCap(stats.getMarketCap())
                    .volume(quote.getVolume())
                    .week52High(quote.getYearHigh())
                    .week52Low(quote.getYearLow())
                    .build();
        } catch (Exception e) {
            log.error("Failed to get stock info for {} from Yahoo Finance: {}", ticker, e.getMessage());
            return getMockStockInfo(ticker);
        }
    }

    @Override
    public List<OHLCVBarVO> getHistoricalBars(String ticker, String startDate, String endDate) {
        try {
            yahoofinance.Stock stock = YahooFinance.get(normalizeTicker(ticker));
            if (stock == null) {
                return getMockHistoricalBars(ticker, 365);
            }

            // 使用 Interval.DAILY 获取近1年日线数据
            List<HistoricalQuote> history = stock.getHistory(Interval.DAILY);
            if (history == null || history.isEmpty()) {
                log.warn("No historical data from Yahoo Finance for {}, using mock", ticker);
                return getMockHistoricalBars(ticker, 365);
            }

            return history.stream()
                    .map(this::convertToOHLCV)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to get historical bars for {}: {}", ticker, e.getMessage());
            return getMockHistoricalBars(ticker, 365);
        }
    }

    @Override
    public TechnicalIndicatorsVO getTechnicalIndicators(String ticker, String startDate, String endDate) {
        try {
            List<OHLCVBarVO> bars = getHistoricalBars(ticker, startDate, endDate);
            return indicatorCalculator.calculate(ticker, bars);
        } catch (Exception e) {
            log.error("Failed to calculate technical indicators for {}: {}", ticker, e.getMessage());
            return TechnicalIndicatorsVO.builder().ticker(ticker).build();
        }
    }

    @Override
    public FundamentalDataVO getFundamentalData(String ticker) {
        try {
            yahoofinance.Stock stock = YahooFinance.get(normalizeTicker(ticker));
            if (stock == null) {
                return getMockFundamentalData(ticker);
            }

            var stats = stock.getStats();
            var dividend = stock.getDividend();

            return FundamentalDataVO.builder()
                    // 估值指标（来自 StockStats）
                    .peRatio(toDouble(stats.getPe()))
                    .pbRatio(toDouble(stats.getPriceBook()))
                    .psRatio(toDouble(stats.getPriceSales()))
                    .pegRatio(toDouble(stats.getPeg()))
                    // 盈利能力
                    .roe(toDouble(stats.getRoe()))
                    .eps(stats.getEps())
                    .bookValuePerShare(stats.getBookValuePerShare())
                    // 财务数据
                    .revenue(stats.getRevenue())
                    .marketCap(stats.getMarketCap())
                    // 增长指标
                    .earningsGrowth(toDouble(stats.getEpsEstimateNextYear()))
                    // 股东回报
                    .dividendYield(toDouble(dividend != null ? dividend.getAnnualYield() : null))
                    .dps(dividend != null ? BigDecimal.valueOf(toDouble(dividend.getAnnualYield())) : null)
                    .build();
        } catch (Exception e) {
            log.error("Failed to get fundamental data for {}: {}", ticker, e.getMessage());
            return getMockFundamentalData(ticker);
        }
    }

    @Override
    public List<NewsItemVO> getNews(String ticker, int limit) {
        try {
            yahoofinance.Stock stock = YahooFinance.get(normalizeTicker(ticker));
            if (stock == null) {
                return Collections.emptyList();
            }

            List<yahoofinance.news.StockNews> newsList = stock.getNews();
            if (newsList == null || newsList.isEmpty()) {
                return Collections.emptyList();
            }

            return newsList.stream()
                    .limit(limit > 0 ? limit : 10)
                    .map(n -> NewsItemVO.builder()
                            .title(n.getTitle())
                            .source("Yahoo Finance")
                            .publishTime(n.getPublishTime() != null
                                    ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                                            .format(new java.util.Date(n.getPublishTime())) : null)
                            .summary(n.getTitle())
                            .url(n.getLink())
                            .relatedTickers(new String[]{ticker})
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to get news for {}: {}", ticker, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public SentimentDataVO getSentiment(String ticker) {
        try {
            yahoofinance.Stock stock = YahooFinance.get(normalizeTicker(ticker));
            if (stock == null) {
                return SentimentDataVO.builder().build();
            }

            var quote = stock.getQuote();
            var stats = stock.getStats();

            Double shortTerm = deriveShortTermSentiment(quote);
            Double analyst = deriveAnalystScore(stats);
            Double overall = (analyst != null) ? round(analyst * 0.6 + shortTerm * 0.4) : shortTerm;

            return SentimentDataVO.builder()
                    .overallScore(overall)
                    .analystScore(analyst)
                    .shortTermScore(shortTerm)
                    .mediumTermScore(overall)
                    .longTermScore(overall)
                    .bullRatio(deriveBullRatio(analyst))
                    .bearRatio(deriveBearRatio(analyst))
                    .fearGreedIndex(deriveFearGreedIndex(overall))
                    .institutionalHoldingChange(toDouble(stats.getShortRatio()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to get sentiment for {}: {}", ticker, e.getMessage());
            return SentimentDataVO.builder().build();
        }
    }

    // ==================== 辅助方法 ====================

    private String normalizeTicker(String ticker) {
        if (ticker == null) return ticker;
        String normalized = ticker.trim().toUpperCase();
        // Yahoo Finance 港股需要后缀
        if (!normalized.contains(".") && normalized.matches("^[0-9]{4,6}$")) {
            return normalized + ".HK";
        }
        return normalized;
    }

    private Double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }

    private OHLCVBarVO convertToOHLCV(HistoricalQuote hq) {
        BigDecimal open = hq.getOpen() != null ? hq.getOpen() : BigDecimal.ZERO;
        BigDecimal high = hq.getHigh() != null ? hq.getHigh() : BigDecimal.ZERO;
        BigDecimal low = hq.getLow() != null ? hq.getLow() : BigDecimal.ZERO;
        BigDecimal close = hq.getClose() != null ? hq.getClose() : BigDecimal.ZERO;
        Long volume = hq.getVolume() != null ? hq.getVolume() : 0L;
        BigDecimal adjClose = hq.getAdjClose() != null ? hq.getAdjClose() : close;

        String date = hq.getDate() != null
                ? hq.getDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString()
                : LocalDate.now().toString();

        return OHLCVBarVO.builder()
                .date(date)
                .open(open.setScale(2, RoundingMode.HALF_UP))
                .high(high.setScale(2, RoundingMode.HALF_UP))
                .low(low.setScale(2, RoundingMode.HALF_UP))
                .close(close.setScale(2, RoundingMode.HALF_UP))
                .volume(volume)
                .adjustedClose(adjClose.setScale(2, RoundingMode.HALF_UP))
                .build();
    }

    private Double deriveShortTermSentiment(yahoofinance.quotes.stock.StockQuote quote) {
        if (quote == null) return 0.0;
        try {
            BigDecimal price = quote.getPrice();
            BigDecimal priceAvg50 = quote.getPriceAvg50();
            if (price == null || priceAvg50 == null || priceAvg50.compareTo(BigDecimal.ZERO) <= 0) {
                return 0.0;
            }
            double maScore = (price.subtract(priceAvg50)).divide(priceAvg50, 4, RoundingMode.HALF_UP).doubleValue() * 10;
            maScore = Math.max(-1, Math.min(1, maScore));
            return round(maScore);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private Double deriveAnalystScore(yahoofinance.quotes.stock.StockStats stats) {
        // Yahoo Finance 免费版没有直接的分析师评级数量
        // 用 PE 和 ROE 估算
        if (stats == null) return null;
        try {
            BigDecimal pe = stats.getPe();
            BigDecimal roe = stats.getRoe();
            if (pe == null || pe.compareTo(BigDecimal.ZERO) <= 0) return null;
            double score = 0;
            // PE 合理区间 10-30，低于10偏多，高于30偏空
            score += (pe.doubleValue() < 15 ? 0.3 : pe.doubleValue() > 30 ? -0.3 : 0.0);
            // ROE > 15% 偏多
            if (roe != null && roe.compareTo(BigDecimal.valueOf(15)) > 0) score += 0.3;
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

    // ==================== Mock 降级数据 ====================

    private StockInfoVO getMockStockInfo(String ticker) {
        Map<String, double[]> mockData = getMockData(ticker.toUpperCase());
        double[] data = mockData.getOrDefault(ticker.toUpperCase(), new double[]{100.0, 25.0, 3.5});

        return StockInfoVO.builder()
                .ticker(ticker)
                .name(ticker + " Inc.")
                .exchange("NASDAQ")
                .currentPrice(BigDecimal.valueOf(data[0]))
                .peRatio(data[1])
                .pbRatio(data[2])
                .volume(10000000L)
                .build();
    }

    private List<OHLCVBarVO> getMockHistoricalBars(String ticker, int days) {
        List<OHLCVBarVO> bars = new ArrayList<>();
        Map<String, double[]> mockData = getMockData(ticker.toUpperCase());
        double[] data = mockData.getOrDefault(ticker.toUpperCase(), new double[]{100.0, 25.0, 3.5});
        double price = data[0];
        Random random = new Random(ticker.hashCode());

        for (int i = days; i >= 0; i--) {
            double change = (random.nextDouble() - 0.5) * 5;
            price += change;
            price = Math.max(50, Math.min(200, price));

            bars.add(OHLCVBarVO.builder()
                    .date(LocalDate.now().minusDays(i).toString())
                    .open(BigDecimal.valueOf(price - 0.5).setScale(2, RoundingMode.HALF_UP))
                    .high(BigDecimal.valueOf(price + 1.0).setScale(2, RoundingMode.HALF_UP))
                    .low(BigDecimal.valueOf(price - 1.0).setScale(2, RoundingMode.HALF_UP))
                    .close(BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP))
                    .volume((long) (1000000 + random.nextInt(5000000)))
                    .build());
        }
        return bars;
    }

    private FundamentalDataVO getMockFundamentalData(String ticker) {
        Map<String, double[]> mockData = getMockData(ticker.toUpperCase());
        double[] data = mockData.getOrDefault(ticker.toUpperCase(), new double[]{100.0, 25.0, 3.5});

        return FundamentalDataVO.builder()
                .peRatio(data[1])
                .pbRatio(data[2])
                .marketCap(BigDecimal.valueOf(1000000000000L))
                .debtToEquity(1.5)
                .roe(15.0)
                .build();
    }

    private Map<String, double[]> getMockData(String ticker) {
        Map<String, double[]> mockData = new HashMap<>();
        mockData.put("AAPL", new double[]{175.0, 28.5, 35.2});
        mockData.put("NVDA", new double[]{480.0, 65.0, 42.5});
        mockData.put("TSLA", new double[]{250.0, 55.0, 12.5});
        mockData.put("MSFT", new double[]{380.0, 32.0, 12.5});
        mockData.put("GOOGL", new double[]{140.0, 25.0, 5.8});
        mockData.put("META", new double[]{500.0, 30.0, 8.5});
        return mockData;
    }
}
