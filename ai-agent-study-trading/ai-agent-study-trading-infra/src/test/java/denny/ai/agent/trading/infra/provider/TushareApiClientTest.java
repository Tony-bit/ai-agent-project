package denny.ai.agent.trading.infra.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TushareApiClientTest {

    static class SampleDto {
        @JsonProperty("ts_code")
        private String tsCode;

        @JsonProperty("close")
        private String close;

        public String getTsCode() {
            return tsCode;
        }

        public void setTsCode(String tsCode) {
            this.tsCode = tsCode;
        }

        public String getClose() {
            return close;
        }

        public void setClose(String close) {
            this.close = close;
        }
    }

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
        TushareApiClient client = new TushareApiClient("test-token");
        List<Map<String, String>> result = callClientWithInvalidResponse(client, "invalid json {{{");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void call_itemHasNullElement() {
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

    @Test
    void callStrict_success() {
        String responseJson = """
            {
              "code": 0,
              "msg": "",
              "data": {
                "fields": ["ts_code", "trade_date", "close"],
                "items": [
                  ["600000.SH", "20240101", "10.5"]
                ]
              }
            }
            """;

        TushareApiClient client = new TushareApiClient("test-token");
        injectMockRestTemplate(client);
        when(mockRestTemplate.postForObject(eq("https://api.tushare.pro"), any(), eq(String.class)))
                .thenReturn(responseJson);

        List<Map<String, String>> result = client.callStrict(
                "daily", Map.of("ts_code", "600000.SH"), "ts_code,trade_date,close");

        assertEquals(1, result.size());
        assertEquals("600000.SH", result.get(0).get("ts_code"));
    }

    @Test
    void callGenericStrict_success() {
        String responseJson = """
            {
              "code": 0,
              "msg": "",
              "data": {
                "fields": ["ts_code", "close"],
                "items": [
                  ["600000.SH", "10.5"]
                ]
              }
            }
            """;

        TushareApiClient client = new TushareApiClient("test-token");
        injectMockRestTemplate(client);
        when(mockRestTemplate.postForObject(eq("https://api.tushare.pro"), any(), eq(String.class)))
                .thenReturn(responseJson);

        List<SampleDto> result = client.callGenericStrict(
                SampleDto.class, "daily", Map.of("ts_code", "600000.SH"), "ts_code,close");

        assertEquals(1, result.size());
        assertEquals("600000.SH", result.get(0).getTsCode());
        assertEquals("10.5", result.get(0).getClose());
    }

    @Test
    void callStrict_apiErrorThrowsBusinessException() {
        String responseJson = """
            {
              "code": 40101,
              "msg": "invalid token",
              "data": null
            }
            """;

        TushareApiClient client = new TushareApiClient("test-token");
        injectMockRestTemplate(client);
        when(mockRestTemplate.postForObject(eq("https://api.tushare.pro"), any(), eq(String.class)))
                .thenReturn(responseJson);

        TushareApiException exception = assertThrows(TushareApiException.class, () ->
                client.callStrict("stock_basic", Map.of("list_status", "L"), "ts_code,name"));

        assertEquals("stock_basic", exception.getApiName());
        assertEquals(40101, exception.getCode());
        assertEquals("invalid token", exception.getApiMessage());
    }

    @Test
    void callStrict_transportErrorThrowsTransportException() {
        TushareApiClient client = new TushareApiClient("test-token");
        injectMockRestTemplate(client);
        when(mockRestTemplate.postForObject(eq("https://api.tushare.pro"), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("SSL handshake failed"));

        TushareTransportException exception = assertThrows(TushareTransportException.class, () ->
                client.callStrict("stock_basic", Map.of("list_status", "L"), "ts_code,name"));

        assertEquals("stock_basic", exception.getApiName());
        assertTrue(exception.getCause().getMessage().contains("SSL handshake failed"));
    }

    @Test
    void callStrict_protocolErrorThrowsProtocolExceptionWhenJsonInvalid() {
        TushareApiClient client = new TushareApiClient("test-token");
        injectMockRestTemplate(client);
        when(mockRestTemplate.postForObject(eq("https://api.tushare.pro"), any(), eq(String.class)))
                .thenReturn("invalid json");

        TushareProtocolException exception = assertThrows(TushareProtocolException.class, () ->
                client.callStrict("stock_basic", Map.of("list_status", "L"), "ts_code,name"));

        assertEquals("stock_basic", exception.getApiName());
    }

    @Test
    void callStrict_protocolErrorThrowsProtocolExceptionWhenStructureMissing() {
        String responseJson = """
            {
              "code": 0,
              "msg": "",
              "data": {
                "fields": ["ts_code", "name"]
              }
            }
            """;

        TushareApiClient client = new TushareApiClient("test-token");
        injectMockRestTemplate(client);
        when(mockRestTemplate.postForObject(eq("https://api.tushare.pro"), any(), eq(String.class)))
                .thenReturn(responseJson);

        TushareProtocolException exception = assertThrows(TushareProtocolException.class, () ->
                client.callStrict("stock_basic", Map.of("list_status", "L"), "ts_code,name"));

        assertEquals("stock_basic", exception.getApiName());
        assertTrue(exception.getMessage().contains("data.items is missing"));
    }

    @Test
    void callStrict_emptyItemsReturnsEmptyList() {
        String responseJson = """
            {
              "code": 0,
              "msg": "",
              "data": {
                "fields": ["ts_code", "name"],
                "items": []
              }
            }
            """;

        TushareApiClient client = new TushareApiClient("test-token");
        injectMockRestTemplate(client);
        when(mockRestTemplate.postForObject(eq("https://api.tushare.pro"), any(), eq(String.class)))
                .thenReturn(responseJson);

        List<Map<String, String>> result = client.callStrict(
                "stock_basic", Map.of("list_status", "L"), "ts_code,name");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

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
