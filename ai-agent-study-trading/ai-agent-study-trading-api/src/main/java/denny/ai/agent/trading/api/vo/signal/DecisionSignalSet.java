package denny.ai.agent.trading.api.vo.signal;

import java.util.List;
import java.util.Objects;

public record DecisionSignalSet(
        DecisionSignal<Integer> fundamentalRating,
        DecisionSignal<Integer> technicalRating,
        DecisionSignal<String> technicalTrendSignal,
        DecisionSignal<Integer> sentimentRating,
        DecisionSignal<Double> sentimentScore,
        DecisionSignal<Integer> newsRating,
        DecisionSignal<String> newsOverallSentiment,
        DecisionSignal<Double> debateOverallScore,
        DecisionSignal<Integer> riskScore
) {

    public DecisionSignalSet {
        Objects.requireNonNull(fundamentalRating, "fundamentalRating");
        Objects.requireNonNull(technicalRating, "technicalRating");
        Objects.requireNonNull(technicalTrendSignal, "technicalTrendSignal");
        Objects.requireNonNull(sentimentRating, "sentimentRating");
        Objects.requireNonNull(sentimentScore, "sentimentScore");
        Objects.requireNonNull(newsRating, "newsRating");
        Objects.requireNonNull(newsOverallSentiment, "newsOverallSentiment");
        Objects.requireNonNull(debateOverallScore, "debateOverallScore");
        Objects.requireNonNull(riskScore, "riskScore");
    }

    public List<DecisionSignal<Integer>> analystRatings() {
        return List.of(fundamentalRating, technicalRating, sentimentRating, newsRating);
    }

    public int availableAnalystCount() {
        return (int) analystRatings().stream().filter(DecisionSignal::isAvailable).count();
    }

    public DecisionSignalSet withDebateOverallScore(DecisionSignal<Double> value) {
        return new DecisionSignalSet(fundamentalRating, technicalRating, technicalTrendSignal,
                sentimentRating, sentimentScore, newsRating, newsOverallSentiment,
                value, riskScore);
    }

    public DecisionSignalSet withRiskScore(DecisionSignal<Integer> value) {
        return new DecisionSignalSet(fundamentalRating, technicalRating, technicalTrendSignal,
                sentimentRating, sentimentScore, newsRating, newsOverallSentiment,
                debateOverallScore, value);
    }
}
