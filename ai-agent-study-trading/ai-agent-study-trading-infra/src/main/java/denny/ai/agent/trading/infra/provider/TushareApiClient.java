package denny.ai.agent.trading.infra.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.*;

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

    public TushareApiClient(String token) {
        this.token = token;
        this.objectMapper = new ObjectMapper();
    }

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
     * 通用 Map 返回（保留，向下兼容）。
     *
     * @deprecated 建议使用 {@link #callGeneric(Class, String, Map, String)}
     */
    @Deprecated
    public List<Map<String, String>> call(String apiName, Map<String, Object> params, String fields) {
        try {
            String response = doPost(apiName, params, fields);
            return parseToMapList(response);
        } catch (Exception e) {
            log.error("Tushare API 调用失败: api_name={}, error={}", apiName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 泛型调用，将响应映射到指定的 DTO。
     * <p>
     * 内部解析为 List&lt;Map&lt;String, String&gt;&gt;，然后通过 Jackson convertValue 映射到 DTO。
     * DTO 类需标注 @JsonNaming(SnakeCaseStrategy.class) 以自动处理字段名映射。
     *
     * @param dtoClass DTO 子类类型，必须标注 @JsonNaming(SnakeCaseStrategy.class)
     * @param <T>      DTO 类型
     * @return DTO 列表
     */
    public <T> List<T> callGeneric(Class<T> dtoClass, String apiName,
                                   Map<String, Object> params, String fields) {
        try {
            String response = doPost(apiName, params, fields);
            List<Map<String, String>> mapList = parseToMapList(response);
            List<T> result = new ArrayList<>(mapList.size());
            for (Map<String, String> row : mapList) {
                T dto = objectMapper.convertValue(row, dtoClass);
                result.add(dto);
            }
            return result;
        } catch (Exception e) {
            log.error("Tushare API 调用失败: api_name={}, error={}", apiName, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String doPost(String apiName, Map<String, Object> params, String fields) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("api_name", apiName);
        requestBody.put("token", token);
        requestBody.put("params", params != null ? params : Collections.emptyMap());
        requestBody.put("fields", fields != null ? fields : "");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        return getRestTemplate().postForObject(BASE_URL, entity, String.class);
    }

    private List<Map<String, String>> parseToMapList(String response) {
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
