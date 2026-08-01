package denny.ai.agent.domain.service.armory.factory.element;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.service.armory.AiClientHttpTimeoutConfig;
import denny.ai.agent.domain.service.armory.AiStreamingProperties;
import denny.ai.agent.domain.service.armory.stream.FirstStreamChunkTimeoutException;
import denny.ai.agent.domain.service.armory.stream.StreamChunkIdleTimeoutException;
import denny.ai.agent.domain.service.armory.stream.StreamTimeoutRetryMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiStreamTimeoutQueryRetryIntegrationTest {

    private static final String PARTIAL_SSE = """
            data: {"id":"partial","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","content":"discarded-partial"},"finish_reason":null}]}

            """;
    private static final String SUCCESS_SSE = """
            data: {"id":"success","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","content":"final-answer"},"finish_reason":"stop"}]}

            data: [DONE]

            """;
    private static final String TOOL_SSE = """
            data: {"id":"tool","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_lookup","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]

            """;

    @Test
    void should_detect_timeout_without_retry_when_switch_is_disabled() throws Exception {
        try (ServerFixture fixture = server((exchange, request) -> {
            sleep(650);
            closeQuietly(exchange);
        })) {
            AtomicInteger subscriptions = new AtomicInteger();
            RetryChatModel model = model(fixture, options(), false, subscriptions);

            StepVerifier.create(model.stream(new Prompt("question", options())))
                    .expectErrorMatches(error -> hasCause(error,
                            FirstStreamChunkTimeoutException.class))
                    .verify(Duration.ofSeconds(3));

            assertEquals(1, subscriptions.get());
            assertEquals(1, fixture.requests.get());
        }
    }

    @Test
    void should_retry_from_fresh_query_when_headers_timeout() throws Exception {
        verifyFreshRetry((exchange, request) -> {
            if (request == 1) {
                sleep(650);
                closeQuietly(exchange);
            } else {
                writeSse(exchange, SUCCESS_SSE);
            }
        });
    }

    @Test
    void should_retry_from_fresh_query_when_first_body_chunk_timeout() throws Exception {
        verifyFreshRetry((exchange, request) -> {
            if (request == 1) {
                openChunked(exchange);
                sleep(650);
                closeQuietly(exchange);
            } else {
                writeSse(exchange, SUCCESS_SSE);
            }
        });
    }

    @Test
    void should_retry_from_fresh_query_when_body_becomes_idle() throws Exception {
        verifyFreshRetry((exchange, request) -> {
            if (request == 1) {
                openChunked(exchange);
                exchange.getResponseBody().write(PARTIAL_SSE.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                sleep(700);
                closeQuietly(exchange);
            } else {
                writeSse(exchange, SUCCESS_SSE);
            }
        });
    }

    @Test
    void should_restart_entry_query_and_allow_tool_reexecution_after_second_round_timeout()
            throws Exception {
        AtomicInteger toolExecutions = new AtomicInteger();
        List<String> bodies = new CopyOnWriteArrayList<>();
        try (ServerFixture fixture = server((exchange, request) -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            if (request == 2) {
                openChunked(exchange);
                exchange.getResponseBody().write(PARTIAL_SSE.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                sleep(700);
                closeQuietly(exchange);
            } else {
                writeSse(exchange, request == 4 ? SUCCESS_SSE : TOOL_SSE);
            }
        })) {
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
            AtomicInteger subscriptions = new AtomicInteger();
            RetryChatModel model = model(fixture, options, true, subscriptions);

            List<String> content;
            try {
                content = content(model, new Prompt("run lookup", options));
            } catch (RuntimeException error) {
                throw new AssertionError("tool retry failed: subscriptions="
                        + subscriptions.get() + ", requests=" + fixture.requests.get()
                        + ", toolExecutions=" + toolExecutions.get(), error);
            }

            assertEquals(List.of("final-answer"), content);
            assertEquals(2, subscriptions.get());
            assertEquals(4, fixture.requests.get());
            assertEquals(2, toolExecutions.get());
            assertEquals(json(bodies.get(0)), json(bodies.get(2)));
            assertTrue(json(bodies.get(1)).path("messages").size() > 1);
            assertTrue(json(bodies.get(3)).path("messages").size() > 1);
        }
    }

    private void verifyFreshRetry(Handler handler) throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        try (ServerFixture fixture = server((exchange, request) -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            handler.handle(exchange, request);
        })) {
            AtomicInteger subscriptions = new AtomicInteger();
            RetryChatModel model = model(fixture, options(), true, subscriptions);

            List<String> content = content(model, new Prompt("question", options()));

            assertEquals(List.of("final-answer"), content);
            assertEquals(2, subscriptions.get());
            assertEquals(2, fixture.requests.get());
            assertEquals(json(bodies.get(0)), json(bodies.get(1)));
        }
    }

    private RetryChatModel model(ServerFixture fixture, OpenAiChatOptions options,
                                 boolean timeoutRetry, AtomicInteger subscriptions) {
        OpenAiChatModel openAi = OpenAiChatModel.builder()
                .openAiApi(openAiApi(fixture.port(), fixture.properties, fixture.scheduler))
                .defaultOptions(options)
                .build();
        ChatModel counting = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return openAi.call(prompt);
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                subscriptions.incrementAndGet();
                return openAi.stream(prompt);
            }
        };
        RetryConfig retry = RetryConfig.builder()
                .enabled(true)
                .retryOnStreamTimeout(timeoutRetry)
                .maxAttempts(2)
                .initialIntervalMs(0)
                .maxIntervalMs(0)
                .build();
        return new RetryChatModel(counting, retry, null, null, null,
                fixture.properties.resolve(null), "test-model", () -> 0L,
                new StreamTimeoutRetryMetrics(null));
    }

    private OpenAiApi openAiApi(int port, AiStreamingProperties properties,
                                Scheduler scheduler) {
        HttpClient client = HttpClient.newBuilder().build();
        WebClient.Builder streamingBuilder = new AiClientHttpTimeoutConfig()
                .aiClientWebClientBuilder(properties, scheduler);
        return OpenAiApi.builder()
                .baseUrl("http://127.0.0.1:" + port + "/")
                .apiKey("test-key")
                .completionsPath("v1/chat/completions")
                .restClientBuilder(RestClient.builder()
                        .requestFactory(new JdkClientHttpRequestFactory(client)))
                .webClientBuilder(streamingBuilder)
                .build();
    }

    private List<String> content(RetryChatModel model, Prompt prompt) {
        return model.stream(prompt)
                .map(response -> response.getResult().getOutput().getText())
                .filter(text -> text != null && !text.isBlank())
                .collectList()
                .block(Duration.ofSeconds(5));
    }

    private OpenAiChatOptions options() {
        return OpenAiChatOptions.builder().model("test-model").build();
    }

    private ServerFixture server(Handler handler) throws IOException {
        AiStreamingProperties properties = new AiStreamingProperties();
        properties.setConnectTimeout(Duration.ofMillis(100));
        properties.setFirstChunkTimeout(Duration.ofMillis(300));
        properties.setStallThreshold(Duration.ofMillis(150));
        properties.setChunkIdleTimeout(Duration.ofMillis(300));
        properties.setQueryAttemptTimeout(Duration.ofMillis(1500));
        properties.validate();
        Scheduler scheduler = Schedulers.newParallel("timeout-integration", 2);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            int request = requests.incrementAndGet();
            try {
                handler.handle(exchange, request);
            } catch (Exception ignored) {
                closeQuietly(exchange);
            }
        });
        server.start();
        return new ServerFixture(server, executor, scheduler, properties, requests);
    }

    private void openChunked(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().flush();
    }

    private void writeSse(HttpExchange exchange, String value) throws IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private JsonNode json(String value) throws IOException {
        return new ObjectMapper().readTree(value);
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(HttpExchange exchange) {
        try {
            exchange.close();
        } catch (Exception ignored) {
            // Best-effort cleanup after the client cancels the exchange.
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange, int requestNumber) throws Exception;
    }

    private record ServerFixture(HttpServer server, ExecutorService executor,
                                 Scheduler scheduler, AiStreamingProperties properties,
                                 AtomicInteger requests) implements AutoCloseable {
        private int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            scheduler.dispose();
        }
    }
}
