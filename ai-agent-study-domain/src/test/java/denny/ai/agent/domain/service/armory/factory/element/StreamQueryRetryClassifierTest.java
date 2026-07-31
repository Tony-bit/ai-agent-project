package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.service.auto.step.ClientDisconnectedException;
import denny.ai.agent.domain.service.armory.stream.FirstStreamChunkTimeoutException;
import denny.ai.agent.domain.service.armory.stream.LlmQueryAttemptTimeoutException;
import denny.ai.agent.domain.service.armory.stream.StreamChunkIdleTimeoutException;
import denny.ai.agent.domain.service.armory.stream.StreamTimeoutType;
import denny.ai.agent.domain.service.armory.stream.TimeoutDeadlineOwner;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamQueryRetryClassifierTest {

    @Test
    void should_expose_structured_stream_timeout_types_through_cause_chain() {
        StreamQueryRetryClassifier classifier = classifier(List.of(), List.of());

        assertEquals(Optional.of(StreamTimeoutType.FIRST_CHUNK),
                classifier.streamTimeoutType(firstChunkTimeout()));
        assertEquals(Optional.of(StreamTimeoutType.CHUNK_IDLE),
                classifier.streamTimeoutType(new RuntimeException("wrapped", chunkIdleTimeout())));
        assertEquals(Optional.empty(), classifier.streamTimeoutType(
                new RuntimeException("idle timeout 90 with status 429")));
        assertEquals(Optional.empty(), classifier.streamTimeoutType(queryAttemptTimeout()));
    }

    @Test
    void should_expose_safety_exclusions_before_recovery_facts() {
        StreamQueryRetryClassifier classifier = classifier(List.of(), List.of());
        List<Throwable> exclusions = List.of(
                new CancellationException("cancelled"),
                new ClientDisconnectedException("disconnected"),
                new ToolExecutionException(ToolDefinition.builder().name("tool")
                        .description("test").inputSchema("{}").build(),
                        new EOFException("connection reset")),
                new TestDecodingException("decode", null),
                new ResponseValidationException(
                        ResponseValidationFailureType.JSON_PARSE_ERROR, "invalid"),
                new TimeoutException("timeout"),
                new SocketTimeoutException("socket timeout"),
                new HttpTimeoutException("http timeout"),
                http(401), http(403));

        for (Throwable exclusion : exclusions) {
            assertTrue(classifier.isSafetyExcluded(
                    new RuntimeException("wrapped", exclusion)), exclusion.getClass().getName());
        }
        assertTrue(classifier.isSafetyExcluded(new RuntimeException(
                "wrapped", new RuntimeException(queryAttemptTimeout()))));
        FirstStreamChunkTimeoutException mixedTimeout = firstChunkTimeout();
        mixedTimeout.initCause(queryAttemptTimeout());
        assertTrue(classifier.isSafetyExcluded(new RuntimeException("wrapped", mixedTimeout)));
        assertFalse(classifier.isSafetyExcluded(firstChunkTimeout()));
        assertFalse(classifier.isSafetyExcluded(chunkIdleTimeout()));
    }

    @Test
    void should_expose_veto_and_definite_non_retryable_http_facts() {
        StreamQueryRetryClassifier veto = classifier(List.of(), List.of("1261"));

        assertTrue(veto.matchesNonRetryableCode(new RuntimeException("wrapped",
                http(400, "{\"error\":{\"code\":\"1261\"}}"))));
        assertTrue(classifier(List.of(), List.of()).isDefiniteNonRetryable4xx(http(400)));
        assertFalse(classifier(List.of(), List.of()).isDefiniteNonRetryable4xx(http(429)));
        assertFalse(classifier(List.of(), List.of()).isDefiniteNonRetryable4xx(http(503)));
    }

    @Test
    void should_retry_only_supported_structured_http_statuses() {
        StreamQueryRetryClassifier classifier = classifier(List.of(), List.of());

        for (int status : List.of(429, 500, 502, 503, 504)) {
            assertTrue(classifier.isRetryable(http(status)), "status=" + status);
        }
        for (int status : List.of(400, 401, 403, 404, 408, 409, 422, 529)) {
            assertFalse(classifier.isRetryable(http(status)), "status=" + status);
        }
    }

    @Test
    void should_apply_hard_exclusion_before_codes_and_transport_causes() {
        StreamQueryRetryClassifier classifier = classifier(List.of("429"), List.of());

        assertFalse(classifier.isRetryable(new TimeoutException("HTTP 429 connection reset")));
        assertFalse(classifier.isRetryable(new ResponseValidationException(
                ResponseValidationFailureType.JSON_PARSE_ERROR, "HTTP 500 connection reset")));
        assertFalse(classifier.isRetryable(new TestDecodingException(
                "{\"error\":{\"code\":\"rate_limit_exceeded\"}}", new EOFException())));
        assertFalse(classifier.isRetryable(new ToolExecutionException(
                ToolDefinition.builder().name("tool").description("test")
                        .inputSchema("{}").build(), new EOFException("connection reset"))));
    }

    @Test
    void should_hard_exclude_all_llm_timeout_subtypes_through_cause_chain() {
        StreamQueryRetryClassifier classifier = classifier(List.of(), List.of());

        assertFalse(classifier.isRetryable(firstChunkTimeout()));
        assertFalse(classifier.isRetryable(new RuntimeException("wrapped", chunkIdleTimeout())));
        assertFalse(classifier.isRetryable(new RuntimeException("wrapped",
                new RuntimeException(queryAttemptTimeout()))));
        assertTrue(classifier.isRetryable(new ConnectException("connection refused")));
    }

    @Test
    void should_apply_veto_to_raw_normalized_provider_and_http_codes() {
        assertFalse(classifier(List.of(), List.of("rate_limit_exceeded"))
                .isRetryable(provider("rate_limit_exceeded")));
        assertFalse(classifier(List.of(), List.of("429"))
                .isRetryable(provider("rate_limit_exceeded")));
        assertFalse(classifier(List.of(), List.of("429"))
                .isRetryable(http(429)));
        assertFalse(classifier(List.of(), List.of("rate_limit_exceeded"))
                .isRetryable(http(429,
                        "{\"error\":{\"code\":\"rate_limit_exceeded\"}}")));
    }

    @Test
    void should_prefer_structured_http_status_over_provider_code() {
        RuntimeException error = new RuntimeException(
                "{\"error\":{\"code\":\"rate_limit_exceeded\"}}", http(400));

        assertFalse(classifier(List.of("rate_limit_exceeded"), List.of()).isRetryable(error));
    }

    @Test
    void should_use_supported_provider_code_only_without_structured_status() {
        StreamQueryRetryClassifier classifier = classifier(List.of(), List.of());

        assertTrue(classifier.isRetryable(provider("rate_limit_exceeded")));
        assertTrue(classifier.isRetryable(provider("service_unavailable")));
        assertFalse(classifier.isRetryable(provider("invalid_request_error")));
        assertFalse(classifier.isRetryable(provider("custom_retryable")));
    }

    @Test
    void should_not_allow_retryable_config_to_expand_fixed_scope() {
        StreamQueryRetryClassifier classifier = classifier(
                List.of("529", "custom_retryable"), List.of());

        assertFalse(classifier.isRetryable(http(529)));
        assertFalse(classifier.isRetryable(provider("custom_retryable")));
    }

    @Test
    void should_retry_supported_transport_failures_without_status_or_code() {
        StreamQueryRetryClassifier classifier = classifier(List.of(), List.of());

        assertTrue(classifier.isRetryable(new ConnectException("connection refused")));
        assertTrue(classifier.isRetryable(new RuntimeException("connection reset by peer")));
        assertTrue(classifier.isRetryable(new RuntimeException("body failed", new EOFException())));
        assertTrue(classifier.isRetryable(new RuntimeException("response header failed")));
        assertFalse(classifier.isRetryable(new IllegalStateException("business rule rejected")));
    }

    @Test
    void should_retry_body_io_failure_after_successful_http_status() {
        WebClientResponseException error = http(200);
        error.initCause(new EOFException("EOF reached while reading response body"));

        assertTrue(classifier(List.of(), List.of()).isRetryable(error));
    }

    private StreamQueryRetryClassifier classifier(List<String> retryable, List<String> veto) {
        return new StreamQueryRetryClassifier(RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .retryableErrorCodes(retryable)
                .nonRetryableErrorCodes(veto)
                .build());
    }

    private RuntimeException provider(String code) {
        return new RuntimeException("{\"error\":{\"code\":\"" + code + "\"}}");
    }

    private WebClientResponseException http(int status) {
        return http(status, "");
    }

    private WebClientResponseException http(int status, String body) {
        return WebClientResponseException.create(status, "status", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private FirstStreamChunkTimeoutException firstChunkTimeout() {
        return new FirstStreamChunkTimeoutException(Duration.ofSeconds(45),
                Duration.ofSeconds(40), TimeoutDeadlineOwner.FIRST_CHUNK,
                Duration.ofSeconds(40), 0, "call-1", "model-1");
    }

    private StreamChunkIdleTimeoutException chunkIdleTimeout() {
        return new StreamChunkIdleTimeoutException(Duration.ofSeconds(90),
                Duration.ofSeconds(80), TimeoutDeadlineOwner.CHUNK_IDLE,
                Duration.ofSeconds(80), 3, "call-1", "model-1");
    }

    private LlmQueryAttemptTimeoutException queryAttemptTimeout() {
        return new LlmQueryAttemptTimeoutException(Duration.ofSeconds(150),
                Duration.ofSeconds(150), TimeoutDeadlineOwner.QUERY_ATTEMPT,
                Duration.ofSeconds(150), 5, "call-1", "model-1");
    }

    private static final class TestDecodingException extends RuntimeException {
        private TestDecodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
