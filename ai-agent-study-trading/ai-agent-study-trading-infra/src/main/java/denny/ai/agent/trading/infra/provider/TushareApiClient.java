package denny.ai.agent.trading.infra.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * Tushare API HTTP 客户端。
 * <p>
 * 统一 POST 请求到 https://api.tushare.pro，封装请求响应处理。
 */
@Slf4j
public class TushareApiClient {

    private static final String BASE_URL = "https://api.tushare.pro";

    private final String token;
    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 默认构造函数。
     * 仅在需要时才创建默认 RestTemplate（延迟初始化）。
     */
    public TushareApiClient(String token) {
        this.token = token;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 构造函数（允许注入 RestTemplate，测试时使用）。
     */
    public TushareApiClient(String token, RestTemplate restTemplate) {
        this.token = token;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    private RestTemplate getRestTemplate() {
        if (this.restTemplate == null) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5000);
            factory.setReadTimeout(30000);
            this.restTemplate = new RestTemplate(factory);
        }
        return this.restTemplate;
    }

    /**
     * 调用 Tushare API。
     *
     * @param apiName 接口名称，如 "daily"、"stock_basic"
     * @param params 查询参数
     * @param fields 返回字段，逗号分隔，如 "trade_date,open,high,low,close,vol"
     * @return 字段名→值的映射列表，调用失败时返回空列表
     */
    public List<Map<String, String>> call(String apiName, Map<String, Object> params, String fields) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("api_name", apiName);
            requestBody.put("token", token);
            requestBody.put("params", params != null ? params : Collections.emptyMap());
            requestBody.put("fields", fields != null ? fields : "");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            String response = getRestTemplate().postForObject(BASE_URL, entity, String.class);
            return parseResponse(response, fields);
        } catch (Exception e) {
            log.error("Tushare API 调用失败: api_name={}, error={}", apiName, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Map<String, String>> parseResponse(String response, String fields) {
        try {
            TushareResponseDTO dto = objectMapper.readValue(response, TushareResponseDTO.class);

            if (dto.getCode() != 0) {
                log.warn("Tushare API 返回错误: code={}, msg={}", dto.getCode(), dto.getMsg());
                return Collections.emptyList();
            }

            if (dto.getData() == null || dto.getData().getFields() == null
                    || dto.getData().getItems() == null || dto.getData().getItems().isEmpty()) {
                return Collections.emptyList();
            }

            List<String> fieldList = dto.getData().getFields();
            List<List<Object>> items = dto.getData().getItems();

            return items.stream()
                    .map(row -> {
                        Map<String, String> map = new HashMap<>();
                        for (int i = 0; i < fieldList.size() && i < row.size(); i++) {
                            String key = fieldList.get(i);
                            Object value = row.get(i);
                            map.put(key, value != null ? value.toString() : null);
                        }
                        return map;
                    })
                    .toList();
        } catch (Exception e) {
            log.error("解析 Tushare 响应失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
