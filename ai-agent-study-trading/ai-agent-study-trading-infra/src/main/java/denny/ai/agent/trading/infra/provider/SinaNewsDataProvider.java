package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.trading.api.vo.NewsItemVO;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 新浪财经新闻搜索 Provider 实现。
 * <p>
 * 调用新浪 feed.mix.sina.com.cn 接口实现关键字搜索。
 * 完全免费，支持任意中文关键字，无需 Token。
 */
@Slf4j
public class SinaNewsDataProvider implements INewsSearchProvider {

    private static final DateTimeFormatter OUTPUT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final SinaNewsApiClient apiClient;

    public SinaNewsDataProvider() {
        this.apiClient = new SinaNewsApiClient();
    }

    public SinaNewsDataProvider(SinaNewsApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public List<NewsItemVO> searchNews(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            log.warn("搜索关键字为空，直接返回空列表");
            return Collections.emptyList();
        }

        try {
            int page = 1;
            int fetched = 0;
            List<NewsItemVO> allNews = new ArrayList<>();

            while (fetched < limit) {
                int pageSize = Math.min(DEFAULT_PAGE_SIZE, limit - fetched);
                List<Map<String, String>> rawList = apiClient.search(keyword, page, pageSize);

                if (rawList.isEmpty()) {
                    break;
                }

                for (Map<String, String> raw : rawList) {
                    allNews.add(convertToNewsItem(raw));
                    fetched++;
                    if (fetched >= limit) {
                        break;
                    }
                }

                if (rawList.size() < DEFAULT_PAGE_SIZE) {
                    break;
                }
                page++;
            }

            log.info("新浪新闻搜索完成: keyword={}, limit={}, actual={}", keyword, limit, allNews.size());
            return allNews;
        } catch (Exception e) {
            log.error("新浪财经新闻搜索异常: keyword={}, error={}", keyword, e.getMessage());
            return Collections.emptyList();
        }
    }

    private NewsItemVO convertToNewsItem(Map<String, String> raw) {
        return NewsItemVO.builder()
                .title(raw.get("title"))
                .source(raw.get("media_name"))
                .publishTime(formatPublishTime(raw.get("ctime")))
                .summary(raw.get("intro"))
                .url(raw.get("url"))
                .sentimentScore(null)
                .relatedTickers(null)
                .build();
    }

    private String formatPublishTime(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            long epochSecond = Long.parseLong(timestamp);
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(epochSecond), ZoneId.of("Asia/Shanghai"));
            return dateTime.format(OUTPUT_DATE_FORMAT);
        } catch (Exception e) {
            return timestamp;
        }
    }
}
