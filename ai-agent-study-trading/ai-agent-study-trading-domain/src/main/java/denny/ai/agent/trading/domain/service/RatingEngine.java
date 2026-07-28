package denny.ai.agent.trading.domain.service;

import denny.ai.agent.trading.api.vo.*;
import denny.ai.agent.trading.domain.config.TradingAgentProperties;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import denny.ai.agent.trading.api.vo.signal.DecisionSignal;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        DecisionSignalSet signals = Objects.requireNonNull(context.getDecisionSignals(),
                "decisionSignals must be populated before rating calculation");
        List<AnalystRating> analystRatings = new ArrayList<>();
        addRating(analystRatings, "FUNDAMENTAL", signals.fundamentalRating());
        addRating(analystRatings, "TECHNICAL", signals.technicalRating());
        addRating(analystRatings, "SENTIMENT", signals.sentimentRating());
        addRating(analystRatings, "NEWS", signals.newsRating());

        if (analystRatings.isEmpty()) {
            log.warn("No analyst ratings available for rating calculation");
            return RatingResult.builder()
                    .overallRating(null)
                    .adjustedRating(null)
                    .decision("HOLD")
                    .confidence("LOW")
                    .analystRatings(List.of())
                    .unavailableReasons(unavailableReasons(signals))
                    .build();
        }

        // 2. 计算综合评分（等权平均）
        double total = analystRatings.stream()
                .mapToInt(AnalystRating::getRating)
                .sum();
        double overallRating = total / analystRatings.size();

        // 3. 辩论调整（辩论综合评分映射到 -2~+2 范围，叠加到综合评分）
        double debateAdjustment = 0.0;
        if (signals.debateOverallScore().isAvailable()) {
            double debateScore = signals.debateOverallScore().value();
            // 将 -2~+2 映射到 -0.5~+0.5，加到综合评分
            debateAdjustment = debateScore * 0.25;
        }

        double adjustedRating = overallRating + debateAdjustment;
        adjustedRating = Math.max(1.0, Math.min(5.0, adjustedRating));

        // 4. 风险评分调整
        if (signals.riskScore().isAvailable()) {
            double riskScore = signals.riskScore().value();
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
                .unavailableReasons(unavailableReasons(signals))
                .build();
    }

    private void addRating(List<AnalystRating> ratings,
                           String type,
                           DecisionSignal<Integer> signal) {
        if (signal.isAvailable()) {
            ratings.add(new AnalystRating(type, signal.value()));
        }
    }

    private List<String> unavailableReasons(DecisionSignalSet signals) {
        List<String> reasons = new ArrayList<>();
        addReason(reasons, "FUNDAMENTAL", signals.fundamentalRating());
        addReason(reasons, "TECHNICAL", signals.technicalRating());
        addReason(reasons, "SENTIMENT", signals.sentimentRating());
        addReason(reasons, "NEWS", signals.newsRating());
        addReason(reasons, "DEBATE", signals.debateOverallScore());
        addReason(reasons, "RISK", signals.riskScore());
        return List.copyOf(reasons);
    }

    private void addReason(List<String> reasons, String name, DecisionSignal<?> signal) {
        if (!signal.isAvailable()) {
            reasons.add(name + ": " + signal.reason());
        }
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
        private Double overallRating;

        /**
         * 调整后评分（含辩论和风险调整）
         */
        private Double adjustedRating;

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

        private List<String> unavailableReasons;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class AnalystRating {
        private String type;
        private int rating;
    }
}
