package denny.ai.agent.trading.domain.node;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import denny.ai.agent.trading.api.vo.NewsItemVO;
import denny.ai.agent.trading.api.vo.NewsReportVO;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
class NewsAnalysisStructuredProcessor {

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern SPLIT_NOISE_PATTERN = Pattern.compile("[;|\\uFF1B\\uFF5C\\u3002!?\\uFF01\\uFF1F\\n\\r]+");

    String buildLlmInput(String ticker, List<NewsItemVO> newsItems) {
        return buildLlmInput(ticker, null, newsItems);
    }

    String buildLlmInput(String ticker, String stockName, List<NewsItemVO> newsItems) {
        JSONObject input = new JSONObject(true);
        input.put("ticker", ticker);

        JSONArray items = new JSONArray();
        if (newsItems != null) {
            List<PreparedNewsItem> preparedItems = prepareItems(ticker, stockName, newsItems);
            for (int i = 0; i < preparedItems.size(); i++) {
                PreparedNewsItem prepared = preparedItems.get(i);
                if (prepared.duplicateOf != null) {
                    preparedItems.get(prepared.duplicateOf).duplicateOriginalIds.add(i + 1);
                }
            }
            for (int i = 0; i < preparedItems.size(); i++) {
                PreparedNewsItem prepared = preparedItems.get(i);
                if (prepared.duplicateOf != null) {
                    continue;
                }
                JSONObject item = new JSONObject(true);
                item.put("id", items.size() + 1);
                item.put("originalId", i + 1);
                item.put("publishTime", prepared.publishTime);
                item.put("source", prepared.source);
                item.put("title", prepared.title);
                item.put("summary", prepared.summary);
                putIfNotBlank(item, "content", prepared.content);
                putIfNotNull(item, "fullTextFetched", prepared.fullTextFetched);
                putIfNotBlank(item, "contentQuality", prepared.contentQuality);
                putIfNotBlank(item, "sourceReliability", prepared.sourceReliability);
                putIfNotBlank(item, "evidenceLevel", prepared.evidenceLevel);
                putIfNotBlank(item, "evidenceQuality", prepared.evidenceQuality);
                if (!prepared.duplicateOriginalIds.isEmpty()) {
                    item.put("duplicateOriginalIds", prepared.duplicateOriginalIds);
                }
                items.add(item);
            }
        }
        input.put("newsItems", items);
        input.put("instructions", "The newsItems list contains cleaned titles and summaries, and may contain optional full article content for selected items only. Exact duplicates were removed only. The LLM must fill one unified NewsReportVO-shaped result regardless of whether evidence comes from summary or full_text. deduplicatedEvents groups semantically equivalent news. sourceNewsIds and evidenceIds must reference newsItems.id values. duplicateOriginalIds records removed exact duplicate source rows. Use evidenceLevel/evidenceQuality/enhancedSourceNewsIds to show whether conclusions are based on summary, full_text, or authoritative evidence.");
        return JSON.toJSONString(input);
    }

    NewsReportVO parseReport(String llmResponse, List<NewsItemVO> newsItems) {
        try {
            String json = extractJson(llmResponse);
            if (json != null) {
                NewsReportVO report = JSON.parseObject(json, NewsReportVO.class);
                if (isValid(report)) {
                    report.setNewsItems(newsItems);
                    normalizeReport(report);
                    return report;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse structured news report from LLM response: {}", e.getMessage());
        }
        return fallbackReport(llmResponse, newsItems);
    }

    private void normalizeReport(NewsReportVO report) {
        report.setRating(clampRating(report.getRating()));
        report.setOverallSentiment(normalizeSentiment(report.getOverallSentiment()));
        if (report.getConfidence() == null) {
            report.setConfidence(0.5);
        } else if (report.getConfidence() < 0) {
            report.setConfidence(0.0);
        } else if (report.getConfidence() > 1) {
            report.setConfidence(1.0);
        }
        if (report.getDeduplicatedEvents() == null) {
            report.setDeduplicatedEvents(new ArrayList<>());
        }
        if (report.getNewsThemes() == null) {
            report.setNewsThemes(new ArrayList<>());
        }
        if (report.getRiskWarnings() == null) {
            report.setRiskWarnings(new ArrayList<>());
        }
        if (report.getEnhancedSourceNewsIds() == null) {
            report.setEnhancedSourceNewsIds(new ArrayList<>());
        }
        if (report.getDataQuality() == null || report.getDataQuality().isBlank()) {
            report.setDataQuality("LLM structured response parsed.");
        }
        if (report.getSummary() == null) {
            report.setSummary("");
        }
    }

    private boolean isValid(NewsReportVO report) {
        return report != null && report.getRating() != null && report.getRating() >= 1 && report.getRating() <= 5;
    }

    private NewsReportVO fallbackReport(String llmResponse, List<NewsItemVO> newsItems) {
        return NewsReportVO.builder()
                .rating(calculateRating(newsItems))
                .newsItems(newsItems)
                .overallSentiment(determineOverallSentiment(newsItems))
                .summary(llmResponse == null ? "" : llmResponse)
                .confidence(0.3)
                .deduplicatedEvents(new ArrayList<>())
                .newsThemes(new ArrayList<>())
                .riskWarnings(new ArrayList<>())
                .enhancedSourceNewsIds(new ArrayList<>())
                .dataQuality("LLM response parse failed; used sentiment-score fallback.")
                .build();
    }

    private int calculateRating(List<NewsItemVO> newsItems) {
        if (newsItems == null || newsItems.isEmpty()) {
            return 3;
        }

        double totalSentiment = 0;
        int count = 0;
        for (NewsItemVO news : newsItems) {
            if (news.getSentimentScore() != null) {
                totalSentiment += news.getSentimentScore();
                count++;
            }
        }
        if (count == 0) {
            return 3;
        }

        double avgSentiment = totalSentiment / count;
        if (avgSentiment > 0.5) return 5;
        if (avgSentiment > 0.2) return 4;
        if (avgSentiment > -0.2) return 3;
        if (avgSentiment > -0.5) return 2;
        return 1;
    }

    private String determineOverallSentiment(List<NewsItemVO> newsItems) {
        if (newsItems == null || newsItems.isEmpty()) {
            return "neutral";
        }

        double totalSentiment = 0;
        int count = 0;
        for (NewsItemVO news : newsItems) {
            if (news.getSentimentScore() != null) {
                totalSentiment += news.getSentimentScore();
                count++;
            }
        }
        if (count == 0) {
            return "neutral";
        }

        double avgSentiment = totalSentiment / count;
        if (avgSentiment > 0.3) return "positive";
        if (avgSentiment > -0.3) return "mixed";
        return "negative";
    }

    private String normalizeSentiment(String sentiment) {
        if (sentiment == null || sentiment.isBlank()) {
            return "neutral";
        }
        String value = sentiment.trim().toLowerCase();
        return switch (value) {
            case "positive", "negative", "neutral", "mixed" -> value;
            case "\u6B63\u9762", "\u504F\u6B63\u9762" -> "positive";
            case "\u8D1F\u9762", "\u504F\u8D1F\u9762" -> "negative";
            case "\u6DF7\u5408" -> "mixed";
            default -> "neutral";
        };
    }

    private Integer clampRating(Integer rating) {
        if (rating == null) {
            return 3;
        }
        if (rating < 1) {
            return 1;
        }
        if (rating > 5) {
            return 5;
        }
        return rating;
    }

    private String extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1)
                    .replace('\u201C', '"')
                    .replace('\u201D', '"');
        }
        return null;
    }

    private List<PreparedNewsItem> prepareItems(String ticker, String stockName, List<NewsItemVO> newsItems) {
        List<PreparedNewsItem> preparedItems = new ArrayList<>();
        for (int i = 0; i < newsItems.size(); i++) {
            NewsItemVO news = newsItems.get(i);
            PreparedNewsItem current = new PreparedNewsItem(
                    cleanBasic(news.getPublishTime()),
                    cleanBasic(news.getSource()),
                    cleanContent(news.getTitle(), ticker, stockName),
                    cleanContent(news.getSummary(), ticker, stockName),
                    cleanBasic(news.getContent()),
                    news.getFullTextFetched(),
                    cleanBasic(news.getContentQuality()),
                    cleanBasic(news.getSourceReliability()),
                    cleanBasic(news.getEvidenceLevel()),
                    cleanBasic(news.getEvidenceQuality())
            );
            for (int j = 0; j < preparedItems.size(); j++) {
                PreparedNewsItem existing = preparedItems.get(j);
                if (isExactDuplicate(current, existing)) {
                    current.duplicateOf = j;
                    break;
                }
            }
            preparedItems.add(current);
        }
        return preparedItems;
    }

    private boolean isExactDuplicate(PreparedNewsItem current, PreparedNewsItem existing) {
        String currentText = current.title + " " + current.summary;
        String existingText = existing.title + " " + existing.summary;
        return normalizeForCompare(currentText).equals(normalizeForCompare(existingText));
    }

    private String normalizeForCompare(String value) {
        return cleanBasic(value).toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "");
    }

    private String cleanContent(String value, String ticker, String stockName) {
        String cleaned = cleanBasic(value);
        if (cleaned.isBlank() || (isBlank(ticker) && isBlank(stockName))) {
            return cleaned;
        }

        String[] parts = SPLIT_NOISE_PATTERN.split(cleaned);
        List<String> relatedParts = new ArrayList<>();
        for (String part : parts) {
            String trimmed = trimNoise(part);
            if (!trimmed.isBlank() && isRelatedToTarget(trimmed, ticker, stockName)) {
                relatedParts.add(trimmed);
            }
        }
        return relatedParts.isEmpty() ? cleaned : String.join("; ", relatedParts);
    }

    private boolean isRelatedToTarget(String text, String ticker, String stockName) {
        String lowerText = text.toLowerCase();
        return (!isBlank(ticker) && lowerText.contains(ticker.toLowerCase()))
                || (!isBlank(stockName) && lowerText.contains(stockName.toLowerCase()));
    }

    private String trimNoise(String value) {
        return value.replaceAll("^[\\s:：,，.。-]+", "")
                .replaceAll("[\\s:：,，.。-]+$", "")
                .trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void putIfNotBlank(JSONObject item, String key, String value) {
        if (!isBlank(value)) {
            item.put(key, value);
        }
    }

    private void putIfNotNull(JSONObject item, String key, Object value) {
        if (value != null) {
            item.put(key, value);
        }
    }

    private String cleanBasic(String value) {
        if (value == null) {
            return "";
        }
        return HTML_TAG_PATTERN.matcher(value.replaceAll("(?i)</?em>", "")).replaceAll("")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace('\u3000', ' ')
                .replace('\u00A0', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static class PreparedNewsItem {
        private final String publishTime;
        private final String source;
        private final String title;
        private final String summary;
        private final String content;
        private final Boolean fullTextFetched;
        private final String contentQuality;
        private final String sourceReliability;
        private final String evidenceLevel;
        private final String evidenceQuality;
        private final List<Integer> duplicateOriginalIds = new ArrayList<>();
        private Integer duplicateOf;

        private PreparedNewsItem(String publishTime,
                                 String source,
                                 String title,
                                 String summary,
                                 String content,
                                 Boolean fullTextFetched,
                                 String contentQuality,
                                 String sourceReliability,
                                 String evidenceLevel,
                                 String evidenceQuality) {
            this.publishTime = publishTime;
            this.source = source;
            this.title = title;
            this.summary = summary;
            this.content = content;
            this.fullTextFetched = fullTextFetched;
            this.contentQuality = contentQuality;
            this.sourceReliability = sourceReliability;
            this.evidenceLevel = evidenceLevel;
            this.evidenceQuality = evidenceQuality;
        }
    }
}
