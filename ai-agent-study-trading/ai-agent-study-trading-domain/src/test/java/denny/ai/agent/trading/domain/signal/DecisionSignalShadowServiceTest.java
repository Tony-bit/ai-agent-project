package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.FundamentalDataVO;
import denny.ai.agent.trading.api.vo.FundamentalReportVO;
import denny.ai.agent.trading.api.vo.NewsItemVO;
import denny.ai.agent.trading.api.vo.NewsReportVO;
import denny.ai.agent.trading.api.vo.SentimentDataVO;
import denny.ai.agent.trading.api.vo.SentimentReportVO;
import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.api.vo.TechnicalIndicatorsVO;
import denny.ai.agent.trading.api.vo.TechnicalReportVO;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalSet;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecisionSignalShadowServiceTest {

    @Test
    void calculatesSignalsFromAuthoritativeSnapshotsWithoutReplacingV2Ratings() {
        TradingContextVO context = TradingContextVO.forTarget(new TargetContext(
                UUID.randomUUID().toString(), "601318.SH", "中国平安", "保险", LocalDate.of(2026, 7, 28)));
        context.setFundamentalReport(FundamentalReportVO.builder().rating(1).rawData(
                FundamentalDataVO.builder().roe(25.0).grossMargin(45.0).build()).build());
        context.setTechnicalReport(TechnicalReportVO.builder().rating(1).indicators(
                TechnicalIndicatorsVO.builder().rsi6(50.0).macdHistogram(BigDecimal.ONE)
                        .ma5(BigDecimal.TEN).ma20(BigDecimal.ONE).build()).build());
        context.setSentimentReport(SentimentReportVO.builder().rating(1).rawData(
                SentimentDataVO.builder().overallScore(0.7).fearGreedIndex(50).build()).build());
        context.setNewsReport(NewsReportVO.builder().rating(1)
                .newsItems(List.of(NewsItemVO.builder().sentimentScore(0.8).build())).build());

        DecisionSignalSet signals = new DecisionSignalShadowService().calculate(context);

        assertEquals(4, signals.availableAnalystCount());
        assertEquals(List.of(5, 5, 4, 5), signals.analystRatings().stream()
                .map(signal -> signal.value()).toList());
        assertEquals(1, context.getFundamentalReport().getRating());
    }
}
