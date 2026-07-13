package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.service.compression.PromptCompressionService;
import denny.ai.agent.domain.service.runtime.RetryRuntimeContextHolder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

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

    private RetryChatModel model() {
        return model(compressionPolicy);
    }

    private RetryChatModel model(CompressionPolicy policy) {
        return new RetryChatModel(delegate, retryConfig, policy, compressionService, null);
    }

    private RetryRuntimeContext context() {
        return RetryRuntimeContext.builder().sessionId("session").traceId("trace").build();
    }

    private Prompt prompt(String text) {
        return new Prompt(new UserMessage(text));
    }
}
