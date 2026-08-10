package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.NewsItemVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NewsItemSentimentEnricherTest {

    private final NewsItemSentimentEnricher enricher =
            new NewsItemSentimentEnricher(new NewsKeywordSentimentScorer());

    @Test
    void preservesNativeScoresAndFillsOnlyRuleMatches() {
        NewsItemVO nativeScore = NewsItemVO.builder()
                .title("公司被处罚").sentimentScore(0.25).build();
        NewsItemVO scored = NewsItemVO.builder().title("公司新药获批").build();
        NewsItemVO unknown = NewsItemVO.builder().title("公司发布公告").build();

        var summary = enricher.enrich(List.of(nativeScore, scored, unknown));

        assertEquals(0.25, nativeScore.getSentimentScore());
        assertEquals(0.60, scored.getSentimentScore(), 0.000001);
        assertNull(unknown.getSentimentScore());
        assertEquals(3, summary.totalCount());
        assertEquals(1, summary.nativeScoreCount());
        assertEquals(1, summary.ruleScoreCount());
        assertEquals(1, summary.unscoredCount());
    }

    @Test
    void toleratesNullListNullEntriesAndBlankItems() {
        assertEquals(0, enricher.enrich(null).totalCount());

        List<NewsItemVO> items = new ArrayList<>(Arrays.asList(null,
                NewsItemVO.builder().build()));
        var summary = enricher.enrich(items);

        assertEquals(2, summary.totalCount());
        assertEquals(0, summary.nativeScoreCount());
        assertEquals(0, summary.ruleScoreCount());
        assertEquals(2, summary.unscoredCount());
    }
}
