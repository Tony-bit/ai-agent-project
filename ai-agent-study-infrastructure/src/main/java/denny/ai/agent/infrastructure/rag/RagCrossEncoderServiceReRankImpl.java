package denny.ai.agent.infrastructure.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rerank API 实现（适配 DashScope qwen3-rerank 响应格式）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rag.rerank.provider", havingValue = "rank")
public class RagCrossEncoderServiceReRankImpl implements RagCrossEncoderService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${rag.rerank.config.base-url:https://dashscope.aliyuncs.com}")
    private String baseUrl;

    @Value("${rag.rerank.config.api-key:}")
    private String apiKey;

    @Value("${rag.rerank.config.model:qwen3-rerank}")
    private String model;

    @Value("${rag.rerank.config.timeout-ms:8000}")
    private int timeoutMs;

    public RagCrossEncoderServiceReRankImpl() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<Double> score(String query, List<String> passages) {
        if (passages == null || passages.isEmpty()) {
            return Collections.emptyList();
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Rerank 已启用但未配置 api-key，降级为零分，candidateSize={}", passages.size());
            return zeros(passages.size());
        }

        try {
            DashscopeRerankRequest req = new DashscopeRerankRequest();
            req.setModel(model);

            DashscopeRerankRequest.Input input = new DashscopeRerankRequest.Input();
            input.setQuery(query);
            input.setDocuments(passages);
            req.setInput(input);

            DashscopeRerankRequest.Parameters parameters = new DashscopeRerankRequest.Parameters();
            parameters.setTopN(passages.size());
            req.setParameters(parameters);

            String body = objectMapper.writeValueAsString(req);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/services/rerank/text-rerank/text-rerank"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Rerank 调用失败，status={}, body={}", response.statusCode(), response.body());
                return zeros(passages.size());
            }

            DashscopeRerankResponse rerankResponse = objectMapper.readValue(response.body(), DashscopeRerankResponse.class);
            return convertScores(rerankResponse, passages.size());
        } catch (Exception e) {
            log.warn("Rerank 调用异常，降级为零分", e);
            return zeros(passages.size());
        }
    }

    private List<Double> convertScores(DashscopeRerankResponse resp, int candidateSize) {
        List<Double> scores = new ArrayList<>(Collections.nCopies(candidateSize, 0.0d));
        if (resp == null || resp.getOutput() == null || resp.getOutput().getResults() == null) {
            return scores;
        }

        for (DashscopeRerankResponse.Result r : resp.getOutput().getResults()) {
            if (r.getIndex() != null && r.getIndex() >= 0 && r.getIndex() < candidateSize) {
                scores.set(r.getIndex(), r.getRelevanceScore() == null ? 0.0d : r.getRelevanceScore());
            }
        }
        return scores;
    }

    private List<Double> zeros(int size) {
        return new ArrayList<>(Collections.nCopies(size, 0.0d));
    }

    @Data
    private static class DashscopeRerankRequest {
        private String model;
        private Input input;
        private Parameters parameters;

        @Data
        private static class Input {
            private String query;
            private List<String> documents;
        }

        @Data
        private static class Parameters {
            @JsonProperty("top_n")
            private Integer topN;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class DashscopeRerankResponse {
        private Output output;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class Output {
            private List<Result> results;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class Result {
            private Integer index;
            @JsonProperty("relevance_score")
            private Double relevanceScore;
        }
    }
}
