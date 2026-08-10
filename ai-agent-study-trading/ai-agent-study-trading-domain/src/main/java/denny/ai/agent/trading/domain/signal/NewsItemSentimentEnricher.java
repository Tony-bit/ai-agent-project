package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.NewsItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class NewsItemSentimentEnricher {

    private static final int MAX_LOG_TITLE_LENGTH = 120;

    private final NewsKeywordSentimentScorer scorer;

    public NewsItemSentimentEnricher(NewsKeywordSentimentScorer scorer) {
        this.scorer = scorer;
    }

    public EnrichmentSummary enrich(List<NewsItemVO> items) {
        if (items == null) {
            EnrichmentSummary empty = new EnrichmentSummary(0, 0, 0, 0);
            logSummary(empty);
            return empty;
        }

        int nativeScoreCount = 0;
        int ruleScoreCount = 0;
        int unscoredCount = 0;
        for (NewsItemVO item : items) {
            if (item == null) {
                unscoredCount++;
                continue;
            }
            if (item.getSentimentScore() != null) {
                nativeScoreCount++;
                continue;
            }

            try {
                NewsKeywordSentimentScorer.ScoreResult result = scorer.score(item);
                if (!result.available()) {
                    unscoredCount++;
                    continue;
                }
                item.setSentimentScore(result.score());
                ruleScoreCount++;
                log.debug("News keyword score applied: title={}, rules={}, score={}",
                        titleForLog(item), result.matchedRules(), result.score());
            } catch (RuntimeException error) {
                unscoredCount++;
                log.warn("News keyword scoring failed: title={}, error={}",
                        titleForLog(item), error.getMessage());
            }
        }

        EnrichmentSummary summary = new EnrichmentSummary(items.size(), nativeScoreCount,
                ruleScoreCount, unscoredCount);
        logSummary(summary);
        return summary;
    }

    private void logSummary(EnrichmentSummary summary) {
        log.info("News sentiment enrichment complete: algorithmVersion={}, totalCount={}, "
                        + "nativeScoreCount={}, ruleScoreCount={}, unscoredCount={}",
                NewsKeywordSentimentScorer.VERSION, summary.totalCount(),
                summary.nativeScoreCount(), summary.ruleScoreCount(), summary.unscoredCount());
    }

    private String titleForLog(NewsItemVO item) {
        String title = item.getTitle();
        if (title == null || title.isBlank()) {
            return "<blank>";
        }
        String normalized = title.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_LOG_TITLE_LENGTH
                ? normalized : normalized.substring(0, MAX_LOG_TITLE_LENGTH);
    }

    public record EnrichmentSummary(int totalCount,
                                    int nativeScoreCount,
                                    int ruleScoreCount,
                                    int unscoredCount) {
    }
}
