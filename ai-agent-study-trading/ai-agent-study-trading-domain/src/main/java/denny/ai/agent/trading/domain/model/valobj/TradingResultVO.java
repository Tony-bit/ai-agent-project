package denny.ai.agent.trading.domain.model.valobj;

import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import denny.ai.agent.trading.api.vo.signal.DecisionSignal;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalSet;
import denny.ai.agent.trading.domain.signal.V2DecisionSignalFactory;
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
    private String outputMode;
    private DecisionSignalSet decisionSignals;
    private DecisionSignalSet shadowDecisionSignals;
    private int availableAnalystCount;
    private List<String> unavailableReasons;

    private FundamentalSummary fundamentalReport;
    private TechnicalSummary technicalReport;
    private SentimentSummary sentimentReport;
    private NewsSummary newsReport;
    private InvestmentDebateSummary investmentDebate;
    private InvestmentPlanSummary investmentPlan;
    private RiskDebateSummary riskDebate;
    private FinalDecisionSummary finalDecision;

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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskDebateSummary {
        private Integer riskScore;
        private String riskLevel;
        private List<String> riskItems;
        private List<String> mitigations;
        private List<String> aggressiveHistory;
        private List<String> conservativeHistory;
        private List<String> neutralHistory;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinalDecisionSummary {
        private String decision;
        private String confidence;
        private Double overallRating;
        private String reasoning;
        private List<String> warnings;
    }

    public static TradingResultVO from(TradingContextVO context) {
        TradingResultVOBuilder builder = TradingResultVO.builder();
        DecisionSignalSet signals = context.getDecisionSignals() == null
                ? new V2DecisionSignalFactory().fromReports(context) : context.getDecisionSignals();

        if (context.getTargetContext() != null) {
            builder.ticker(context.getTargetContext().targetId())
                    .name(context.getTargetContext().stockName());
        }
        if (context.getStockInfo() != null) {
            StockInfoVO stock = context.getStockInfo();
            builder.exchange(stock.getExchange())
                    .currentPrice(stock.getCurrentPrice());
        }

        builder.generatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .outputMode(context.getOutputMode() == null ? "STRICT_V2" : context.getOutputMode())
                .decisionSignals(signals)
                .shadowDecisionSignals(context.getShadowDecisionSignals())
                .availableAnalystCount(signals.availableAnalystCount())
                .unavailableReasons(unavailableReasons(signals));

        if (context.getFundamentalReport() != null) {
            FundamentalReportVO r = context.getFundamentalReport();
            builder.fundamentalReport(FundamentalSummary.builder()
                    .rating(signals.fundamentalRating().value())
                    .keyFindings(r.getKeyFindings())
                    .riskWarnings(r.getRiskWarnings())
                    .summary(r.getSummary())
                    .build());
        }

        if (context.getTechnicalReport() != null) {
            TechnicalReportVO r = context.getTechnicalReport();
            builder.technicalReport(TechnicalSummary.builder()
                    .rating(signals.technicalRating().value())
                    .trendSignal(signals.technicalTrendSignal().value())
                    .keyPatterns(r.getKeyPatterns())
                    .summary(r.getSummary())
                    .build());
        }

        if (context.getSentimentReport() != null) {
            SentimentReportVO r = context.getSentimentReport();
            builder.sentimentReport(SentimentSummary.builder()
                    .rating(signals.sentimentRating().value())
                    .sentimentScore(signals.sentimentScore().value())
                    .keySentiments(r.getKeySentiments())
                    .summary(r.getSummary())
                    .build());
        }

        if (context.getNewsReport() != null) {
            NewsReportVO r = context.getNewsReport();
            builder.newsReport(NewsSummary.builder()
                    .rating(signals.newsRating().value())
                    .overallSentiment(signals.newsOverallSentiment().value())
                    .newsThemes(r.getNewsThemes())
                    .summary(r.getSummary())
                    .build());
        }

        if (context.getInvestmentDebate() != null) {
            TradingContextVO.InvestmentDebateVO d = context.getInvestmentDebate();
            builder.investmentDebate(InvestmentDebateSummary.builder()
                    .overallScore(signals.debateOverallScore().value())
                    .conclusion(d.getConclusion())
                    .bullArguments(d.getBullHistory().stream()
                            .map(denny.ai.agent.trading.api.vo.NarrativeNodeResult::rawText)
                            .toList())
                    .bearArguments(d.getBearHistory().stream()
                            .map(denny.ai.agent.trading.api.vo.NarrativeNodeResult::rawText)
                            .toList())
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

        if (context.getRiskDebate() != null) {
            TradingContextVO.RiskDebateVO r = context.getRiskDebate();
            builder.riskDebate(RiskDebateSummary.builder()
                    .riskScore(signals.riskScore().value())
                    .riskLevel(r.getRiskLevel())
                    .riskItems(r.getRiskItems())
                    .mitigations(r.getMitigations())
                    .aggressiveHistory(r.getAggressiveHistory().stream()
                            .map(denny.ai.agent.trading.api.vo.NarrativeNodeResult::rawText).toList())
                    .conservativeHistory(r.getConservativeHistory().stream()
                            .map(denny.ai.agent.trading.api.vo.NarrativeNodeResult::rawText).toList())
                    .neutralHistory(r.getNeutralHistory().stream()
                            .map(denny.ai.agent.trading.api.vo.NarrativeNodeResult::rawText).toList())
                    .build());
        }

        if (context.getFinalDecision() != null) {
            TradingContextVO.FinalTradeDecisionVO d = context.getFinalDecision();
            builder.finalDecision(FinalDecisionSummary.builder()
                    .decision(d.getDecision())
                    .confidence(d.getConfidence())
                    .overallRating(d.getOverallRating())
                    .reasoning(d.getReasoning())
                    .warnings(d.getWarnings())
                    .build());
        }

        return builder.build();
    }

    private static List<String> unavailableReasons(DecisionSignalSet signals) {
        List<String> reasons = new ArrayList<>();
        addReason(reasons, "fundamentalRating", signals.fundamentalRating());
        addReason(reasons, "technicalRating", signals.technicalRating());
        addReason(reasons, "technicalTrendSignal", signals.technicalTrendSignal());
        addReason(reasons, "sentimentRating", signals.sentimentRating());
        addReason(reasons, "sentimentScore", signals.sentimentScore());
        addReason(reasons, "newsRating", signals.newsRating());
        addReason(reasons, "newsOverallSentiment", signals.newsOverallSentiment());
        addReason(reasons, "debateOverallScore", signals.debateOverallScore());
        addReason(reasons, "riskScore", signals.riskScore());
        return List.copyOf(reasons);
    }

    private static void addReason(List<String> reasons, String name, DecisionSignal<?> signal) {
        if (!signal.isAvailable()) {
            reasons.add(name + ": " + signal.reason());
        }
    }
}
