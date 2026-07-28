package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.FundamentalDataVO;
import denny.ai.agent.trading.api.vo.signal.DecisionSignal;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalSource;

public final class FundamentalRatingAlgorithm {

    public static final String VERSION = "fundamental-rating-v1";

    public DecisionSignal<Integer> calculate(FundamentalDataVO data) {
        if (data == null) {
            return unavailable("fundamental data is missing");
        }
        int available = 0;
        int points = 0;
        if (data.getRoe() != null) {
            available++;
            points += score(data.getRoe(), 10, 20);
        }
        if (data.getGrossMargin() != null) {
            available++;
            points += score(data.getGrossMargin(), 20, 40);
        }
        if (data.getNetMargin() != null) {
            available++;
            points += score(data.getNetMargin(), 10, 20);
        }
        if (data.getRevenueGrowth() != null) {
            available++;
            points += score(data.getRevenueGrowth(), 5, 15);
        }
        if (available < 2) {
            return unavailable("fewer than two fundamental dimensions are available");
        }
        int rating = clamp((int) Math.floor(1.0 + 4.0 * points / (2.0 * available) + 0.5));
        return DecisionSignal.available(rating, DecisionSignalSource.DETERMINISTIC_V3, VERSION);
    }

    private int score(double value, double lower, double upper) {
        if (value > upper) {
            return 2;
        }
        return value > lower ? 1 : 0;
    }

    private int clamp(int value) {
        return Math.max(1, Math.min(5, value));
    }

    private DecisionSignal<Integer> unavailable(String reason) {
        return DecisionSignal.unavailable(DecisionSignalSource.DETERMINISTIC_V3, VERSION, reason);
    }
}
