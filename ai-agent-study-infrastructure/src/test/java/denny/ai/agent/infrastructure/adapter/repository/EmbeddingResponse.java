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
public class EmbeddingResponse {

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
        private List<Embeddings> embeddings;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("input_tokens")
        private Integer inputTokens;

        @JsonProperty("image_tokens")
        private Integer imageTokens;
    }

    @Data
    public static class Embeddings {
        private int index;

        private List<Double> embedding;

        private String type;
    }
}
