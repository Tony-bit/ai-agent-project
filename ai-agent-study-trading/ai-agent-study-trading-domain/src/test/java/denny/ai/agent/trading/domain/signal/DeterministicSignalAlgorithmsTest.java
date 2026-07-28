package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.FundamentalDataVO;
import denny.ai.agent.trading.api.vo.NewsItemVO;
import denny.ai.agent.trading.api.vo.SentimentDataVO;
import denny.ai.agent.trading.api.vo.TechnicalIndicatorsVO;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeterministicSignalAlgorithmsTest {

    @Test
    void fundamentalUsesCoverageThresholdStrictBoundariesAndHalfUpRounding() {
        FundamentalRatingAlgorithm algorithm = new FundamentalRatingAlgorithm();

        assertEquals(DecisionSignalStatus.UNAVAILABLE, algorithm.calculate(
                FundamentalDataVO.builder().roe(25.0).build()).status());
        assertEquals(2, algorithm.calculate(FundamentalDataVO.builder()
                .roe(20.0).grossMargin(20.0).netMargin(10.0).revenueGrowth(5.0).build()).value());
        assertEquals(5, algorithm.calculate(FundamentalDataVO.builder()
                .roe(21.0).grossMargin(41.0).netMargin(21.0).revenueGrowth(16.0).build()).value());
    }

    @Test
    void technicalRequiresAllInputsAndDerivesCanonicalTrend() {
        TechnicalSignalAlgorithm algorithm = new TechnicalSignalAlgorithm();

        assertEquals(DecisionSignalStatus.UNAVAILABLE, algorithm.calculate(
                TechnicalIndicatorsVO.builder().rsi6(50.0).build()).rating().status());
        TechnicalSignalAlgorithm.TechnicalSignals up = algorithm.calculate(technical(
                50.0, "1", "12", "10"));
        assertEquals("UP", up.trendSignal().value());
        assertEquals(5, up.rating().value());
        TechnicalSignalAlgorithm.TechnicalSignals down = algorithm.calculate(technical(
                80.0, "-1", "8", "10"));
        assertEquals("DOWN", down.trendSignal().value());
        assertEquals(3, down.rating().value());
        assertEquals("SIDEWAYS", algorithm.calculate(technical(
                50.0, "-1", "12", "10")).trendSignal().value());
    }

    @Test
    void sentimentRequiresTwoDimensionsAndKeepsAuthoritativeScore() {
        SentimentRatingAlgorithm algorithm = new SentimentRatingAlgorithm();

        SentimentRatingAlgorithm.SentimentSignals insufficient = algorithm.calculate(
                SentimentDataVO.builder().overallScore(0.7).build());
        assertEquals(DecisionSignalStatus.UNAVAILABLE, insufficient.rating().status());
        assertEquals(0.7, insufficient.sentimentScore().value());
        assertEquals(5, algorithm.calculate(SentimentDataVO.builder()
                .overallScore(0.7).fearGreedIndex(50).bullRatio(0.7).build()).rating().value());
        assertEquals(2, algorithm.calculate(SentimentDataVO.builder()
                .overallScore(0.2).fearGreedIndex(39).build()).rating().value());
    }

    @Test
    void newsIgnoresNullScoresAndUsesStrictThresholds() {
        NewsRatingAlgorithm algorithm = new NewsRatingAlgorithm();

        assertEquals(DecisionSignalStatus.UNAVAILABLE,
                algorithm.calculate(List.of(NewsItemVO.builder().build())).rating().status());
        NewsRatingAlgorithm.NewsSignals positive = algorithm.calculate(List.of(
                item(0.8), item(null), item(0.4)));
        assertEquals(5, positive.rating().value());
        assertEquals("positive", positive.overallSentiment().value());
        NewsRatingAlgorithm.NewsSignals boundary = algorithm.calculate(List.of(item(0.2)));
        assertEquals(3, boundary.rating().value());
        assertEquals("mixed", boundary.overallSentiment().value());
        assertEquals("negative", algorithm.calculate(List.of(item(-0.3)))
                .overallSentiment().value());
    }

    private TechnicalIndicatorsVO technical(double rsi, String histogram, String ma5, String ma20) {
        return TechnicalIndicatorsVO.builder().rsi6(rsi)
                .macdHistogram(new BigDecimal(histogram))
                .ma5(new BigDecimal(ma5)).ma20(new BigDecimal(ma20)).build();
    }

    private NewsItemVO item(Double score) {
        return NewsItemVO.builder().sentimentScore(score).build();
    }
}
