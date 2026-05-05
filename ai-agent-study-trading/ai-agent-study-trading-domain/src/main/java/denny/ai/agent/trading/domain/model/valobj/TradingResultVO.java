package denny.ai.agent.trading.domain.model.valobj;

import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 交易结果值对象，用于导出到 Markdown 文档。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingResultVO {

    private String ticker;
    private String name;
    private String exchange;
    private BigDecimal currentPrice;
    private String generatedAt;

    private FundamentalSummary fundamentalReport;
    private TechnicalSummary technicalReport;
    private SentimentSummary sentimentReport;
    private NewsSummary newsReport;
    private InvestmentDebateSummary investmentDebate;
    private InvestmentPlanSummary investmentPlan;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FundamentalSummary {
        private Integer rating;
        private List<String> keyFindings;
        private List<String> riskWarnings;
        private String summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TechnicalSummary {
        private Integer rating;
        private String trendSignal;
        private List<String> keyPatterns;
        private String summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SentimentSummary {
        private Integer rating;
        private Double sentimentScore;
        private List<String> keySentiments;
        private String summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NewsSummary {
        private Integer rating;
        private String overallSentiment;
        private List<NewsReportVO.NewsThemeVO> newsThemes;
        private String summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvestmentDebateSummary {
        private Double overallScore;
        private String conclusion;
        private List<String> bullArguments;
        private List<String> bearArguments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvestmentPlanSummary {
        private String action;
        private Double positionRatio;
        private String entryPriceRange;
        private String stopLossPrice;
        private String takeProfitPrice;
        private String holdingPeriod;
        private Double riskRewardRatio;
    }

    public static TradingResultVO from(TradingContextVO context) {
        TradingResultVOBuilder builder = TradingResultVO.builder();

        if (context.getStockInfo() != null) {
            StockInfoVO stock = context.getStockInfo();
            builder.ticker(stock.getTicker())
                    .name(stock.getName())
                    .exchange(stock.getExchange())
                    .currentPrice(stock.getCurrentPrice());
        }

        builder.generatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        if (context.getFundamentalReport() != null) {
            FundamentalReportVO r = context.getFundamentalReport();
            builder.fundamentalReport(FundamentalSummary.builder()
                    .rating(r.getRating())
                    .keyFindings(r.getKeyFindings())
                    .riskWarnings(r.getRiskWarnings())
                    .summary(r.getSummary())
                    .build());
        }

        if (context.getTechnicalReport() != null) {
            TechnicalReportVO r = context.getTechnicalReport();
            builder.technicalReport(TechnicalSummary.builder()
                    .rating(r.getRating())
                    .trendSignal(r.getTrendSignal())
                    .keyPatterns(r.getKeyPatterns())
                    .summary(r.getSummary())
                    .build());
        }

        if (context.getSentimentReport() != null) {
            SentimentReportVO r = context.getSentimentReport();
            builder.sentimentReport(SentimentSummary.builder()
                    .rating(r.getRating())
                    .sentimentScore(r.getSentimentScore())
                    .keySentiments(r.getKeySentiments())
                    .summary(r.getSummary())
                    .build());
        }

        if (context.getNewsReport() != null) {
            NewsReportVO r = context.getNewsReport();
            builder.newsReport(NewsSummary.builder()
                    .rating(r.getRating())
                    .overallSentiment(r.getOverallSentiment())
                    .newsThemes(r.getNewsThemes())
                    .summary(r.getSummary())
                    .build());
        }

        if (context.getInvestmentDebate() != null) {
            TradingContextVO.InvestmentDebateVO d = context.getInvestmentDebate();
            builder.investmentDebate(InvestmentDebateSummary.builder()
                    .overallScore(d.getOverallScore())
                    .conclusion(d.getConclusion())
                    .bullArguments(d.getBullHistory())
                    .bearArguments(d.getBearHistory())
                    .build());
        }

        if (context.getInvestmentPlan() != null) {
            TradingContextVO.InvestmentPlanVO p = context.getInvestmentPlan();
            builder.investmentPlan(InvestmentPlanSummary.builder()
                    .action(p.getAction())
                    .positionRatio(p.getPositionRatio())
                    .entryPriceRange(p.getEntryPriceRange())
                    .stopLossPrice(p.getStopLossPrice())
                    .takeProfitPrice(p.getTakeProfitPrice())
                    .holdingPeriod(p.getHoldingPeriod())
                    .riskRewardRatio(p.getRiskRewardRatio())
                    .build());
        }

        return builder.build();
    }
}
