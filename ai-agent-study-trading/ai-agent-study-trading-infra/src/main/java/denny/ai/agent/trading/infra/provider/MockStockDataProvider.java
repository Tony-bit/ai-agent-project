package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Mock 股票数据 Provider，用于 Phase 1-5 开发调试。
 * <p>
 * 返回预定义的硬编码数据，不依赖网络。Phase 6 替换为 {@code YahooFinanceStockDataProvider}。
 */
public class MockStockDataProvider implements IStockDataProvider {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public StockInfoVO getStockInfo(String ticker) {
        return switch (ticker.toUpperCase()) {
            case "NVDA" -> StockInfoVO.builder()
                    .ticker("NVDA")
                    .name("NVIDIA Corporation")
                    .exchange("NASDAQ")
                    .currentPrice(new BigDecimal("875.40"))
                    .peRatio(65.3)
                    .pbRatio(55.2)
                    .marketCap(new BigDecimal("2150.0"))
                    .volume(42_500_000L)
                    .week52High(new BigDecimal("974.00"))
                    .week52Low(new BigDecimal("373.53"))
                    .build();
            case "AAPL" -> StockInfoVO.builder()
                    .ticker("AAPL")
                    .name("Apple Inc.")
                    .exchange("NASDAQ")
                    .currentPrice(new BigDecimal("189.25"))
                    .peRatio(31.2)
                    .pbRatio(47.8)
                    .marketCap(new BigDecimal("2890.0"))
                    .volume(58_200_000L)
                    .week52High(new BigDecimal("199.62"))
                    .week52Low(new BigDecimal("164.08"))
                    .build();
            case "TSLA" -> StockInfoVO.builder()
                    .ticker("TSLA")
                    .name("Tesla, Inc.")
                    .exchange("NASDAQ")
                    .currentPrice(new BigDecimal("242.80"))
                    .peRatio(78.5)
                    .pbRatio(11.3)
                    .marketCap(new BigDecimal("772.0"))
                    .volume(98_700_000L)
                    .week52High(new BigDecimal("278.98"))
                    .week52Low(new BigDecimal("138.80"))
                    .build();
            case "MSFT" -> StockInfoVO.builder()
                    .ticker("MSFT")
                    .name("Microsoft Corporation")
                    .exchange("NASDAQ")
                    .currentPrice(new BigDecimal("415.60"))
                    .peRatio(36.8)
                    .pbRatio(13.4)
                    .marketCap(new BigDecimal("3090.0"))
                    .volume(22_100_000L)
                    .week52High(new BigDecimal("430.82"))
                    .week52Low(new BigDecimal("309.45"))
                    .build();
            default -> StockInfoVO.builder()
                    .ticker(ticker.toUpperCase())
                    .name(ticker.toUpperCase() + " Corp.")
                    .exchange("UNKNOWN")
                    .currentPrice(new BigDecimal("100.00"))
                    .peRatio(20.0)
                    .pbRatio(3.0)
                    .marketCap(new BigDecimal("100.0"))
                    .volume(10_000_000L)
                    .week52High(new BigDecimal("120.00"))
                    .week52Low(new BigDecimal("80.00"))
                    .build();
        };
    }

    @Override
    public List<OHLCVBarVO> getHistoricalBars(String ticker, String startDate, String endDate) {
        List<OHLCVBarVO> bars = new ArrayList<>();
        LocalDate start = LocalDate.parse(startDate, DATE_FMT);
        LocalDate end = LocalDate.parse(endDate, DATE_FMT);
        BigDecimal basePrice = getStockInfo(ticker).getCurrentPrice();
        BigDecimal price = basePrice.multiply(new BigDecimal("0.95"));

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (date.getDayOfWeek().getValue() > 5) continue;
            BigDecimal delta = BigDecimal.valueOf(Math.random() * 8 - 4);
            BigDecimal open = price.add(delta.multiply(new BigDecimal("0.5")));
            BigDecimal close = price.add(delta);
            BigDecimal high = open.max(close).add(BigDecimal.valueOf(Math.random() * 3));
            BigDecimal low = open.min(close).subtract(BigDecimal.valueOf(Math.random() * 3));
            long vol = (long) (30_000_000L + Math.random() * 50_000_000L);

            bars.add(OHLCVBarVO.builder()
                    .date(date.format(DATE_FMT))
                    .open(open.setScale(2, java.math.RoundingMode.HALF_UP))
                    .high(high.setScale(2, java.math.RoundingMode.HALF_UP))
                    .low(low.setScale(2, java.math.RoundingMode.HALF_UP))
                    .close(close.setScale(2, java.math.RoundingMode.HALF_UP))
                    .volume(vol)
                    .adjustedClose(close.setScale(2, java.math.RoundingMode.HALF_UP))
                    .build());
            price = close;
        }
        return bars;
    }

    @Override
    public TechnicalIndicatorsVO getTechnicalIndicators(String ticker, String startDate, String endDate) {
        BigDecimal price = getStockInfo(ticker).getCurrentPrice();
        return TechnicalIndicatorsVO.builder()
                .ma5(price.multiply(new BigDecimal("0.98")))
                .ma10(price.multiply(new BigDecimal("0.96")))
                .ma20(price.multiply(new BigDecimal("0.94")))
                .ma60(price.multiply(new BigDecimal("0.90")))
                .ma120(price.multiply(new BigDecimal("0.85")))
                .macd(new BigDecimal("8.5"))
                .macdSignal(new BigDecimal("6.2"))
                .macdHistogram(new BigDecimal("2.3"))
                .rsi6(58.5)
                .rsi12(55.2)
                .rsi24(52.8)
                .k(62.3)
                .d(58.7)
                .j(69.5)
                .bollUpper(price.multiply(new BigDecimal("1.05")))
                .bollMiddle(price.multiply(new BigDecimal("1.00")))
                .bollLower(price.multiply(new BigDecimal("0.95")))
                .volumeRatio(1.2)
                .volumeMa5(BigDecimal.valueOf(45_000_000L))
                .atr(new BigDecimal("12.5"))
                .adx(25.3)
                .build();
    }

    @Override
    public FundamentalDataVO getFundamentalData(String ticker) {
        return switch (ticker.toUpperCase()) {
            case "NVDA" -> FundamentalDataVO.builder()
                    .peRatio(65.3).pbRatio(55.2).psRatio(35.1).pegRatio(1.2)
                    .roe(63.8).roa(29.5).grossMargin(74.5).netMargin(52.9)
                    .revenue(BigDecimal.valueOf(60_922)).netIncome(BigDecimal.valueOf(32_244))
                    .totalAssets(BigDecimal.valueOf(65_373)).totalDebt(BigDecimal.valueOf(14_552))
                    .bookValuePerShare(BigDecimal.valueOf(26.17)).eps(BigDecimal.valueOf(12.92)).dps(BigDecimal.valueOf(0.16))
                    .revenueGrowth(12.5).earningsGrowth(18.5).netIncomeGrowth(17.6)
                    .operatingCashFlow(BigDecimal.valueOf(36_486)).freeCashFlow(BigDecimal.valueOf(32_244))
                    .debtToAssets(22.3).currentRatio(4.13)
                    .marketCap(BigDecimal.valueOf(2_150_000)).dividendYield(0.0002)
                    .build();
            case "AAPL" -> FundamentalDataVO.builder()
                    .peRatio(31.2).pbRatio(47.8).psRatio(7.9).pegRatio(3.1)
                    .roe(156.7).roa(24.2).grossMargin(46.9).netMargin(24.2)
                    .revenue(BigDecimal.valueOf(385_606)).netIncome(BigDecimal.valueOf(97_386))
                    .totalAssets(BigDecimal.valueOf(352_583)).totalDebt(BigDecimal.valueOf(108_987))
                    .bookValuePerShare(BigDecimal.valueOf(4.27)).eps(BigDecimal.valueOf(6.14)).dps(BigDecimal.valueOf(0.99))
                    .revenueGrowth(4.0).earningsGrowth(11.5).netIncomeGrowth(11.4)
                    .operatingCashFlow(BigDecimal.valueOf(118_254)).freeCashFlow(BigDecimal.valueOf(96_543))
                    .debtToAssets(30.9).currentRatio(0.99)
                    .marketCap(BigDecimal.valueOf(2_890_000)).dividendYield(0.0052)
                    .build();
            case "TSLA" -> FundamentalDataVO.builder()
                    .peRatio(78.5).pbRatio(11.3).psRatio(7.2).pegRatio(2.8)
                    .roe(24.3).roa(8.9).grossMargin(17.7).netMargin(15.2)
                    .revenue(BigDecimal.valueOf(96_773)).netIncome(BigDecimal.valueOf(7_893))
                    .totalAssets(BigDecimal.valueOf(106_618)).totalDebt(BigDecimal.valueOf(43_377))
                    .bookValuePerShare(BigDecimal.valueOf(23.89)).eps(BigDecimal.valueOf(2.51)).dps(BigDecimal.ZERO)
                    .revenueGrowth(3.0).earningsGrowth(-23.0).netIncomeGrowth(-23.9)
                    .operatingCashFlow(BigDecimal.valueOf(13_251)).freeCashFlow(BigDecimal.valueOf(2_064))
                    .debtToAssets(40.7).currentRatio(1.86)
                    .marketCap(BigDecimal.valueOf(772_000)).dividendYield(0.0)
                    .build();
            default -> FundamentalDataVO.builder()
                    .peRatio(20.0).pbRatio(3.0).psRatio(2.0).pegRatio(1.5)
                    .roe(15.0).roa(8.0).grossMargin(35.0).netMargin(10.0)
                    .revenue(BigDecimal.valueOf(10_000)).netIncome(BigDecimal.valueOf(1_000))
                    .totalAssets(BigDecimal.valueOf(50_000)).totalDebt(BigDecimal.valueOf(20_000))
                    .bookValuePerShare(BigDecimal.valueOf(30.00)).eps(BigDecimal.valueOf(3.00)).dps(BigDecimal.valueOf(0.50))
                    .revenueGrowth(5.0).earningsGrowth(8.0).netIncomeGrowth(7.0)
                    .operatingCashFlow(BigDecimal.valueOf(2_000)).freeCashFlow(BigDecimal.valueOf(1_500))
                    .debtToAssets(40.0).currentRatio(1.5)
                    .marketCap(BigDecimal.valueOf(100_000)).dividendYield(0.02)
                    .build();
        };
    }

    @Override
    public List<NewsItemVO> getNews(String ticker, int limit) {
        List<NewsItemVO> news = Arrays.asList(
                NewsItemVO.builder()
                        .title(ticker + " 公布季度财报，营收超预期")
                        .source("Reuters")
                        .publishTime(LocalDate.now().minusDays(1).format(DATE_FMT) + " 08:30")
                        .summary("该公司最新季度财报显示，营收和利润均超出分析师预期，受此消息提振，股价盘前上涨约2%。")
                        .url("https://example.com/news/1")
                        .relatedTickers(new String[]{ticker})
                        .sentimentScore(0.75)
                        .build(),
                NewsItemVO.builder()
                        .title(ticker + " 发布新产品线，进军新市场")
                        .source("Bloomberg")
                        .publishTime(LocalDate.now().minusDays(2).format(DATE_FMT) + " 14:00")
                        .summary("公司宣布推出全新产品线，战略布局新兴市场。分析师认为此举有望带来新的增长动力。")
                        .url("https://example.com/news/2")
                        .relatedTickers(new String[]{ticker})
                        .sentimentScore(0.65)
                        .build(),
                NewsItemVO.builder()
                        .title(ticker + " 面临监管审查，市场情绪谨慎")
                        .source("Financial Times")
                        .publishTime(LocalDate.now().minusDays(3).format(DATE_FMT) + " 10:15")
                        .summary("监管部门启动对公司的反垄断调查，股价短期承压，但机构投资者普遍维持评级不变。")
                        .url("https://example.com/news/3")
                        .relatedTickers(new String[]{ticker})
                        .sentimentScore(-0.30)
                        .build(),
                NewsItemVO.builder()
                        .title(ticker + " 宣布股票回购计划，规模创新高")
                        .source("CNBC")
                        .publishTime(LocalDate.now().minusDays(4).format(DATE_FMT) + " 09:00")
                        .summary("公司董事会批准了100亿美元股票回购计划，表明管理层对公司长期发展充满信心。")
                        .url("https://example.com/news/4")
                        .relatedTickers(new String[]{ticker})
                        .sentimentScore(0.80)
                        .build(),
                NewsItemVO.builder()
                        .title(ticker + " 首席财务官离职，引发市场关注")
                        .source("Wall Street Journal")
                        .publishTime(LocalDate.now().minusDays(5).format(DATE_FMT) + " 16:30")
                        .summary("公司首席财务官因个人原因宣布辞职，管理层表示已启动继任者遴选程序。")
                        .url("https://example.com/news/5")
                        .relatedTickers(new String[]{ticker})
                        .sentimentScore(-0.15)
                        .build()
        );
        return news.subList(0, Math.min(limit, news.size()));
    }

    @Override
    public SentimentDataVO getSentiment(String ticker) {
        Map<String, Double> platformMap = Map.of(
                "Twitter", 0.62,
                "Reddit", 0.55,
                "StockTwits", 0.70,
                "Seeking Alpha", 0.45
        );
        return switch (ticker.toUpperCase()) {
            case "NVDA" -> SentimentDataVO.builder()
                    .overallScore(0.68)
                    .socialMediaScore(0.72)
                    .newsScore(0.65)
                    .analystScore(0.70)
                    .shortTermScore(0.75)
                    .mediumTermScore(0.65)
                    .longTermScore(0.62)
                    .bullRatio(0.72)
                    .bearRatio(0.18)
                    .socialBuzz(8.5)
                    .fearGreedIndex(72)
                    .institutionalHoldingChange(0.05)
                    .platformSentiments(platformMap)
                    .build();
            case "TSLA" -> SentimentDataVO.builder()
                    .overallScore(0.35)
                    .socialMediaScore(0.45)
                    .newsScore(0.25)
                    .analystScore(0.40)
                    .shortTermScore(0.30)
                    .mediumTermScore(0.35)
                    .longTermScore(0.42)
                    .bullRatio(0.55)
                    .bearRatio(0.32)
                    .socialBuzz(9.2)
                    .fearGreedIndex(45)
                    .institutionalHoldingChange(-0.03)
                    .platformSentiments(platformMap)
                    .build();
            default -> SentimentDataVO.builder()
                    .overallScore(0.50)
                    .socialMediaScore(0.50)
                    .newsScore(0.48)
                    .analystScore(0.52)
                    .shortTermScore(0.50)
                    .mediumTermScore(0.50)
                    .longTermScore(0.50)
                    .bullRatio(0.50)
                    .bearRatio(0.30)
                    .socialBuzz(5.0)
                    .fearGreedIndex(55)
                    .institutionalHoldingChange(0.01)
                    .platformSentiments(platformMap)
                    .build();
        };
    }

    @Override
    public List<StockSearchResultVO> searchByName(String name) {
        // Mock 数据：返回预定义的几只股票
        Map<String, StockSearchResultVO> mockData = Map.of(
                "药明康德", StockSearchResultVO.builder()
                        .ticker("603259")
                        .name("药明康德")
                        .exchange("SSE")
                        .market("科创板")
                        .tsCode("603259.SH")
                        .build(),
                "贵州茅台", StockSearchResultVO.builder()
                        .ticker("600519")
                        .name("贵州茅台")
                        .exchange("SSE")
                        .market("主板")
                        .tsCode("600519.SH")
                        .build(),
                "宁德时代", StockSearchResultVO.builder()
                        .ticker("300750")
                        .name("宁德时代")
                        .exchange("SZSE")
                        .market("创业板")
                        .tsCode("300750.SZ")
                        .build(),
                "比亚迪", StockSearchResultVO.builder()
                        .ticker("002594")
                        .name("比亚迪")
                        .exchange("SZSE")
                        .market("主板")
                        .tsCode("002594.SZ")
                        .build(),
                "平安银行", StockSearchResultVO.builder()
                        .ticker("000001")
                        .name("平安银行")
                        .exchange("SZSE")
                        .market("主板")
                        .tsCode("000001.SZ")
                        .build()
        );

        // 精确匹配
        if (mockData.containsKey(name)) {
            return List.of(mockData.get(name));
        }

        // 模糊匹配（包含关键词）
        List<StockSearchResultVO> results = new ArrayList<>();
        for (StockSearchResultVO vo : mockData.values()) {
            if (vo.getName().contains(name) || name.contains(vo.getName())) {
                results.add(vo);
            }
        }
        return results;
    }
}
