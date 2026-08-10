package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.NewsItemVO;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsAnalysisPreprocessorTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZonedDateTime NOW =
            ZonedDateTime.of(2026, 8, 10, 12, 0, 0, 0, SHANGHAI);

    private final NewsAnalysisPreprocessor preprocessor = new NewsAnalysisPreprocessor(
            new NewsItemSentimentEnricher(new NewsKeywordSentimentScorer()));

    @Test
    void keepsInclusiveFifteenDayBoundaryAndFutureTolerance() {
        List<NewsItemVO> items = new ArrayList<>(List.of(
                item("2026-07-26 12:00:00", "公司回购"),
                item("2026-08-10 12:10:00", "公司获批新药")
        ));

        NewsAnalysisPreprocessor.Result result = preprocessor.prepare(items, NOW);

        assertEquals(2, result.newsItems().size());
        assertEquals(0, result.staleCount());
        assertEquals(0, result.futureCount());
        assertNotNull(result.newsItems().get(0).getSentimentScore());
        assertNotNull(result.newsItems().get(1).getSentimentScore());
    }

    @Test
    void excludesOlderAndClearlyFutureNewsBeforeScoring() {
        NewsItemVO stale = item("2026-07-26 11:59:59", "公司回购");
        NewsItemVO future = item("2026-08-10 12:10:01", "公司获批新药");

        NewsAnalysisPreprocessor.Result result = preprocessor.prepare(
                new ArrayList<>(List.of(stale, future)), NOW);

        assertTrue(result.newsItems().isEmpty());
        assertEquals(1, result.staleCount());
        assertEquals(1, result.futureCount());
        assertNull(stale.getSentimentScore());
        assertNull(future.getSentimentScore());
    }

    @Test
    void keepsMissingAndUnparseableTimesAsUnknown() {
        List<NewsItemVO> items = new ArrayList<>(List.of(
                item(null, "公司回购"),
                item("时间未知", "公司减持")
        ));

        NewsAnalysisPreprocessor.Result result = preprocessor.prepare(items, NOW);

        assertEquals(2, result.newsItems().size());
        assertEquals(2, result.unknownTimeCount());
        assertNotNull(result.newsItems().get(0).getSentimentScore());
        assertNotNull(result.newsItems().get(1).getSentimentScore());
    }

    @Test
    void acceptsMinutePrecisionFromCurrentProvider() {
        NewsAnalysisPreprocessor.Result result = preprocessor.prepare(
                new ArrayList<>(List.of(item("2026-08-04 15:48", "公司解除质押"))), NOW);

        assertEquals(1, result.newsItems().size());
        assertEquals(0, result.unknownTimeCount());
    }

    @Test
    void preservesProviderOrderAndNativeScore() {
        NewsItemVO first = item("2026-08-10 10:00:00", "普通公告");
        first.setSentimentScore(0.25);
        NewsItemVO second = item("2026-08-09 10:00:00", "公司回购");

        NewsAnalysisPreprocessor.Result result = preprocessor.prepare(
                new ArrayList<>(List.of(first, second)), NOW);

        assertSame(first, result.newsItems().get(0));
        assertSame(second, result.newsItems().get(1));
        assertEquals(0.25, first.getSentimentScore(), 0.000001);
        assertTrue(second.getSentimentScore() > 0);
    }

    private NewsItemVO item(String publishTime, String title) {
        return NewsItemVO.builder().publishTime(publishTime).title(title).build();
    }
}
