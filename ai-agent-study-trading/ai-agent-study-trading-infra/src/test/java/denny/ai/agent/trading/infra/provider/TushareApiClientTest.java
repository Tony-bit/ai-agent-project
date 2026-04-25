package denny.ai.agent.trading.infra.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TushareApiClient 单元测试。
 */
class TushareApiClientTest {

    private RestTemplate mockRestTemplate;

    @BeforeEach
    void setUp() {
        mockRestTemplate = mock(RestTemplate.class);
    }

    @Test
    void call_success() {
        String responseJson = """
            {
              "code": 0,
              "msg": "",
              "data": {
                "fields": ["ts_code", "trade_date", "close"],
                "items": [
                  ["600000.SH", "20240101", "10.5"],
                  ["600000.SH", "20240102", "10.8"]
                ]
              }
            }
            """;

        TushareApiClient client = new TushareApiClient("test-token");
        List<Map<String, String>> result = callClientWithMockAndResponse(client, responseJson);

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("600000.SH", result.get(0).get("ts_code"));
        assertEquals("10.5", result.get(0).get("close"));
    }

    @Test
    void call_apiError() {
        String responseJson = """
            {
              "code": 40003,
              "msg": "权限不足",
              "data": null
            }
            """;

        TushareApiClient client = new TushareApiClient("test-token");
        List<Map<String, String>> result = callClientWithMockAndResponse(client, responseJson);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void call_networkError() {
        TushareApiClient client = new TushareApiClient("test-token");
        List<Map<String, String>> result = callClientWithMockAndThrow(client, new RuntimeException("Network error"));

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void call_emptyData() {
        String responseJson = """
            {
              "code": 0,
              "msg": "",
              "data": {
                "fields": ["ts_code", "trade_date"],
                "items": []
              }
            }
            """;

        TushareApiClient client = new TushareApiClient("test-token");
        List<Map<String, String>> result = callClientWithMockAndResponse(client, responseJson);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void call_nullFields() {
        // 响应中 data.fields=null，验证不会 NPE
        String responseJson = """
            {
              "code": 0,
              "msg": "",
              "data": {
                "fields": null,
                "items": [["600000.SH", "20240101", "10.5"]]
              }
            }
            """;

        TushareApiClient client = new TushareApiClient("test-token");
        List<Map<String, String>> result = callClientWithMockAndResponse(client, responseJson);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void call_nullItems() {
        // 响应中 data.items=null，验证不会 NPE
        String responseJson = """
            {
              "code": 0,
              "msg": "",
              "data": {
                "fields": ["ts_code", "trade_date", "close"],
                "items": null
              }
            }
            """;

        TushareApiClient client = new TushareApiClient("test-token");
        List<Map<String, String>> result = callClientWithMockAndResponse(client, responseJson);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void call_dataNull() {
        // 响应中 "data": null
        String responseJson = """
            {
              "code": 0,
              "msg": "",
              "data": null
            }
            """;

        TushareApiClient client = new TushareApiClient("test-token");
        List<Map<String, String>> result = callClientWithMockAndResponse(client, responseJson);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void call_invalidJson() {
        // 响应为非法 JSON 字符串，验证异常被捕获返回空列表
        TushareApiClient client = new TushareApiClient("test-token");
        List<Map<String, String>> result = callClientWithInvalidResponse(client, "invalid json {{{");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void call_itemHasNullElement() {
        // items 中某行包含 null 元素，如 ["600000.SH", null, "10.5"]
        String responseJson = """
            {
              "code": 0,
              "msg": "",
              "data": {
                "fields": ["ts_code", "trade_date", "close"],
                "items": [["600000.SH", null, "10.5"]]
              }
            }
            """;

        TushareApiClient client = new TushareApiClient("test-token");
        List<Map<String, String>> result = callClientWithMockAndResponse(client, responseJson);

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("600000.SH", result.get(0).get("ts_code"));
        assertNull(result.get(0).get("trade_date"));
        assertEquals("10.5", result.get(0).get("close"));
    }

    // ========== Helper methods using reflection ==========

    private List<Map<String, String>> callClientWithMockAndResponse(TushareApiClient client, String responseJson) {
        injectMockRestTemplate(client);
        when(mockRestTemplate.postForObject(
                eq("https://api.tushare.pro"),
                any(),
                eq(String.class)
        )).thenReturn(responseJson);

        Map<String, Object> params = new HashMap<>();
        params.put("ts_code", "600000.SH");
        return client.call("daily", params, "ts_code,trade_date,close");
    }

    private List<Map<String, String>> callClientWithMockAndThrow(TushareApiClient client, Exception ex) {
        injectMockRestTemplate(client);
        when(mockRestTemplate.postForObject(
                eq("https://api.tushare.pro"),
                any(),
                eq(String.class)
        )).thenThrow(ex);

        Map<String, Object> params = new HashMap<>();
        params.put("ts_code", "600000.SH");
        return client.call("daily", params, "ts_code,trade_date,close");
    }

    private List<Map<String, String>> callClientWithInvalidResponse(TushareApiClient client, String invalidResponse) {
        injectMockRestTemplate(client);
        when(mockRestTemplate.postForObject(
                eq("https://api.tushare.pro"),
                any(),
                eq(String.class)
        )).thenReturn(invalidResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("ts_code", "600000.SH");
        return client.call("daily", params, "ts_code,trade_date,close");
    }

    private void injectMockRestTemplate(TushareApiClient client) {
        try {
            var restTemplateField = TushareApiClient.class.getDeclaredField("restTemplate");
            restTemplateField.setAccessible(true);
            restTemplateField.set(client, mockRestTemplate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
