package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.EOFException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamQueryRetryClassifierTest {

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

    private static final class TestDecodingException extends RuntimeException {
        private TestDecodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
