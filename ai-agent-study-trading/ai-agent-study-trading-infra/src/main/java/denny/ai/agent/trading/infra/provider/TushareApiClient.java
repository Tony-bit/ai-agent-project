package denny.ai.agent.trading.infra.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tushare API HTTP client.
 *
 * Keeps the legacy empty-list fallback for existing callers while exposing
 * strict methods for callers that need explicit error classification.
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
     * Legacy map-based call with empty-list downgrade.
     *
     * @deprecated Prefer {@link #callStrict(String, Map, String)} or
     * {@link #callGenericStrict(Class, String, Map, String)}.
     */
    @Deprecated
    public List<Map<String, String>> call(String apiName, Map<String, Object> params, String fields) {
        try {
            return callStrict(apiName, params, fields);
        } catch (Exception e) {
            log.error("Tushare API call failed: api_name={}, error={}", apiName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Legacy generic call with empty-list downgrade.
     */
    public <T> List<T> callGeneric(Class<T> dtoClass, String apiName,
                                   Map<String, Object> params, String fields) {
        try {
            return callGenericStrict(dtoClass, apiName, params, fields);
        } catch (Exception e) {
            log.error("Tushare API call failed: api_name={}, error={}", apiName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Strict map-based call.
     */
    public List<Map<String, String>> callStrict(String apiName, Map<String, Object> params, String fields) {
        String response = doPost(apiName, params, fields);
        return parseToMapListStrict(apiName, response);
    }

    /**
     * Strict generic call.
     */
    public <T> List<T> callGenericStrict(Class<T> dtoClass, String apiName,
                                         Map<String, Object> params, String fields) {
        List<Map<String, String>> mapList = callStrict(apiName, params, fields);
        List<T> result = new ArrayList<>(mapList.size());
        for (Map<String, String> row : mapList) {
            try {
                result.add(objectMapper.convertValue(row, dtoClass));
            } catch (IllegalArgumentException e) {
                throw new TushareProtocolException(apiName, "DTO conversion failed: " + e.getMessage(), e);
            }
        }
        return result;
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

        try {
            return getRestTemplate().postForObject(BASE_URL, entity, String.class);
        } catch (RestClientException e) {
            throw new TushareTransportException(apiName, e);
        }
    }

    private List<Map<String, String>> parseToMapListStrict(String apiName, String response) {
        try {
            if (response == null || response.isBlank()) {
                throw new TushareProtocolException(apiName, "response body is empty");
            }
            TushareResponseDTO dto = objectMapper.readValue(response, TushareResponseDTO.class);
            if (dto.getCode() != 0) {
                throw new TushareApiException(apiName, dto.getCode(), dto.getMsg());
            }
            if (dto.getData() == null) {
                throw new TushareProtocolException(apiName, "data is missing");
            }

            List<String> fieldList = dto.getData().getFields();
            List<List<Object>> items = dto.getData().getItems();
            if (fieldList == null) {
                throw new TushareProtocolException(apiName, "data.fields is missing");
            }
            if (items == null) {
                throw new TushareProtocolException(apiName, "data.items is missing");
            }
            if (items.isEmpty()) {
                return Collections.emptyList();
            }

            return items.stream()
                    .map(row -> {
                        if (row == null) {
                            throw new TushareProtocolException(apiName, "row is null");
                        }
                        if (row.size() != fieldList.size()) {
                            throw new TushareProtocolException(apiName,
                                    "row/field size mismatch: fields=" + fieldList.size() + ", row=" + row.size());
                        }
                        Map<String, String> map = new HashMap<>();
                        for (int i = 0; i < fieldList.size(); i++) {
                            String key = fieldList.get(i);
                            Object value = row.get(i);
                            map.put(key, value != null ? value.toString() : null);
                        }
                        return map;
                    })
                    .toList();
        } catch (TushareApiException | TushareProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new TushareProtocolException(apiName, "failed to parse response", e);
        }
    }
}
