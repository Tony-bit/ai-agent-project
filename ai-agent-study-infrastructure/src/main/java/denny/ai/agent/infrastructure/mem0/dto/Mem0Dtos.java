package denny.ai.agent.infrastructure.mem0.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Mem0 REST API 请求与响应 DTO。
 */
public class Mem0Dtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;
        private String content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MemoryCreateRequest {
        private List<Message> messages;
        private String user_id;
        private String agent_id;
        private String run_id;
        private Map<String, Object> metadata;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
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
    public static class SearchResponse {
        private List<Mem0Result> results;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Mem0Result {
        private String memory;
        private Map<String, Object> metadata;
        private Double score;
    }
}
