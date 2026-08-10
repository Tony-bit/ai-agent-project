package denny.ai.agent.trading.domain.node;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import denny.ai.agent.trading.api.vo.NewsItemVO;
import denny.ai.agent.trading.api.vo.NewsReportVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NewsAnalysisStructuredProcessorTest {

    @Test
    void buildsCleanNumberedJsonInputForLlm() {
        NewsAnalysisStructuredProcessor processor = new NewsAnalysisStructuredProcessor();
        List<NewsItemVO> newsItems = List.of(
                NewsItemVO.builder()
                        .publishTime("2026-05-01 16:48:11")
                        .title("<em>CleanSoft</em> 002511 Q1 net profit reached 103 million")
                        .source("Source A")
                        .summary("CleanSoft&nbsp;<em>002511</em> disclosed its Q1 report.")
                        .build(),
                NewsItemVO.builder()
                        .publishTime("2026-05-01 09:25:35")
                        .title("CleanSoft 002511 institutional holdings declined")
                        .source("Source B")
                        .summary("Top ten institutional holdings declined by 3.66 percentage points.")
                        .build()
        );

        String jsonInput = processor.buildLlmInput("002511", newsItems);

        JSONObject input = JSON.parseObject(jsonInput);
        assertEquals("002511", input.getString("ticker"));
        assertEquals(2, input.getJSONArray("newsItems").size());
        assertFalse(jsonInput.contains("<em>"));
        assertEquals(1, input.getJSONArray("newsItems").getJSONObject(0).getIntValue("id"));
        assertEquals("CleanSoft 002511 Q1 net profit reached 103 million",
                input.getJSONArray("newsItems").getJSONObject(0).getString("title"));
    }

    @Test
    void includesAvailableSentimentScoresAndOmitsMissingOnes() {
        NewsAnalysisStructuredProcessor processor = new NewsAnalysisStructuredProcessor();
        List<NewsItemVO> items = List.of(
                NewsItemVO.builder().title("approved").sentimentScore(0.6).build(),
                NewsItemVO.builder().title("unknown").build());

        JSONObject root = JSON.parseObject(
                processor.buildLlmInput("600285.SH", "羚锐制药", items));
        JSONObject scoredItem = root.getJSONArray("newsItems").getJSONObject(0);
        JSONObject unscoredItem = root.getJSONArray("newsItems").getJSONObject(1);

        assertEquals(0.6, scoredItem.getDoubleValue("sentimentScore"), 0.000001);
        assertFalse(unscoredItem.containsKey("sentimentScore"));
    }

    @Test
    void includesAllFiveSourceFieldsAndSentimentScore() {
        NewsItemVO news = NewsItemVO.builder()
                .publishTime("2026-08-04 15:48:00")
                .source("财中社")
                .title("<em>羚锐制药</em>解除部分质押")
                .summary("公司发布公告，股东解除部分质押。")
                .url("https://finance.eastmoney.com/a/202608043831176255.html")
                .sentimentScore(0.2)
                .build();

        JSONObject item = JSON.parseObject(
                        new NewsAnalysisStructuredProcessor().buildLlmInput(
                                "600285.SH", "羚锐制药", List.of(news)))
                .getJSONArray("newsItems").getJSONObject(0);

        assertEquals("2026-08-04 15:48:00", item.getString("publishTime"));
        assertEquals("财中社", item.getString("source"));
        assertEquals("羚锐制药解除部分质押", item.getString("title"));
        assertEquals("公司发布公告，股东解除部分质押。", item.getString("summary"));
        assertEquals("https://finance.eastmoney.com/a/202608043831176255.html", item.getString("url"));
        assertEquals(0.2, item.getDoubleValue("sentimentScore"), 0.000001);
    }

    @Test
    void keepsNewsWhenUrlIsMissing() {
        JSONObject item = JSON.parseObject(
                        new NewsAnalysisStructuredProcessor().buildLlmInput(
                                "600285.SH", "羚锐制药",
                                List.of(NewsItemVO.builder().title("羚锐制药发布公告").build())))
                .getJSONArray("newsItems").getJSONObject(0);

        assertFalse(item.containsKey("url"));
        assertEquals("羚锐制药发布公告", item.getString("title"));
    }

    @Test
    void instructionsDefineFiveFieldSummaryOnlyAnalysis() {
        JSONObject input = JSON.parseObject(
                new NewsAnalysisStructuredProcessor().buildLlmInput(
                        "600285.SH", "羚锐制药",
                        List.of(NewsItemVO.builder()
                                .title("公司发布公告")
                                .summary("摘要")
                                .build())));

        String instructions = input.getString("instructions").toLowerCase();
        assertTrue(instructions.contains("title and summary determine article sentiment"));
        assertTrue(instructions.contains(
                "source, publishtime, and url affect report-level confidence and dataquality"));
        assertTrue(instructions.contains("do not access url"));
        assertTrue(instructions.contains("summary-level evidence"));
        assertTrue(instructions.contains("missing or unparseable publishtime"));
        assertTrue(instructions.contains("title and summary conflict"));
    }

    @Test
    void parsesStructuredLlmReportAndKeepsOriginalNewsItems() {
        NewsAnalysisStructuredProcessor processor = new NewsAnalysisStructuredProcessor();
        List<NewsItemVO> newsItems = List.of(
                NewsItemVO.builder().title("earnings improved").sentimentScore(0.8).build(),
                NewsItemVO.builder().title("institutional holdings declined").sentimentScore(-0.2).build()
        );
        String response = """
                ```json
                {
                  "rating": 4,
                  "overallSentiment": "positive",
                  "confidence": 0.82,
                  "deduplicatedEvents": [
                    {
                      "eventType": "earnings",
                      "eventTitle": "Q1 net profit improved",
                      "sentiment": "positive",
                      "impactLevel": "high",
                      "sourceNewsIds": [1],
                      "summary": "Q1 net profit grew materially."
                    }
                  ],
                  "newsThemes": [
                    {
                      "theme": "Q1 earnings improvement",
                      "sentiment": "positive",
                      "impactLevel": "high",
                      "evidenceIds": [1],
                      "reason": "Net profit grew materially."
                    }
                  ],
                  "riskWarnings": [
                    {
                      "risk": "Institutional holdings declined",
                      "impactLevel": "medium",
                      "evidenceIds": [2],
                      "reason": "Some capital attitude divergence exists."
                    }
                  ],
                  "dataQuality": "Several reports may describe the same earnings event.",
                  "summary": "Earnings improvement is the main positive signal."
                }
                ```
                """;

        NewsReportVO report = processor.parseReport(response, newsItems);

        assertEquals(4, report.getRating());
        assertEquals("positive", report.getOverallSentiment());
        assertEquals(0.82, report.getConfidence());
        assertSame(newsItems, report.getNewsItems());
        assertEquals("earnings", report.getDeduplicatedEvents().get(0).getEventType());
        assertEquals(List.of(1), report.getDeduplicatedEvents().get(0).getSourceNewsIds());
        assertEquals("Q1 earnings improvement", report.getNewsThemes().get(0).getTheme());
        assertEquals(List.of(1), report.getNewsThemes().get(0).getEvidenceIds());
        assertEquals("Institutional holdings declined", report.getRiskWarnings().get(0).getRisk());
        assertEquals("Several reports may describe the same earnings event.", report.getDataQuality());
    }

    @Test
    void parsesEvidenceMetadataFromUnifiedStructuredReport() {
        NewsAnalysisStructuredProcessor processor = new NewsAnalysisStructuredProcessor();
        List<NewsItemVO> newsItems = List.of(
                NewsItemVO.builder().title("summary only").sentimentScore(0.1).build(),
                NewsItemVO.builder().title("full text enhanced").sentimentScore(0.2).build()
        );
        String response = """
                {
                  "rating": 4,
                  "overallSentiment": "positive",
                  "confidence": 0.76,
                  "enhancedSourceNewsIds": [2],
                  "deduplicatedEvents": [
                    {
                      "eventType": "regulatory",
                      "eventTitle": "Regulatory inquiry confirmed by full text",
                      "sentiment": "negative",
                      "impactLevel": "medium",
                      "sourceNewsIds": [1, 2],
                      "enhancedSourceNewsIds": [2],
                      "evidenceLevel": "full_text",
                      "evidenceQuality": "confirmed",
                      "summary": "Full text confirmed the inquiry details."
                    }
                  ],
                  "newsThemes": [
                    {
                      "theme": "Regulatory pressure",
                      "sentiment": "negative",
                      "impactLevel": "medium",
                      "evidenceIds": [1, 2],
                      "enhancedSourceNewsIds": [2],
                      "evidenceLevel": "full_text",
                      "evidenceQuality": "confirmed",
                      "reason": "Full article contains specific facts."
                    }
                  ],
                  "riskWarnings": [
                    {
                      "risk": "Regulatory uncertainty",
                      "impactLevel": "medium",
                      "evidenceIds": [2],
                      "enhancedSourceNewsIds": [2],
                      "evidenceLevel": "full_text",
                      "evidenceQuality": "confirmed",
                      "reason": "The full article provides the inquiry date."
                    }
                  ],
                  "dataQuality": "Structured from title and summary; selected articles were enhanced with full text.",
                  "summary": "Unified report keeps the same output shape after full text enhancement."
                }
                """;

        NewsReportVO report = processor.parseReport(response, newsItems);

        assertEquals(List.of(2), report.getEnhancedSourceNewsIds());
        assertEquals("full_text", report.getDeduplicatedEvents().get(0).getEvidenceLevel());
        assertEquals("confirmed", report.getDeduplicatedEvents().get(0).getEvidenceQuality());
        assertEquals(List.of(2), report.getDeduplicatedEvents().get(0).getEnhancedSourceNewsIds());
        assertEquals("full_text", report.getNewsThemes().get(0).getEvidenceLevel());
        assertEquals("confirmed", report.getNewsThemes().get(0).getEvidenceQuality());
        assertEquals(List.of(2), report.getNewsThemes().get(0).getEnhancedSourceNewsIds());
        assertEquals("full_text", report.getRiskWarnings().get(0).getEvidenceLevel());
        assertEquals("confirmed", report.getRiskWarnings().get(0).getEvidenceQuality());
        assertEquals(List.of(2), report.getRiskWarnings().get(0).getEnhancedSourceNewsIds());
    }

    @Test
    void includesOptionalFullTextEvidenceFieldsInLlmInput() {
        NewsAnalysisStructuredProcessor processor = new NewsAnalysisStructuredProcessor();
        List<NewsItemVO> newsItems = List.of(
                NewsItemVO.builder()
                        .publishTime("2026-05-02 10:00:00")
                        .title("CleanSoft 002511 received regulatory inquiry")
                        .source("Source A")
                        .summary("CleanSoft 002511 disclosed a regulatory inquiry.")
                        .content("<p>CleanSoft 002511 received a formal inquiry letter on 2026-05-02.</p>")
                        .fullTextFetched(true)
                        .contentQuality("usable")
                        .sourceReliability("mainstream_media")
                        .evidenceLevel("full_text")
                        .evidenceQuality("confirmed")
                        .build()
        );

        String jsonInput = processor.buildLlmInput("002511", "CleanSoft", newsItems);

        JSONObject item = JSON.parseObject(jsonInput).getJSONArray("newsItems").getJSONObject(0);
        assertEquals("CleanSoft 002511 received a formal inquiry letter on 2026-05-02.", item.getString("content"));
        assertTrue(item.getBooleanValue("fullTextFetched"));
        assertEquals("usable", item.getString("contentQuality"));
        assertEquals("mainstream_media", item.getString("sourceReliability"));
        assertEquals("full_text", item.getString("evidenceLevel"));
        assertEquals("confirmed", item.getString("evidenceQuality"));
    }

    @Test
    void fallsBackToNewsSentimentWhenLlmResponseIsNotJson() {
        NewsAnalysisStructuredProcessor processor = new NewsAnalysisStructuredProcessor();
        List<NewsItemVO> newsItems = List.of(
                NewsItemVO.builder().title("positive").sentimentScore(0.8).build(),
                NewsItemVO.builder().title("neutral").sentimentScore(0.4).build()
        );

        NewsReportVO report = processor.parseReport("not-json-response", newsItems);

        assertEquals(5, report.getRating());
        assertEquals("positive", report.getOverallSentiment());
        assertEquals("not-json-response", report.getSummary());
        assertTrue(report.getDeduplicatedEvents().isEmpty());
        assertEquals("LLM response parse failed; used sentiment-score fallback.", report.getDataQuality());
    }

    @Test
    void keepsSemanticDuplicatesForLlmEventDeduplication() {
        NewsAnalysisStructuredProcessor processor = new NewsAnalysisStructuredProcessor();
        List<NewsItemVO> newsItems = List.of(
                NewsItemVO.builder()
                        .publishTime("2026-05-01 16:48:11")
                        .title("CleanSoft 002511 Q1 net profit reached 103 million, up 53.76%")
                        .source("Source A")
                        .summary("CleanSoft 002511 disclosed its Q1 report. Net profit was 103 million, up 53.76%.")
                        .build(),
                NewsItemVO.builder()
                        .publishTime("2026-04-28 09:11:05")
                        .title("CleanSoft(002511): Q1 net profit was 103 million yuan")
                        .source("Source B")
                        .summary("CleanSoft released its Q1 report. Net profit reached 103 million and increased 53.8%.")
                        .build(),
                NewsItemVO.builder()
                        .publishTime("2026-04-14 18:58:00")
                        .title("CleanSoft plans cash dividend of 0.41 yuan per 10 shares")
                        .source("Source C")
                        .summary("CleanSoft 002511 announced a cash dividend plan.")
                        .build()
        );

        String jsonInput = processor.buildLlmInput("002511", "CleanSoft", newsItems);

        JSONObject input = JSON.parseObject(jsonInput);
        assertEquals(3, input.getJSONArray("newsItems").size());
        assertNull(input.getJSONArray("newsItems").getJSONObject(0).getJSONArray("duplicateOriginalIds"));
    }

    @Test
    void removesExactDuplicateNewsBeforeSendingToLlm() {
        NewsAnalysisStructuredProcessor processor = new NewsAnalysisStructuredProcessor();
        List<NewsItemVO> newsItems = List.of(
                NewsItemVO.builder()
                        .publishTime("2026-05-01 16:48:11")
                        .title("CleanSoft 002511 Q1 net profit reached 103 million, up 53.76%")
                        .source("Source A")
                        .summary("CleanSoft 002511 disclosed its Q1 report.")
                        .url("https://example.com/first")
                        .build(),
                NewsItemVO.builder()
                        .publishTime("2026-05-01 16:48:11")
                        .title("CleanSoft 002511 Q1 net profit reached 103 million, up 53.76%")
                        .source("Source B")
                        .summary("CleanSoft 002511 disclosed its Q1 report.")
                        .url("https://example.com/duplicate")
                        .build()
        );

        String jsonInput = processor.buildLlmInput("002511", "CleanSoft", newsItems);

        JSONObject input = JSON.parseObject(jsonInput);
        assertEquals(1, input.getJSONArray("newsItems").size());
        assertEquals(List.of(2), input.getJSONArray("newsItems").getJSONObject(0)
                .getJSONArray("duplicateOriginalIds").toJavaList(Integer.class));
        assertEquals("https://example.com/first",
                input.getJSONArray("newsItems").getJSONObject(0).getString("url"));
    }

    @Test
    void removesUnrelatedSnippetNoiseFromTitleAndSummary() {
        NewsAnalysisStructuredProcessor processor = new NewsAnalysisStructuredProcessor();
        List<NewsItemVO> newsItems = List.of(
                NewsItemVO.builder()
                        .publishTime("2026-04-22 17:19:00")
                        .title("Market list: 000001 OtherBank up 5%; CleanSoft 002511 got buy rating; 000002 OtherTech down")
                        .source("Source A")
                        .summary("0.66 transportation 000001 OtherBank; CleanSoft 002511 target upside above 20%; media 000002 OtherTech")
                        .build()
        );

        String jsonInput = processor.buildLlmInput("002511", "CleanSoft", newsItems);

        JSONObject item = JSON.parseObject(jsonInput).getJSONArray("newsItems").getJSONObject(0);
        assertEquals("CleanSoft 002511 got buy rating", item.getString("title"));
        assertEquals("CleanSoft 002511 target upside above 20%", item.getString("summary"));
    }
}
