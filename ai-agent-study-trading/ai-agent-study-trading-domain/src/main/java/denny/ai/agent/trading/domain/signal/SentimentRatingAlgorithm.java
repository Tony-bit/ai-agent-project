package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.SentimentDataVO;
import denny.ai.agent.trading.api.vo.signal.DecisionSignal;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalSource;

public final class SentimentRatingAlgorithm {

    public static final String VERSION = "sentiment-rating-v1";
    private static final String INPUT_VERSION = "authoritative-sentiment-input-v1";

    public SentimentSignals calculate(SentimentDataVO data) {
        if (data == null) {
            return new SentimentSignals(unavailableRating("sentiment data is missing"),
                    DecisionSignal.unavailable(DecisionSignalSource.AUTHORITATIVE_INPUT,
                            INPUT_VERSION, "overallScore is missing"));
        }
        DecisionSignal<Double> sentimentScore = data.getOverallScore() == null
                ? DecisionSignal.unavailable(DecisionSignalSource.AUTHORITATIVE_INPUT,
                        INPUT_VERSION, "overallScore is missing")
                : DecisionSignal.available(data.getOverallScore(), DecisionSignalSource.AUTHORITATIVE_INPUT,
                        INPUT_VERSION);
        int available = count(data.getOverallScore()) + count(data.getFearGreedIndex())
                + count(data.getBullRatio());
        if (available < 2) {
            return new SentimentSignals(
                    unavailableRating("fewer than two sentiment dimensions are available"), sentimentScore);
        }
        int score = 0;
        if (data.getOverallScore() != null) {
            score += data.getOverallScore() > 0.6 ? 3
                    : data.getOverallScore() > 0.4 ? 2
                    : data.getOverallScore() > 0.2 ? 1 : 0;
        }
        if (data.getFearGreedIndex() != null) {
            score += data.getFearGreedIndex() >= 40 && data.getFearGreedIndex() <= 60 ? 2 : 1;
        }
        if (data.getBullRatio() != null && data.getBullRatio() > 0.6) {
            score++;
        }
        int rating = Math.max(1, Math.min(5, Math.floorDiv(score, 2) + 2));
        return new SentimentSignals(
                DecisionSignal.available(rating, DecisionSignalSource.DETERMINISTIC_V3, VERSION),
                sentimentScore);
    }

    private int count(Object value) {
        return value == null ? 0 : 1;
    }

    private DecisionSignal<Integer> unavailableRating(String reason) {
        return DecisionSignal.unavailable(DecisionSignalSource.DETERMINISTIC_V3, VERSION, reason);
    }

    public record SentimentSignals(DecisionSignal<Integer> rating,
                                   DecisionSignal<Double> sentimentScore) {
    }
}
