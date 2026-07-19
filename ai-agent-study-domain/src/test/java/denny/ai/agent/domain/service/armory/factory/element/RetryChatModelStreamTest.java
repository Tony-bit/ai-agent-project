package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.service.armory.AiStreamingProperties;
import denny.ai.agent.domain.service.compression.PromptCompressionService;
import denny.ai.agent.domain.service.runtime.RetryRuntimeContextHolder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class RetryChatModelStreamTest {

    @Mock
    private ChatModel delegate;

    @Mock
    private PromptCompressionService compressionService;

    @Mock
    private ChatResponse successResponse;

    private RetryConfig retryConfig;
    private CompressionPolicy compressionPolicy;

    @Before
    public void setUp() {
        retryConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(2)
                .initialIntervalMs(0)
                .maxIntervalMs(0)
                .retryableErrorCodes(java.util.List.of("429"))
                .build();
        compressionPolicy = CompressionPolicy.builder()
                .proactiveThresholdTokens(Integer.MAX_VALUE)
                .maxCompressionAttempts(2)
                .build();
    }

    @Test
    public void subscriptionError429RetriesDelegateStream() {
        Prompt prompt = prompt("question");
        when(delegate.stream(prompt))
                .thenReturn(Flux.error(new RuntimeException("{\"error\":{\"code\":\"429\"}}")))
                .thenReturn(Flux.just(successResponse));

        StepVerifier.create(model().stream(prompt))
                .expectNext(successResponse)
                .verifyComplete();

        verify(delegate, times(2)).stream(prompt);
        verify(compressionService, never()).compress(any(), any(), any());
    }

    @Test
    public void overflowBeforeFirstChunkCompressesAndRetriesStream() {
        Prompt original = prompt("a".repeat(500));
        Prompt compressed = prompt("x");
        RetryRuntimeContext context = context();
        when(delegate.stream(original)).thenReturn(Flux.error(
                new RuntimeException("{\"error\":{\"code\":\"1261\"}}")));
        when(delegate.stream(compressed)).thenReturn(Flux.just(successResponse));
        when(compressionService.compress(original, context, compressionPolicy)).thenReturn(compressed);

        Flux<ChatResponse> flux = RetryRuntimeContextHolder.withContext(context,
                () -> model().stream(original));
        assertNull(RetryRuntimeContextHolder.current());

        StepVerifier.create(flux).expectNext(successResponse).verifyComplete();
        verify(delegate).stream(original);
        verify(delegate).stream(compressed);
    }

    @Test
    public void delayedSubscriptionUsesContextCapturedAtStreamEntry() {
        Prompt original = prompt("a".repeat(500));
        Prompt compressed = prompt("x");
        RetryRuntimeContext context = context();
        when(delegate.stream(original)).thenReturn(Flux.error(
                new RuntimeException("{\"error\":{\"code\":\"1261\"}}")));
        when(delegate.stream(compressed)).thenReturn(Flux.just(successResponse));
        when(compressionService.compress(original, context, compressionPolicy)).thenReturn(compressed);

        Flux<ChatResponse> flux = RetryRuntimeContextHolder.withContext(context,
                () -> model().stream(original));

        StepVerifier.create(flux).expectNext(successResponse).verifyComplete();
        verify(compressionService).compress(original, context, compressionPolicy);
    }

    @Test
    public void errorAfterFirstChunkIsNotRetried() {
        Prompt prompt = prompt("question");
        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"429\"}}");
        when(delegate.stream(prompt)).thenReturn(Flux.concat(Flux.just(successResponse), Flux.error(error)));

        StepVerifier.create(model().stream(prompt))
                .expectNext(successResponse)
                .expectErrorMatches(actual -> actual == error)
                .verify();

        verify(delegate, times(1)).stream(prompt);
    }

    @Test
    public void proactiveCompressionKeepsStreamingContract() {
        Prompt original = prompt("a".repeat(500));
        Prompt compressed = prompt("x");
        CompressionPolicy proactive = CompressionPolicy.builder()
                .proactiveThresholdTokens(1).maxCompressionAttempts(1).build();
        when(compressionService.compress(eq(original), any(), eq(proactive))).thenReturn(compressed);
        when(delegate.stream(compressed)).thenReturn(Flux.just(successResponse));

        StepVerifier.create(model(proactive).stream(original))
                .expectNext(successResponse)
                .verifyComplete();

        verify(delegate).stream(compressed);
        verify(delegate, never()).call(any(Prompt.class));
    }

    @Test
    public void should_retry_when_first_content_times_out_before_any_response() {
        Prompt prompt = prompt("question");
        when(delegate.stream(prompt))
                .thenReturn(Flux.never())
                .thenReturn(Flux.just(response("done")));

        StepVerifier.withVirtualTime(() -> model(timeouts(5, 3, 20)).stream(prompt))
                .thenAwait(Duration.ofSeconds(5))
                .expectNextMatches(value -> "done".equals(value.getResult().getOutput().getText()))
                .verifyComplete();

        verify(delegate, times(2)).stream(prompt);
    }

    @Test
    public void should_not_retry_when_first_content_times_out_after_response_observed() {
        Prompt prompt = prompt("question");
        when(delegate.stream(prompt)).thenReturn(
                Flux.concat(Flux.just(response("")), Flux.never()));

        StepVerifier.withVirtualTime(() -> model(timeouts(5, 3, 20)).stream(prompt))
                .expectNextCount(1)
                .thenAwait(Duration.ofSeconds(5))
                .expectError(java.util.concurrent.TimeoutException.class)
                .verify();

        verify(delegate, times(1)).stream(prompt);
    }

    @Test
    public void should_not_retry_when_effective_content_becomes_idle() {
        Prompt prompt = prompt("question");
        when(delegate.stream(prompt)).thenReturn(
                Flux.concat(Flux.just(response("first")), Flux.never()));

        StepVerifier.withVirtualTime(() -> model(timeouts(5, 3, 20)).stream(prompt))
                .expectNextCount(1)
                .thenAwait(Duration.ofSeconds(3))
                .expectError(java.util.concurrent.TimeoutException.class)
                .verify();

        verify(delegate, times(1)).stream(prompt);
    }

    @Test
    public void should_preserve_logical_call_budget_across_attempts() {
        Prompt prompt = prompt("question");
        when(delegate.stream(prompt)).thenReturn(Flux.never());

        StepVerifier.withVirtualTime(() -> model(timeouts(4, 3, 6)).stream(prompt))
                .thenAwait(Duration.ofSeconds(6))
                .expectError(java.util.concurrent.TimeoutException.class)
                .verify();

        verify(delegate, times(2)).stream(prompt);
    }

    private RetryChatModel model() {
        return model(compressionPolicy);
    }

    private RetryChatModel model(CompressionPolicy policy) {
        return new RetryChatModel(delegate, retryConfig, policy, compressionService, null);
    }

    private RetryChatModel model(AiStreamingProperties.StreamingTimeouts timeouts) {
        return new RetryChatModel(delegate, retryConfig, compressionPolicy, compressionService, null, timeouts);
    }

    private AiStreamingProperties.StreamingTimeouts timeouts(long firstSeconds,
                                                              long idleSeconds,
                                                              long totalSeconds) {
        return new AiStreamingProperties.StreamingTimeouts(
                Duration.ofSeconds(1), Duration.ofSeconds(firstSeconds),
                Duration.ofSeconds(idleSeconds), Duration.ofSeconds(totalSeconds));
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private RetryRuntimeContext context() {
        return RetryRuntimeContext.builder().sessionId("session").traceId("trace").build();
    }

    private Prompt prompt(String text) {
        return new Prompt(new UserMessage(text));
    }
}
