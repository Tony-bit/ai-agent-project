package denny.ai.agent.trading.domain.service;

import denny.ai.agent.trading.api.vo.FundamentalReportVO;
import denny.ai.agent.trading.api.vo.NewsReportVO;
import denny.ai.agent.trading.api.vo.SentimentReportVO;
import denny.ai.agent.trading.api.vo.TechnicalReportVO;
import denny.ai.agent.trading.api.vo.signal.DecisionSignal;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalSource;
import denny.ai.agent.trading.domain.config.TradingAgentProperties;
import denny.ai.agent.trading.domain.signal.V2DecisionSignalFactory;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RatingEngineSignalTest {

    @Test
    void filtersUnavailableSignalsAndAppliesOnlySignalBasedAdjustments() {
        TradingContextVO context = TradingContextVO.empty();
        context.setFundamentalReport(FundamentalReportVO.builder().rating(5).build());
        context.setTechnicalReport(TechnicalReportVO.builder().rating(null).build());
        context.setSentimentReport(SentimentReportVO.builder().rating(3).build());
        context.setNewsReport(NewsReportVO.builder().rating(null).build());
        var signals = new V2DecisionSignalFactory().fromReports(context)
                .withDebateOverallScore(DecisionSignal.available(
                        2.0, DecisionSignalSource.LLM_V2, null))
                .withRiskScore(DecisionSignal.available(
                        2, DecisionSignalSource.LLM_V2, null));
        context.setDecisionSignals(signals);

        RatingEngine.RatingResult result = new RatingEngine(new TradingAgentProperties()).calculate(context);

        assertEquals(4.0, result.getOverallRating());
        assertEquals(4.4, result.getAdjustedRating());
        assertEquals("BUY", result.getDecision());
        assertEquals(2, result.getAnalystRatings().size());
        assertEquals(2, result.getUnavailableReasons().size());
    }

    @Test
    void zeroAvailableSignalsUsesUnknownRatherThanSyntheticZeroOrThree() {
        TradingContextVO context = TradingContextVO.empty();
        context.setDecisionSignals(new V2DecisionSignalFactory().fromReports(context));

        RatingEngine.RatingResult result = new RatingEngine(new TradingAgentProperties()).calculate(context);

        assertNull(result.getOverallRating());
        assertNull(result.getAdjustedRating());
        assertEquals("HOLD", result.getDecision());
        assertEquals("LOW", result.getConfidence());
    }
}
