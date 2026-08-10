package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.NewsItemVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsKeywordSentimentScorerTest {

    private final NewsKeywordSentimentScorer scorer = new NewsKeywordSentimentScorer();

    @Test
    void scoresPositiveAndNegativeHighConfidenceEvents() {
        var approved = scorer.score(item("公司新药获批", "收到药监局批准通知"));
        var investigated = scorer.score(item("公司被立案调查", "涉嫌重大违法"));

        assertTrue(approved.available());
        assertTrue(approved.score() > 0.5);
        assertTrue(investigated.available());
        assertTrue(investigated.score() < -0.8);
    }

    @Test
    void compositeRulesOverrideContainedKeywords() {
        var narrowingLoss = scorer.score(item("公司亏损收窄", "经营情况改善"));
        var belowExpectation = scorer.score(item("利润增长不及预期", ""));

        assertEquals(0.35, narrowingLoss.score(), 0.000001);
        assertEquals(-0.60, belowExpectation.score(), 0.000001);
    }

    @Test
    void combinesMixedEventsAndKeepsZeroAvailable() {
        var mixed = scorer.score(item(
                "股东解除质押975万股，同时新增质押508万股", ""));
        var offset = scorer.score(item("公司上调评级后又下调评级", ""));

        assertTrue(mixed.available());
        assertEquals(-0.10, mixed.score(), 0.000001);
        assertTrue(offset.available());
        assertEquals(-0.05, offset.score(), 0.000001);
    }

    @Test
    void titleWinsOverDuplicateSummaryMatch() {
        var result = scorer.score(item("公司获批新产品", "公司新产品已经获批"));

        assertEquals(0.60, result.score(), 0.000001);
        assertEquals(1, result.matchedRules().size());
    }

    @Test
    void appliesSummaryWeightDegreeModifierAndHtmlCleanup() {
        var summaryOnly = scorer.score(item("公司发布公告", "净利润增长"));
        var amplified = scorer.score(item("净利润大幅增长", ""));
        var weakened = scorer.score(item("净利润小幅下降", ""));
        var html = scorer.score(item("<em>公司</em>新药获批", ""));

        assertEquals(0.30, summaryOnly.score(), 0.000001);
        assertEquals(0.60, amplified.score(), 0.000001);
        assertEquals(-0.35, weakened.score(), 0.000001);
        assertEquals(0.60, html.score(), 0.000001);
    }

    @Test
    void returnsUnavailableWithoutDirectionalEvidence() {
        assertFalse(scorer.score(item("公司发布一季报", "营业收入11.26亿元")).available());
        assertFalse(scorer.score(item(null, null)).available());
        assertFalse(scorer.score(null).available());
    }

    @Test
    void clampsScoreAndFeedsExistingNewsRatingAlgorithm() {
        NewsItemVO news = item("公司回购并获批重大合同，股价涨停", "");
        var score = scorer.score(news);

        assertEquals(1.0, score.score(), 0.000001);
        news.setSentimentScore(score.score());
        var signals = new NewsRatingAlgorithm().calculate(List.of(news));

        assertTrue(signals.rating().isAvailable());
        assertEquals(5, signals.rating().value());
        assertEquals("positive", signals.overallSentiment().value());
    }

    private NewsItemVO item(String title, String summary) {
        return NewsItemVO.builder().title(title).summary(summary).build();
    }
}
