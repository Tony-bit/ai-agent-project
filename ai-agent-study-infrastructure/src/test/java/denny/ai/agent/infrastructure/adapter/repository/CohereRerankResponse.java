package denny.ai.agent.infrastructure.adapter.repository;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Cohere Rerank 接口响应对象。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CohereRerankResponse {

    @JsonProperty("status_code")
    private Integer statusCode;

    @JsonProperty("request_id")
    private String requestId;

    private String code;

    private String message;

    private Output output;

    private Usage usage;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Output {
        private List<Result> results;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        private Integer index;

        @JsonProperty("relevance_score")
        private Double relevanceScore;

        private Document document;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Document {
        private String text;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}
