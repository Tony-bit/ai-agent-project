package denny.ai.agent.trading.infra.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 东方财富财经新闻搜索 HTTP 客户端。
 * <p>
 * 调用 search-api-web.eastmoney.com 获取财经新闻，支持关键字搜索。
 * 完全免费，无需 Token，支持关键词匹配。
 */
@Slf4j
public class SinaNewsApiClient {

    private static final String BASE_URL = "https://search-api-web.eastmoney.com/search/jsonp";
    private static final String REFERER = "https://so.eastmoney.com/";

    private final HttpCaller httpCaller;
    private final ObjectMapper objectMapper;

    /**
     * HTTP 调用器，支持测试时注入 mock。
     */
    @FunctionalInterface
    public interface HttpCaller {
        String get(String url);
    }

    public SinaNewsApiClient() {
        this(url -> {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Referer", REFERER);
            headers.set("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.set("Accept", "text/javascript, application/json, */*; q=0.01");
            return new org.springframework.web.client.RestTemplate()
                    .getForObject(url, String.class);
        });
    }

    public SinaNewsApiClient(HttpCaller httpCaller) {
        this.httpCaller = httpCaller;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 构建请求 URL（暴露给测试用）。
     */
    String buildUrl(String keyword, int page, int pageSize) {
        String innerParam = "{\"uid\":\"\",\"keyword\":\"" + keyword
                + "\",\"type\":[\"cmsArticle\"],\"client\":\"web\",\"clientType\":\"web\","
                + "\"clientVersion\":\"curr\","
                + "\"param\":{\"cmsArticle\":{\"searchScope\":\"default\",\"sort\":\"default\","
                + "\"pageIndex\":" + page + ",\"pageSize\":" + pageSize + "}}}";
        String encodedParam = java.net.URLEncoder.encode(innerParam, java.nio.charset.StandardCharsets.UTF_8);
        return BASE_URL + "?cb=callback&param=" + encodedParam;
    }

    public List<Map<String, String>> search(String keyword, int page, int pageSize) {
        try {
            String url = buildUrl(keyword, page, pageSize);
            String response = httpCaller.get(url);
            return parseResponse(response);
        } catch (Exception e) {
            log.error("东方财富财经新闻搜索失败: keyword={}, error={}", keyword, e.getMessage());
            return Collections.emptyList();
        }
    }

    List<Map<String, String>> parseResponse(String response) {
        try {
            if (response == null) {
                return Collections.emptyList();
            }
            String json = response.trim();
            if (json.startsWith("callback(")) {
                json = json.substring("callback(".length());
            }
            if (json.endsWith(")")) {
                json = json.substring(0, json.length() - 1);
            }

            JsonNode root = objectMapper.readTree(json);
            JsonNode listNode = root.path("result").path("cmsArticle");
            if (!listNode.isArray()) {
                return Collections.emptyList();
            }

            List<Map<String, String>> result = new ArrayList<>();
            for (JsonNode item : listNode) {
                Map<String, String> news = new LinkedHashMap<>();
                news.put("title", item.path("title").asText(""));
                news.put("ctime", item.path("date").asText(""));
                String code = item.path("code").asText("");
                news.put("url", code.isEmpty() ? "" : "https://finance.eastmoney.com/a/" + code + ".html");
                news.put("intro", item.path("content").asText(""));
                news.put("media_name", item.path("mediaName").asText(""));
                result.add(news);
            }
            return result;
        } catch (Exception e) {
            log.error("解析东方财富新闻响应失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
