package denny.ai.agent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DashscopeEmbeddingModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void callUsesOpenAiCompatibleEmbeddingRequestBody() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/compatible-mode/v1/embeddings", exchange -> handleEmbedding(exchange, requestBody));
        server.start();

        try {
            String apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1/embeddings";
            DashscopeEmbeddingModel model = new DashscopeEmbeddingModel(apiUrl, "test-key", "text-embedding-v3", 768);

            float[] vector = model.embed("hello");

            assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, vector);

            JsonNode json = objectMapper.readTree(requestBody.get());
            assertEquals("text-embedding-v3", json.get("model").asText());
            assertEquals("hello", json.get("input").get(0).asText());
            assertEquals(768, json.get("dimensions").asInt());
            assertEquals("float", json.get("encoding_format").asText());
            assertFalse(json.has("parameters"));
        } finally {
            server.stop(0);
        }
    }

    private void handleEmbedding(HttpExchange exchange, AtomicReference<String> requestBody) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = """
                {
                  "object": "list",
                  "data": [
                    {
                      "object": "embedding",
                      "index": 0,
                      "embedding": [0.1, 0.2, 0.3]
                    }
                  ],
                  "model": "text-embedding-v3",
                  "usage": {
                    "prompt_tokens": 1,
                    "total_tokens": 1
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
