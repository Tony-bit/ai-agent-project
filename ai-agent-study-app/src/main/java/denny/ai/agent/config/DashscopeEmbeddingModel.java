package denny.ai.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public class DashscopeEmbeddingModel implements EmbeddingModel {

    private static final String DEFAULT_ENCODING_FORMAT = "float";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final Integer dimension;

    public DashscopeEmbeddingModel(String apiUrl, String apiKey, String model, Integer dimension) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request == null ? Collections.emptyList() : request.getInstructions();
        if (instructions == null || instructions.isEmpty()) {
            return new EmbeddingResponse(Collections.emptyList());
        }

        try {
            DashscopeEmbeddingRequest req = new DashscopeEmbeddingRequest();
            req.setModel(model);
            req.setInput(new ArrayList<>(instructions));
            if (dimension != null && dimension > 0) {
                req.setDimensions(dimension);
            }
            req.setEncodingFormat(DEFAULT_ENCODING_FORMAT);

            String body = objectMapper.writeValueAsString(req);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("DashScope embedding 调用失败, url={}, model={}, status={}, body={}",
                        apiUrl, model, response.statusCode(), response.body());
                throw new IllegalStateException("DashScope embedding 调用失败, url=" + apiUrl + ", model=" + model + ", status=" + response.statusCode() + ", body=" + response.body());
            }

            DashscopeEmbeddingResponse embeddingResponse = objectMapper.readValue(response.body(), DashscopeEmbeddingResponse.class);
            if (embeddingResponse == null || embeddingResponse.getData() == null) {
                throw new IllegalStateException("DashScope embedding 响应缺少 data");
            }

            List<Embedding> results = new ArrayList<>();
            for (DashscopeEmbeddingResponse.EmbeddingItem item : embeddingResponse.getData()) {
                if (item == null || item.getEmbedding() == null || item.getEmbedding().isEmpty()) {
                    continue;
                }

                float[] vector = new float[item.getEmbedding().size()];
                for (int i = 0; i < item.getEmbedding().size(); i++) {
                    vector[i] = item.getEmbedding().get(i).floatValue();
                }
                results.add(new Embedding(vector, item.getIndex()));
            }

            if (results.isEmpty()) {
                throw new IllegalStateException("DashScope embedding 响应中无可用向量");
            }

            return new EmbeddingResponse(results);
        } catch (Exception e) {
            log.error("DashScope embedding 调用异常, url={}, model={}, dimension={}", apiUrl, model, dimension, e);
            throw new RuntimeException("DashScope embedding 调用异常, url=" + apiUrl + ", model=" + model + ", dimension=" + dimension, e);
        }
    }

    @Override
    public float[] embed(Document document) {
        if (document == null || document.getText() == null) {
            return new float[0];
        }
        return embed(document.getText());
    }

    @Data
    private static class DashscopeEmbeddingRequest {
        private String model;
        private List<String> input;
        private Integer dimensions;

        @JsonProperty("encoding_format")
        private String encodingFormat;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class DashscopeEmbeddingResponse {
        private List<EmbeddingItem> data;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class EmbeddingItem {
            private Integer index;

            private List<Double> embedding;
        }
    }
}
