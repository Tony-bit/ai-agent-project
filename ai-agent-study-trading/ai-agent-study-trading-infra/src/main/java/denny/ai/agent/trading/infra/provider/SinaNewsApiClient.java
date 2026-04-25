package denny.ai.agent.trading.infra.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.util.*;

/**
 * 新浪财经新闻搜索 HTTP 客户端。
 * <p>
 * 调用 feed.mix.sina.com.cn 获取财经新闻，支持关键字搜索。
 * 完全免费，无需 Token。
 */
@Slf4j
public class SinaNewsApiClient {

    private static final String BASE_URL = "https://feed.mix.sina.com.cn/api/roll/get";
    private static final String REFERER = "https://finance.sina.com.cn/";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public SinaNewsApiClient() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public List<Map<String, String>> search(String keyword, int page, int pageSize) {
        try {
            String encodedKeyword = URLEncoder.encode(keyword, "UTF-8");
            String url = BASE_URL + "?pageid=153&lid=2509&k=" + encodedKeyword
                    + "&num=" + pageSize + "&page=" + page;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Referer", REFERER);
            headers.set("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            String response = restTemplate.getForObject(url, String.class);

            return parseResponse(response);
        } catch (Exception e) {
            log.error("新浪财经新闻搜索失败: keyword={}, error={}", keyword, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Map<String, String>> parseResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode dataNode = root.path("result").path("data");
            if (!dataNode.isArray()) {
                return Collections.emptyList();
            }

            List<Map<String, String>> result = new ArrayList<>();
            for (JsonNode item : dataNode) {
                Map<String, String> news = new LinkedHashMap<>();
                news.put("title", item.path("title").asText(""));
                news.put("ctime", item.path("ctime").asText(""));
                news.put("url", item.path("url").asText(""));
                news.put("intro", item.path("intro").asText(""));
                news.put("media_name", item.path("media_name").asText(""));
                result.add(news);
            }
            return result;
        } catch (Exception e) {
            log.error("解析新浪新闻响应失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
