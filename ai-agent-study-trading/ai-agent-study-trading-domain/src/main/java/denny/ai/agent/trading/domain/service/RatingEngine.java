package denny.ai.agent.trading.domain.service;

import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.domain.config.TradingAgentProperties;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 评分量化引擎。
 * <p>
 * 职责：
 * <ol>
 *   <li>汇总 4 个分析师的 rating（1-5 分），计算综合评分</li>
 *   <li>基于 {@link TradingAgentProperties#rating} 的阈值触发交易决策</li>
 *   <li>置信度判定：综合评分 &ge; 4.0 → HIGH，2.5~4.0 → MEDIUM，&lt; 2.5 → LOW</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RatingEngine {

    private final TradingAgentProperties properties;

    /**
     * 计算综合评分及决策。
     *
     * @param context 交易上下文（含各分析师报告）
     * @return 评分结果
     */
    public RatingResult calculate(TradingContextVO context) {
        // 1. 收集各维度评分
        List<AnalystRating> analystRatings = new ArrayList<>();

        if (context.getFundamentalReport() != null && context.getFundamentalReport().getRating() > 0) {
            analystRatings.add(new AnalystRating("FUNDAMENTAL", context.getFundamentalReport().getRating()));
        }
        if (context.getTechnicalReport() != null && context.getTechnicalReport().getRating() > 0) {
            analystRatings.add(new AnalystRating("TECHNICAL", context.getTechnicalReport().getRating()));
        }
        if (context.getSentimentReport() != null && context.getSentimentReport().getRating() > 0) {
            analystRatings.add(new AnalystRating("SENTIMENT", context.getSentimentReport().getRating()));
        }
        if (context.getNewsReport() != null && context.getNewsReport().getRating() > 0) {
            analystRatings.add(new AnalystRating("NEWS", context.getNewsReport().getRating()));
        }

        if (analystRatings.isEmpty()) {
            log.warn("No analyst ratings available for rating calculation");
            return RatingResult.builder()
                    .overallRating(0.0)
                    .decision("HOLD")
                    .confidence("LOW")
                    .analystRatings(List.of())
                    .build();
        }

        // 2. 计算综合评分（等权平均）
        double total = analystRatings.stream()
                .mapToInt(AnalystRating::getRating)
                .sum();
        double overallRating = total / analystRatings.size();

        // 3. 辩论调整（辩论综合评分映射到 -2~+2 范围，叠加到综合评分）
        double debateAdjustment = 0.0;
        if (context.getInvestmentDebate() != null && context.getInvestmentDebate().getOverallScore() != null) {
            double debateScore = context.getInvestmentDebate().getOverallScore();
            // 将 -2~+2 映射到 -0.5~+0.5，加到综合评分
            debateAdjustment = debateScore * 0.25;
        }

        double adjustedRating = overallRating + debateAdjustment;
        adjustedRating = Math.max(1.0, Math.min(5.0, adjustedRating));

        // 4. 风险评分调整
        if (context.getRiskDebate() != null && context.getRiskDebate().getRiskScore() != null) {
            double riskScore = context.getRiskDebate().getRiskScore();
            // 风险评分 1（高风险）~5（低风险），映射到 -0.5~+0.2
            double riskAdjustment = (riskScore - 3.0) * 0.1;
            adjustedRating = Math.max(1.0, Math.min(5.0, adjustedRating + riskAdjustment));
        }

        // 5. 基于阈值判定决策
        String decision = decide(adjustedRating);

        // 6. 置信度判定
        String confidence = determineConfidence(adjustedRating, analystRatings.size());

        log.info("RatingEngine: overallRating={}, adjustedRating={}, decision={}, confidence={}, analystCount={}",
                String.format("%.2f", overallRating),
                String.format("%.2f", adjustedRating),
                decision,
                confidence,
                analystRatings.size());

        return RatingResult.builder()
                .overallRating(round(overallRating))
                .adjustedRating(round(adjustedRating))
                .decision(decision)
                .confidence(confidence)
                .analystRatings(analystRatings)
                .build();
    }

    /**
     * 基于综合评分触发决策。
     */
    private String decide(double rating) {
        if (rating >= properties.getRating().getBuyThreshold()) {
            return "BUY";
        } else if (rating <= properties.getRating().getSellThreshold()) {
            return "SELL";
        } else {
            return "HOLD";
        }
    }

    /**
     * 判定置信度。
     * <ul>
     *   <li>HIGH：综合评分 &ge; 4.0</li>
     *   <li>MEDIUM：综合评分 2.5 ~ 4.0</li>
     *   <li>LOW：综合评分 &lt; 2.5，或分析师数量 &lt; 2</li>
     * </ul>
     */
    private String determineConfidence(double rating, int analystCount) {
        if (analystCount < 2) {
            return "LOW";
        }
        if (rating >= 4.0) {
            return "HIGH";
        } else if (rating >= 2.5) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ==================== 结果对象 ====================

    @lombok.Data
    @lombok.Builder
    public static class RatingResult {
        /**
         * 原始综合评分（1-5）
         */
        private double overallRating;

        /**
         * 调整后评分（含辩论和风险调整）
         */
        private double adjustedRating;

        /**
         * 决策：BUY / SELL / HOLD
         */
        private String decision;

        /**
         * 置信度：HIGH / MEDIUM / LOW
         */
        private String confidence;

        /**
         * 各分析师评分详情
         */
        private List<AnalystRating> analystRatings;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class AnalystRating {
        private String type;
        private int rating;
    }
}
