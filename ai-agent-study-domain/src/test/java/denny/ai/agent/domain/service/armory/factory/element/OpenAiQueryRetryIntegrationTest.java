package denny.ai.agent.domain.service.armory.factory.element;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.service.armory.AiClientHttpTimeoutConfig;
import denny.ai.agent.domain.service.armory.AiStreamingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiQueryRetryIntegrationTest {

    private static final String TOOL_SSE = """
            data: {"id":"chatcmpl-tool","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_lookup","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]

            """;
    private static final String PARTIAL_SSE = """
            data: {"id":"chatcmpl-partial","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","content":"discarded-partial"},"finish_reason":null}]}

            """;
    private static final String SUCCESS_SSE = """
            data: {"id":"chatcmpl-success","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","content":"final-answer"},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    @Test
    void should_restart_original_query_and_allow_tool_reexecution_when_tool_round_fails()
            throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger toolExecutions = new AtomicInteger();
        List<String> requestBodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange ->
                handle(exchange, requests.incrementAndGet(), requestBodies));
        server.start();

        try {
            ToolCallback tool = FunctionToolCallback.builder("lookup", () -> {
                        toolExecutions.incrementAndGet();
                        return "tool-result";
                    })
                    .description("Returns a deterministic test value")
                    .build();
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model("test-model")
                    .toolCallbacks(tool)
                    .build();
            OpenAiApi api = openAiApi(server.getAddress().getPort());
            OpenAiChatModel delegate = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(options)
                    .build();
            RetryChatModel model = new RetryChatModel(delegate, RetryConfig.builder()
                    .enabled(true)
                    .maxAttempts(2)
                    .initialIntervalMs(0)
                    .maxIntervalMs(0)
                    .build());

            List<String> content = model.stream(new Prompt("run lookup", options))
                    .map(response -> response.getResult().getOutput().getText())
                    .filter(text -> text != null && !text.isBlank())
                    .collectList()
                    .block(Duration.ofSeconds(15));

            assertEquals(List.of("final-answer"), content);
            assertEquals(4, requests.get());
            assertEquals(2, toolExecutions.get());
            assertEquals(json(requestBodies.get(0)), json(requestBodies.get(2)));
            assertEquals(1, json(requestBodies.get(0)).path("messages").size());
            assertTrue(json(requestBodies.get(1)).path("messages").size() > 1);
            assertTrue(json(requestBodies.get(3)).path("messages").size() > 1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void should_use_one_http_request_for_one_query_attempt_without_tools() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = SUCCESS_SSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            OpenAiChatOptions options = OpenAiChatOptions.builder().model("test-model").build();
            OpenAiChatModel delegate = OpenAiChatModel.builder()
                    .openAiApi(openAiApi(server.getAddress().getPort()))
                    .defaultOptions(options)
                    .build();
            RetryChatModel model = new RetryChatModel(delegate, RetryConfig.builder()
                    .enabled(true).maxAttempts(3).initialIntervalMs(0).maxIntervalMs(0).build());

            List<String> content = model.stream(new Prompt("plain query", options))
                    .map(response -> response.getResult().getOutput().getText())
                    .filter(text -> text != null && !text.isBlank())
                    .collectList()
                    .block(Duration.ofSeconds(10));

            assertEquals(List.of("final-answer"), content);
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private OpenAiApi openAiApi(int port) {
        HttpClient client = HttpClient.newBuilder().build();
        WebClient.Builder streamingBuilder = new AiClientHttpTimeoutConfig()
                .aiClientWebClientBuilder(new AiStreamingProperties(), Schedulers.parallel());
        return OpenAiApi.builder()
                .baseUrl("http://127.0.0.1:" + port + "/")
                .apiKey("test-key")
                .completionsPath("v1/chat/completions")
                .restClientBuilder(RestClient.builder()
                        .requestFactory(new JdkClientHttpRequestFactory(client)))
                .webClientBuilder(streamingBuilder)
                .build();
    }

    private void handle(HttpExchange exchange, int requestNumber, List<String> bodies)
            throws IOException {
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        if (requestNumber == 2) {
            byte[] partial = PARTIAL_SSE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, partial.length + 128L);
            exchange.getResponseBody().write(partial);
            exchange.close();
            return;
        }
        byte[] body = (requestNumber == 4 ? SUCCESS_SSE : TOOL_SSE)
                .getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private JsonNode json(String value) throws IOException {
        return new ObjectMapper().readTree(value);
    }
}
