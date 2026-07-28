package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.TechnicalIndicatorsVO;
import denny.ai.agent.trading.api.vo.signal.DecisionSignal;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalSource;

import java.math.BigDecimal;

public final class TechnicalSignalAlgorithm {

    public static final String VERSION = "technical-signal-v1";

    public TechnicalSignals calculate(TechnicalIndicatorsVO data) {
        if (data == null || data.getRsi6() == null || data.getMacdHistogram() == null
                || data.getMa5() == null || data.getMa20() == null) {
            return unavailable("rsi6, macdHistogram, ma5 and ma20 are all required");
        }
        String trend = trend(data.getMa5(), data.getMa20(), data.getMacdHistogram());
        int score = data.getRsi6() >= 30 && data.getRsi6() <= 70 ? 2 : 1;
        score += data.getMacdHistogram().compareTo(BigDecimal.ZERO) > 0 ? 2 : 1;
        score += data.getMa5().compareTo(data.getMa20()) > 0 ? 2 : 0;
        score += switch (trend) {
            case "UP" -> 2;
            case "SIDEWAYS" -> 1;
            case "DOWN" -> 0;
            default -> throw new IllegalStateException("unexpected trend: " + trend);
        };
        int rating = Math.max(1, Math.min(5, Math.floorDiv(score, 2) + 2));
        return new TechnicalSignals(
                DecisionSignal.available(rating, DecisionSignalSource.DETERMINISTIC_V3, VERSION),
                DecisionSignal.available(trend, DecisionSignalSource.DETERMINISTIC_V3, VERSION));
    }

    private String trend(BigDecimal ma5, BigDecimal ma20, BigDecimal histogram) {
        if (ma5.compareTo(ma20) > 0 && histogram.compareTo(BigDecimal.ZERO) > 0) {
            return "UP";
        }
        if (ma5.compareTo(ma20) < 0 && histogram.compareTo(BigDecimal.ZERO) < 0) {
            return "DOWN";
        }
        return "SIDEWAYS";
    }

    private TechnicalSignals unavailable(String reason) {
        DecisionSignal<Integer> rating = DecisionSignal.unavailable(
                DecisionSignalSource.DETERMINISTIC_V3, VERSION, reason);
        DecisionSignal<String> trend = DecisionSignal.unavailable(
                DecisionSignalSource.DETERMINISTIC_V3, VERSION, reason);
        return new TechnicalSignals(rating, trend);
    }

    public record TechnicalSignals(DecisionSignal<Integer> rating,
                                   DecisionSignal<String> trendSignal) {
    }
}
