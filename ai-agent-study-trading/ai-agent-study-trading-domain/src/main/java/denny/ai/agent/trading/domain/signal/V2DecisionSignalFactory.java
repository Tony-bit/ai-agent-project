package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.signal.DecisionSignal;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalSet;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalSource;
import denny.ai.agent.trading.domain.vo.TradingContextVO;

import java.util.Objects;

public final class V2DecisionSignalFactory {

    public DecisionSignalSet fromReports(TradingContextVO context) {
        Objects.requireNonNull(context, "context");
        return new DecisionSignalSet(
                integer(context.getFundamentalReport() == null ? null
                        : context.getFundamentalReport().getRating(), "fundamental rating is missing"),
                integer(context.getTechnicalReport() == null ? null
                        : context.getTechnicalReport().getRating(), "technical rating is missing"),
                text(context.getTechnicalReport() == null ? null
                        : context.getTechnicalReport().getTrendSignal(), "technical trend is missing"),
                integer(context.getSentimentReport() == null ? null
                        : context.getSentimentReport().getRating(), "sentiment rating is missing"),
                decimal(context.getSentimentReport() == null ? null
                        : context.getSentimentReport().getSentimentScore(), "sentiment score is missing"),
                integer(context.getNewsReport() == null ? null
                        : context.getNewsReport().getRating(), "news rating is missing"),
                text(context.getNewsReport() == null ? null
                        : context.getNewsReport().getOverallSentiment(), "news sentiment is missing"),
                DecisionSignal.unavailable(DecisionSignalSource.LLM_V2, null,
                        "research manager has not completed"),
                DecisionSignal.unavailable(DecisionSignalSource.LLM_V2, null,
                        "risk management has not completed"));
    }

    private DecisionSignal<Integer> integer(Integer value, String reason) {
        return value == null || value < 1
                ? DecisionSignal.unavailable(DecisionSignalSource.LLM_V2, null, reason)
                : DecisionSignal.available(value, DecisionSignalSource.LLM_V2, null);
    }

    private DecisionSignal<Double> decimal(Double value, String reason) {
        return value == null
                ? DecisionSignal.unavailable(DecisionSignalSource.LLM_V2, null, reason)
                : DecisionSignal.available(value, DecisionSignalSource.LLM_V2, null);
    }

    private DecisionSignal<String> text(String value, String reason) {
        return value == null || value.isBlank()
                ? DecisionSignal.unavailable(DecisionSignalSource.LLM_V2, null, reason)
                : DecisionSignal.available(value.trim(), DecisionSignalSource.LLM_V2, null);
    }
}
