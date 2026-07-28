package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.NewsItemVO;
import denny.ai.agent.trading.api.vo.signal.DecisionSignal;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalSource;

import java.util.List;

public final class NewsRatingAlgorithm {

    public static final String VERSION = "news-rating-v1";

    public NewsSignals calculate(List<NewsItemVO> items) {
        if (items == null) {
            return unavailable("news items are missing");
        }
        double average = items.stream()
                .filter(java.util.Objects::nonNull)
                .map(NewsItemVO::getSentimentScore)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(Double.NaN);
        if (Double.isNaN(average)) {
            return unavailable("no news item has a sentiment score");
        }
        int rating = average > 0.5 ? 5 : average > 0.2 ? 4
                : average > -0.2 ? 3 : average > -0.5 ? 2 : 1;
        String sentiment = average > 0.3 ? "positive" : average > -0.3 ? "mixed" : "negative";
        return new NewsSignals(
                DecisionSignal.available(rating, DecisionSignalSource.DETERMINISTIC_V3, VERSION),
                DecisionSignal.available(sentiment, DecisionSignalSource.DETERMINISTIC_V3, VERSION));
    }

    private NewsSignals unavailable(String reason) {
        return new NewsSignals(
                DecisionSignal.unavailable(DecisionSignalSource.DETERMINISTIC_V3, VERSION, reason),
                DecisionSignal.unavailable(DecisionSignalSource.DETERMINISTIC_V3, VERSION, reason));
    }

    public record NewsSignals(DecisionSignal<Integer> rating,
                              DecisionSignal<String> overallSentiment) {
    }
}
