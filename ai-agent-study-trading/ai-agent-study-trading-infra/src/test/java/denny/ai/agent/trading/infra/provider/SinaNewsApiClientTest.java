package denny.ai.agent.trading.infra.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SinaNewsApiClient 单元测试。
 * <p>
 * 通过构造函数注入 mock HttpCaller，验证 URL 构建和响应解析逻辑，
 * 不依赖外部网络。
 */
class SinaNewsApiClientTest {

    private SinaNewsApiClient client;

    @BeforeEach
    void setUp() {
        client = new SinaNewsApiClient(url -> null);
    }

    // ========== URL 构建 ==========

    @Test
    void buildUrl_containsEastmoneyDomainAndJsonpFormat() {
        String url = client.buildUrl("人工智能", 1, 20);

        assertTrue(url.contains("search-api-web.eastmoney.com/search/jsonp"),
                "基础URL应指向东方财富搜索API，实际URL: " + url);
        assertTrue(url.contains("cb=callback"),
                "应为JSONP格式，实际URL: " + url);
        assertTrue(url.contains("param="),
                "应包含param参数，实际URL: " + url);
    }

    @Test
    void buildUrl_paginationParamsEncodedCorrectly() {
        String url = client.buildUrl("芯片", 2, 10);

        assertTrue(url.contains("%22pageIndex%22%3A2"),
                "pageIndex=2 应被编码为 %22pageIndex%22%3A2，实际URL: " + url);
        assertTrue(url.contains("%22pageSize%22%3A10"),
                "pageSize=10 应被编码为 %22pageSize%22%3A10，实际URL: " + url);
    }

    @Test
    void buildUrl_keywordEncodedInInnerParam() {
        String url = client.buildUrl("人工智能", 1, 20);

        assertTrue(url.contains("%E4%BA%BA%E5%B7%A5%E6%99%BA%E8%83%BD"),
                "keyword应在内部JSON中UTF-8编码，实际URL: " + url);
    }

    // ========== 边界情况 ==========

    @Test
    void parseResponse_null_returnsEmpty() {
        List<Map<String, String>> result = client.parseResponse(null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseResponse_invalidJson_returnsEmpty() {
        List<Map<String, String>> result = client.parseResponse("not a json {{{");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseResponse_emptyDataArray_returnsEmpty() {
        String json = "callback({\"result\":{\"cmsArticle\":[]}})";
        List<Map<String, String>> result = client.parseResponse(json);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseResponse_missingDataNode_returnsEmpty() {
        String json = "callback({\"result\":{}})";
        List<Map<String, String>> result = client.parseResponse(json);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseResponse_missingResultNode_returnsEmpty() {
        String json = "callback({})";
        List<Map<String, String>> result = client.parseResponse(json);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseResponse_noCallbackPrefix_returnsEmpty() {
        String json = "{\"result\":{\"cmsArticle\":[]}}";
        List<Map<String, String>> result = client.parseResponse(json);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ========== 响应解析正确性 ==========

    @Test
    void parseResponse_validResponse_parsesCorrectly() {
        String json = """
            callback({
              "result": {
                "cmsArticle": [
                  {
                    "title": "人工智能概念股集体涨停",
                    "date": "2026-04-27 15:17:13",
                    "code": "202604273720036764",
                    "content": "受政策利好影响，AI板块大涨",
                    "mediaName": "市场资讯"
                  },
                  {
                    "title": "芯片行业迎来新机遇",
                    "date": "2026-04-27 10:00:00",
                    "code": "202604271234567890",
                    "content": "多家芯片企业业绩预增",
                    "mediaName": "财经频道"
                  }
                ]
              }
            })
            """;

        List<Map<String, String>> result = client.parseResponse(json);

        assertNotNull(result);
        assertEquals(2, result.size());

        Map<String, String> item1 = result.get(0);
        assertEquals("人工智能概念股集体涨停", item1.get("title"));
        assertEquals("2026-04-27 15:17:13", item1.get("ctime"));
        assertEquals("https://finance.eastmoney.com/a/202604273720036764.html", item1.get("url"));
        assertEquals("受政策利好影响，AI板块大涨", item1.get("intro"));
        assertEquals("市场资讯", item1.get("media_name"));

        Map<String, String> item2 = result.get(1);
        assertEquals("芯片行业迎来新机遇", item2.get("title"));
        assertEquals("财经频道", item2.get("media_name"));
        assertEquals("https://finance.eastmoney.com/a/202604271234567890.html", item2.get("url"));
    }

    @Test
    void parseResponse_emptyCodeField_noUrl() {
        String json = """
            callback({
              "result": {
                "cmsArticle": [
                  {
                    "title": "测试新闻",
                    "date": "2026-04-27 10:00:00",
                    "code": "",
                    "content": "内容",
                    "mediaName": "来源"
                  }
                ]
              }
            })
            """;

        List<Map<String, String>> result = client.parseResponse(json);

        assertEquals(1, result.size());
        assertEquals("", result.get(0).get("url"));
    }

    // ========== search() 方法集成测试 ==========

    @Test
    void search_nullResponse_returnsEmpty() {
        SinaNewsApiClient clientWithMock = new SinaNewsApiClient(url -> null);
        List<Map<String, String>> result = clientWithMock.search("关键字", 1, 20);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void search_exception_returnsEmpty() {
        SinaNewsApiClient clientWithMock = new SinaNewsApiClient(
                url -> { throw new RuntimeException("Connection refused"); });
        List<Map<String, String>> result = clientWithMock.search("关键字", 1, 20);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void search_validJson_returnsResults() {
        String json = "callback({\"result\":{\"cmsArticle\":[{\"title\":\"测试\",\"date\":\"2026-04-27\",\"code\":\"123\",\"content\":\"内容\",\"mediaName\":\"来源\"}]}})";
        SinaNewsApiClient clientWithMock = new SinaNewsApiClient(url -> json);
        List<Map<String, String>> result = clientWithMock.search("测试", 1, 20);

        assertEquals(1, result.size());
        assertEquals("测试", result.get(0).get("title"));
    }
}
