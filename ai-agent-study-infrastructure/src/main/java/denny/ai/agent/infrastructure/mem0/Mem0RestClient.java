package denny.ai.agent.infrastructure.mem0;

import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.infrastructure.mem0.dto.Mem0Dtos;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 自研 Mem0 HTTP Client，直接调用 Mem0 REST API。
 */
@Slf4j
public class Mem0RestClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final ObjectMapper objectMapper;

    public Mem0RestClient(RestTemplate restTemplate, String baseUrl, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.objectMapper = objectMapper;
    }

    public void addMemory(MemoryCreate request) {
        try {
            HttpEntity<Map<String, Object>> entity = jsonEntity(toRequestBody(request));
            restTemplate.exchange(baseUrl + "/memories", HttpMethod.POST, entity, Map.class);
            log.info("Mem0 addMemory 成功, userId={}", request.getUser_id());
        } catch (RestClientException e) {
            log.error("Mem0 addMemory 失败, userId={}", request.getUser_id(), e);
            throw new RuntimeException("Mem0 addMemory failed", e);
        }
    }

    public Mem0ServerResp searchMemories(SearchRequest request) {
        try {
            HttpEntity<Map<String, Object>> entity = jsonEntity(toRequestBody(request));
            ResponseEntity<Mem0Dtos.SearchResponse> response = restTemplate.exchange(
                    baseUrl + "/search",
                    HttpMethod.POST,
                    entity,
                    Mem0Dtos.SearchResponse.class
            );
            return Mem0ServerResp.fromSearchResponse(response.getBody());
        } catch (RestClientException e) {
            log.error("Mem0 searchMemories 失败, userId={}", request.getUser_id(), e);
            throw new RuntimeException("Mem0 searchMemories failed", e);
        }
    }

    public Object getAllMemories(String userId, String agentId, String runId) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/memories")
                .queryParamIfPresent("user_id", Optional.ofNullable(userId))
                .queryParamIfPresent("agent_id", Optional.ofNullable(agentId))
                .queryParamIfPresent("run_id", Optional.ofNullable(runId))
                .build()
                .toUriString();

        try {
            ResponseEntity<Object> response = restTemplate.getForEntity(url, Object.class);
            return response.getBody();
        } catch (RestClientException e) {
            log.error("Mem0 getAllMemories 失败, userId={}", userId, e);
            throw new RuntimeException("Mem0 getAllMemories failed", e);
        }
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Map<String, Object> toRequestBody(Object request) {
        Map<String, Object> body = objectMapper.convertValue(request, Map.class);
        body.values().removeIf(value -> value == null);
        return body;
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Mem0 baseUrl must not be blank");
        }
        return url.replaceAll("/+$", "");
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryCreate {
        private List<Mem0Dtos.Message> messages;
        private String user_id;
        private String agent_id;
        private String run_id;
        private Map<String, Object> metadata;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchRequest {
        private String query;
        private String user_id;
        private String run_id;
        private String agent_id;
        private Map<String, Object> filters;
        private Integer limit;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Mem0ServerResp {
        private List<Mem0Results> results;

        public static Mem0ServerResp fromSearchResponse(Mem0Dtos.SearchResponse response) {
            if (response == null || response.getResults() == null) {
                return new Mem0ServerResp(Collections.emptyList());
            }
            List<Mem0Results> results = response.getResults().stream()
                    .map(result -> new Mem0Results(result.getMemory(), result.getMetadata(), result.getScore()))
                    .toList();
            return new Mem0ServerResp(results);
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Mem0Results {
            private String memory;
            private Map<String, Object> metadata;
            private Double score;
        }
    }
}
