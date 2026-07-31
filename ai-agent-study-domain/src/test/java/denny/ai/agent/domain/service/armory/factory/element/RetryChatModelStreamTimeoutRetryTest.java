package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.service.armory.stream.FirstStreamChunkTimeoutException;
import denny.ai.agent.domain.service.armory.stream.LlmQueryAttemptTimeoutException;
import denny.ai.agent.domain.service.armory.stream.StreamChunkIdleTimeoutException;
import denny.ai.agent.domain.service.armory.stream.TimeoutDeadlineOwner;
import denny.ai.agent.domain.service.compression.PromptCompressionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetryChatModelStreamTimeoutRetryTest {

    @Test
    void should_retry_first_chunk_timeout_when_both_switches_are_enabled() {
        ChatModel delegate = mock(ChatModel.class);
        Prompt prompt = prompt("question");
        ChatResponse success = response("success");
        when(delegate.stream(prompt))
                .thenReturn(Flux.error(firstChunkTimeout()))
                .thenReturn(Flux.just(success));

        StepVerifier.create(model(delegate, config(true, true, 2)).stream(prompt))
                .expectNext(success)
                .verifyComplete();

        verify(delegate, times(2)).stream(prompt);
    }

    @Test
    void should_retry_chunk_idle_timeout_and_discard_failed_attempt() {
        ChatModel delegate = mock(ChatModel.class);
        Prompt prompt = prompt("question");
        ChatResponse discarded = response("discarded");
        ChatResponse success = response("success");
        when(delegate.stream(prompt))
                .thenReturn(Flux.concat(Flux.just(discarded), Flux.error(chunkIdleTimeout())))
                .thenReturn(Flux.just(success));

        StepVerifier.create(model(delegate, config(true, true, 2)).stream(prompt))
                .expectNext(success)
                .verifyComplete();
    }

    @Test
    void should_not_retry_stream_timeout_when_child_switch_is_disabled() {
        assertNoRetry(config(true, false, 2), firstChunkTimeout());
    }

    @Test
    void should_not_retry_stream_timeout_when_global_switch_is_disabled() {
        assertNoRetry(config(false, true, 2), chunkIdleTimeout());
    }

    @Test
    void should_share_ordinary_credit_between_503_and_stream_timeout() {
        ChatModel delegate = mock(ChatModel.class);
        Prompt prompt = prompt("question");
        ChatResponse success = response("success");
        when(delegate.stream(prompt))
                .thenReturn(Flux.error(http(503, "")))
                .thenReturn(Flux.error(chunkIdleTimeout()))
                .thenReturn(Flux.just(success));

        StepVerifier.create(model(delegate, config(true, true, 3)).stream(prompt))
                .expectNext(success)
                .verifyComplete();

        verify(delegate, times(3)).stream(prompt);
    }

    @Test
    void should_propagate_last_original_stream_timeout_when_exhausted() {
        ChatModel delegate = mock(ChatModel.class);
        Prompt prompt = prompt("question");
        FirstStreamChunkTimeoutException first = firstChunkTimeout();
        StreamChunkIdleTimeoutException last = chunkIdleTimeout();
        when(delegate.stream(prompt))
                .thenReturn(Flux.error(first))
                .thenReturn(Flux.error(last));

        StepVerifier.create(model(delegate, config(true, true, 2)).stream(prompt))
                .expectErrorMatches(error -> error == last)
                .verify();
    }

    @Test
    void should_choose_exactly_one_recovery_action_by_priority() {
        assertNoRetry(config(true, true, 2), mixed(firstChunkTimeout(), queryAttemptTimeout()));
        assertNoRetry(config(true, true, 2, List.of("1261")),
                new RuntimeException(providerError("1261"), chunkIdleTimeout()));
        assertNoRetry(config(true, true, 2),
                mixed(http(400, providerError("invalid_request")), firstChunkTimeout()));

        ChatModel delegate = mock(ChatModel.class);
        Prompt original = prompt("a".repeat(500));
        Prompt compressed = prompt("x");
        PromptCompressionService compression = mock(PromptCompressionService.class);
        when(compression.compress(any(), any(), any())).thenReturn(compressed);
        when(delegate.stream(original)).thenReturn(Flux.error(
                new RuntimeException(providerError("1261"), chunkIdleTimeout())));
        when(delegate.stream(compressed)).thenReturn(Flux.just(response("success")));

        StepVerifier.create(model(delegate, config(true, true, 2), compression, 1)
                        .stream(original))
                .expectNextCount(1)
                .verifyComplete();

        verify(compression, times(1)).compress(any(), any(), any());
        verify(delegate, times(2)).stream(any(Prompt.class));
    }

    @Test
    void should_count_model_calls_and_budgets_after_compression_then_timeout() {
        ChatModel delegate = mock(ChatModel.class);
        Prompt original = prompt("a".repeat(500));
        Prompt compressed = prompt("x");
        PromptCompressionService compression = mock(PromptCompressionService.class);
        ChatResponse success = response("success");
        when(compression.compress(any(), any(), any())).thenReturn(compressed);
        when(delegate.stream(original)).thenReturn(Flux.error(
                new RuntimeException(providerError("1261"), chunkIdleTimeout())));
        when(delegate.stream(compressed))
                .thenReturn(Flux.error(firstChunkTimeout()))
                .thenReturn(Flux.just(success));

        StepVerifier.create(model(delegate, config(true, true, 2), compression, 1)
                        .stream(original))
                .expectNext(success)
                .verifyComplete();

        verify(compression, times(1)).compress(any(), any(), any());
        verify(delegate, times(3)).stream(any(Prompt.class));
    }

    private void assertNoRetry(RetryConfig config, Throwable error) {
        ChatModel delegate = mock(ChatModel.class);
        Prompt prompt = prompt("question");
        when(delegate.stream(prompt)).thenReturn(Flux.error(error));

        StepVerifier.create(model(delegate, config).stream(prompt))
                .expectErrorMatches(actual -> actual == error)
                .verify();

        verify(delegate, times(1)).stream(prompt);
    }

    private RetryChatModel model(ChatModel delegate, RetryConfig config) {
        return new RetryChatModel(delegate, config);
    }

    private RetryChatModel model(ChatModel delegate, RetryConfig config,
                                 PromptCompressionService compression, int maxCompressionAttempts) {
        CompressionPolicy policy = CompressionPolicy.builder()
                .proactiveThresholdTokens(Integer.MAX_VALUE)
                .maxCompressionAttempts(maxCompressionAttempts)
                .build();
        return new RetryChatModel(delegate, config, policy, compression, null);
    }

    private RetryConfig config(boolean enabled, boolean timeoutEnabled, int maxAttempts) {
        return config(enabled, timeoutEnabled, maxAttempts, List.of());
    }

    private RetryConfig config(boolean enabled, boolean timeoutEnabled, int maxAttempts,
                               List<String> nonRetryableCodes) {
        return RetryConfig.builder()
                .enabled(enabled)
                .retryOnStreamTimeout(timeoutEnabled)
                .maxAttempts(maxAttempts)
                .initialIntervalMs(0)
                .maxIntervalMs(0)
                .nonRetryableErrorCodes(nonRetryableCodes)
                .build();
    }

    private RuntimeException mixed(RuntimeException outer, Throwable cause) {
        outer.initCause(cause);
        return outer;
    }

    private FirstStreamChunkTimeoutException firstChunkTimeout() {
        return new FirstStreamChunkTimeoutException(Duration.ofSeconds(45),
                Duration.ofSeconds(45), TimeoutDeadlineOwner.FIRST_CHUNK,
                Duration.ofSeconds(45), 0, "call-1", "model-1");
    }

    private StreamChunkIdleTimeoutException chunkIdleTimeout() {
        return new StreamChunkIdleTimeoutException(Duration.ofSeconds(90),
                Duration.ofSeconds(90), TimeoutDeadlineOwner.CHUNK_IDLE,
                Duration.ofSeconds(90), 2, "call-1", "model-1");
    }

    private LlmQueryAttemptTimeoutException queryAttemptTimeout() {
        return new LlmQueryAttemptTimeoutException(Duration.ofSeconds(150),
                Duration.ofSeconds(150), TimeoutDeadlineOwner.QUERY_ATTEMPT,
                Duration.ofSeconds(150), 2, "call-1", "model-1");
    }

    private WebClientResponseException http(int status, String body) {
        return WebClientResponseException.create(status, "status", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private String providerError(String code) {
        return "{\"error\":{\"code\":\"" + code + "\"}}";
    }

    private Prompt prompt(String text) {
        return new Prompt(new UserMessage(text));
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
