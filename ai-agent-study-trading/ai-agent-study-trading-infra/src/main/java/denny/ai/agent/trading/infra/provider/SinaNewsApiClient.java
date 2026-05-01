package denny.ai.agent.trading.infra.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
            org.springframework.http.HttpEntity<org.springframework.http.HttpHeaders> entity =
                    new org.springframework.http.HttpEntity<>(headers);
            return new org.springframework.web.client.RestTemplate()
                    .exchange(java.net.URI.create(url), org.springframework.http.HttpMethod.GET, entity, String.class)
                    .getBody();
        });
    }

    public SinaNewsApiClient(HttpCaller httpCaller) {
        this.httpCaller = httpCaller;
        this.objectMapper = new ObjectMapper();
    }

    // ========== 请求/响应 POJO ==========

    /**
     * 东方财富搜索接口的请求参数结构（JSON）。
     * <p>
     * 原先手工拼接 JSON 字符串容易因特殊字符（如 +、空格）导致 URL 编码错误，
     * 改用 POJO + ObjectMapper 序列化，结构清晰、不易出错。
     */
    private static class SearchRequest {
        public String uid = "";
        public String keyword;
        public List<String> type = List.of("cmsArticle");
        public String client = "web";
        public String clientType = "web";
        public String clientVersion = "curr";
        public CmsArticleParam param;

        public SearchRequest(String keyword, int pageIndex, int pageSize) {
            this.keyword = keyword;
            this.param = new CmsArticleParam(pageIndex, pageSize);
        }
    }

    private static class CmsArticleParam {
        public CmsArticle search = new CmsArticle();

        public CmsArticleParam(int pageIndex, int pageSize) {
            this.search.pageIndex = pageIndex;
            this.search.pageSize = pageSize;
        }
    }

    private static class CmsArticle {
        public String searchScope = "default";
        public String sort = "default";
        public int pageIndex;
        public int pageSize;
    }

    // ========== 公开方法 ==========

    /**
     * 构建搜索请求 URL。
     */
    String buildUrl(String keyword, int page, int pageSize) throws Exception {
        SearchRequest request = new SearchRequest(keyword, page, pageSize);
        String innerParam = objectMapper.writeValueAsString(request);
        String encodedParam = URLEncoder.encode(innerParam, StandardCharsets.UTF_8).replace("+", "%20");
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
