package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.NewsItemVO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Component
public class NewsAnalysisPreprocessor {

    public static final ZoneId ANALYSIS_ZONE = ZoneId.of("Asia/Shanghai");
    static final int LOOKBACK_DAYS = 15;
    static final int FUTURE_TOLERANCE_MINUTES = 10;

    private static final List<DateTimeFormatter> TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    );

    private final NewsItemSentimentEnricher sentimentEnricher;

    public NewsAnalysisPreprocessor(NewsItemSentimentEnricher sentimentEnricher) {
        this.sentimentEnricher = sentimentEnricher;
    }

    public Result prepare(List<NewsItemVO> items, ZonedDateTime analysisTime) {
        ZonedDateTime normalizedTime = analysisTime.withZoneSameInstant(ANALYSIS_ZONE);
        LocalDateTime earliest = normalizedTime.minusDays(LOOKBACK_DAYS).toLocalDateTime();
        LocalDateTime latest = normalizedTime.plusMinutes(FUTURE_TOLERANCE_MINUTES).toLocalDateTime();
        List<NewsItemVO> included = new ArrayList<>();
        int staleCount = 0;
        int futureCount = 0;
        int unknownTimeCount = 0;

        for (NewsItemVO item : items == null ? List.<NewsItemVO>of() : items) {
            if (item == null) {
                continue;
            }
            LocalDateTime publishedAt = parse(item.getPublishTime());
            if (publishedAt == null) {
                unknownTimeCount++;
                included.add(item);
            } else if (publishedAt.isBefore(earliest)) {
                staleCount++;
            } else if (publishedAt.isAfter(latest)) {
                futureCount++;
            } else {
                included.add(item);
            }
        }

        sentimentEnricher.enrich(included);
        return new Result(List.copyOf(included), staleCount, futureCount, unknownTimeCount);
    }

    private LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (DateTimeFormatter formatter : TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(value.trim(), formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next provider-supported format.
            }
        }
        return null;
    }

    public record Result(List<NewsItemVO> newsItems,
                         int staleCount,
                         int futureCount,
                         int unknownTimeCount) {
    }
}
