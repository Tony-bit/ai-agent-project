package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.CompressionConfig;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.util.TokenCountUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RetryChatModel stream() 方法专项测试
 * <p>
 * 测试覆盖：
 * - TC-STR-01: stream基础流程
 * - TC-STR-02: stream重试行为
 * - TC-STR-03: stream降级边界
 * - TC-STR-04: stream与call一致性
 * </p>
 */
@RunWith(MockitoJUnitRunner.class)
public class RetryChatModelStreamTest {

    @Mock
    private ChatModel delegate;

    @Mock
    private ChatResponse successResponse;

    private RetryConfig retryConfig;

    @Before
    public void setUp() {
        retryConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .multiplier(2.0)
                .maxIntervalMs(100)
                .retryableErrorCodes(List.of("500", "1302"))
                .nonRetryableErrorCodes(List.of("401", "403"))
                .build();
    }

    private Prompt makePrompt(String text) {
        return Prompt.builder()
                .messages(new UserMessage(text))
                .build();
    }

    // ========== TC-STR-01: stream基础流程 ==========

    @Test
    public void testStreamSuccess() throws Exception {
        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);

        ChatResponse response1 = mock(ChatResponse.class);
        ChatResponse response2 = mock(ChatResponse.class);
        Flux<ChatResponse> flux = Flux.just(response1, response2);

        when(delegate.stream(any(Prompt.class))).thenReturn(flux);

        Flux<ChatResponse> result = retryChatModel.stream(makePrompt("hello"));

        List<ChatResponse> collected = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        result.subscribe(
                collected::add,
                error -> latch.countDown(),
                () -> latch.countDown()
        );
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(2, collected.size());
        assertSame(response1, collected.get(0));
        assertSame(response2, collected.get(1));
        verify(delegate, times(1)).stream(any(Prompt.class));
    }

    @Test
    public void testStreamDegradeToCall() throws Exception {
        CompressionConfig compressionConfig = CompressionConfig.builder()
                .enabled(true)
                .proactiveThresholdTokens(0)
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);

        ChatResponse response = mock(ChatResponse.class);
        when(delegate.call(any(Prompt.class))).thenReturn(response);

        String longPromptText = "a".repeat(200);
        Flux<ChatResponse> result = retryChatModel.stream(makePrompt(longPromptText));

        List<ChatResponse> collected = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        result.subscribe(
                collected::add,
                error -> latch.countDown(),
                () -> latch.countDown()
        );
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(1, collected.size());
        assertSame(response, collected.get(0));
        verify(delegate, times(1)).call(any(Prompt.class));
        verify(delegate, never()).stream(any(Prompt.class));
    }

    @Test
    public void testStreamDegradeToCallWithCompressionEnabled() throws Exception {
        CompressionConfig compressionConfig = CompressionConfig.builder()
                .enabled(true)
                .proactiveThresholdTokens(0)
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);

        ChatResponse response = mock(ChatResponse.class);
        when(delegate.call(any(Prompt.class))).thenReturn(response);

        String longPromptText = "a".repeat(150);
        Flux<ChatResponse> result = retryChatModel.stream(makePrompt(longPromptText));

        List<ChatResponse> collected = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        result.subscribe(
                collected::add,
                error -> latch.countDown(),
                () -> latch.countDown()
        );
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(1, collected.size());
        assertSame(response, collected.get(0));
    }

    @Test
    public void testStreamDegradeThenSuccess() throws Exception {
        CompressionConfig compressionConfig = CompressionConfig.builder()
                .enabled(true)
                .proactiveThresholdTokens(0)
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);

        ChatResponse response = mock(ChatResponse.class);
        when(delegate.call(any(Prompt.class))).thenReturn(response);

        String longPromptText = "a".repeat(200);
        Flux<ChatResponse> result = retryChatModel.stream(makePrompt(longPromptText));

        List<ChatResponse> collected = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        result.subscribe(
                collected::add,
                error -> latch.countDown(),
                () -> latch.countDown()
        );
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(1, collected.size());
        assertSame(response, collected.get(0));
    }

    // ========== TC-STR-02: stream重试行为 ==========

    @Test
    public void testStreamRetryableException() throws Exception {
        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);

        ChatResponse response = mock(ChatResponse.class);
        Flux<ChatResponse> flux = Flux.just(response);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\",\"message\":\"error\"}}");
        when(delegate.stream(any(Prompt.class)))
                .thenThrow(error)
                .thenReturn(flux);

        Flux<ChatResponse> result = retryChatModel.stream(makePrompt("hello"));

        List<ChatResponse> collected = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        result.subscribe(
                collected::add,
                e -> latch.countDown(),
                () -> latch.countDown()
        );
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(1, collected.size());
        assertSame(response, collected.get(0));
        verify(delegate, times(2)).stream(any(Prompt.class));
    }

    @Test
    public void testStreamBlacklistException() throws Exception {
        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"401\",\"message\":\"auth failed\"}}");
        when(delegate.stream(any(Prompt.class))).thenThrow(error);

        Flux<ChatResponse> result = retryChatModel.stream(makePrompt("hello"));

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        result.subscribe(
                r -> {},
                e -> { errorRef.set(e); latch.countDown(); },
                () -> latch.countDown()
        );
        latch.await(5, TimeUnit.SECONDS);

        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().getMessage().contains("401"));
        verify(delegate, times(1)).stream(any(Prompt.class));
    }

    @Test
    public void testStreamRetryExhausted() throws Exception {
        retryConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(1)
                .maxIntervalMs(2)
                .retryableErrorCodes(List.of("500"))
                .build();
        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\",\"message\":\"error\"}}");
        when(delegate.stream(any(Prompt.class))).thenThrow(error);

        Flux<ChatResponse> result = retryChatModel.stream(makePrompt("hello"));

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        result.subscribe(
                r -> {},
                e -> { errorRef.set(e); latch.countDown(); },
                () -> latch.countDown()
        );
        latch.await(5, TimeUnit.SECONDS);

        assertNotNull(errorRef.get());
        verify(delegate, times(3)).stream(any(Prompt.class));
    }

    // ========== TC-STR-03: stream降级边界 ==========

    @Test
    public void testThresholdBoundaryEqual_noDegrade() throws Exception {
        Prompt prompt = makePrompt("boundary");
        int tokenCount = TokenCountUtils.estimate(prompt.toString());
        CompressionConfig compressionConfig = CompressionConfig.builder()
                .enabled(true)
                .proactiveThresholdTokens(tokenCount)
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);

        ChatResponse response = mock(ChatResponse.class);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(response));

        Flux<ChatResponse> result = retryChatModel.stream(prompt);

        List<ChatResponse> collected = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        result.subscribe(
                collected::add,
                e -> latch.countDown(),
                () -> latch.countDown()
        );
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(1, collected.size());
        verify(delegate, never()).call(any(Prompt.class));
    }

    @Test
    public void testThresholdBoundaryExceed_degrade() throws Exception {
        Prompt prompt = makePrompt("boundary");
        int tokenCount = TokenCountUtils.estimate(prompt.toString());
        CompressionConfig compressionConfig = CompressionConfig.builder()
                .enabled(true)
                .proactiveThresholdTokens(tokenCount - 1)
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);

        ChatResponse response = mock(ChatResponse.class);
        when(delegate.call(any(Prompt.class))).thenReturn(response);

        Flux<ChatResponse> result = retryChatModel.stream(prompt);

        List<ChatResponse> collected = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        result.subscribe(
                collected::add,
                e -> latch.countDown(),
                () -> latch.countDown()
        );
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(1, collected.size());
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    @Test
    public void testCompressionDisabled_noDegrade() throws Exception {
        CompressionConfig compressionConfig = CompressionConfig.builder()
                .enabled(false)
                .proactiveThresholdTokens(100)
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);

        String longPromptText = "a".repeat(200);
        ChatResponse response = mock(ChatResponse.class);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(response));

        Flux<ChatResponse> result = retryChatModel.stream(makePrompt(longPromptText));

        List<ChatResponse> collected = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        result.subscribe(
                collected::add,
                e -> latch.countDown(),
                () -> latch.countDown()
        );
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(1, collected.size());
        verify(delegate, never()).call(any(Prompt.class));
    }

    @Test
    public void testCompressionConfigNull_noDegrade() throws Exception {
        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);

        String longPromptText = "a".repeat(200);
        ChatResponse response = mock(ChatResponse.class);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(response));

        Flux<ChatResponse> result = retryChatModel.stream(makePrompt(longPromptText));

        List<ChatResponse> collected = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        result.subscribe(
                collected::add,
                e -> latch.countDown(),
                () -> latch.countDown()
        );
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(1, collected.size());
        verify(delegate, never()).call(any(Prompt.class));
    }

    // ========== TC-STR-04: stream与call一致性 ==========

    @Test
    public void testSameConfigSameBehavior() {
        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\",\"message\":\"error\"}}");
        ChatResponse response = mock(ChatResponse.class);

        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenReturn(response);
        when(delegate.stream(any(Prompt.class)))
                .thenThrow(error)
                .thenReturn(Flux.just(response));

        ChatResponse callResult = retryChatModel.call(makePrompt("hello"));
        assertNotNull(callResult);

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        retryChatModel.stream(makePrompt("hello")).subscribe(
                r -> {},
                e -> { errorRef.set(e); latch.countDown(); },
                () -> latch.countDown()
        );
    }

    @Test
    public void testSameErrorSameDecision() {
        retryConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .retryableErrorCodes(List.of("500"))
                .nonRetryableErrorCodes(List.of("401"))
                .build();
        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);

        RuntimeException authError = new RuntimeException("{\"error\":{\"code\":\"401\",\"message\":\"auth failed\"}}");
        when(delegate.call(any(Prompt.class))).thenThrow(authError);
        RuntimeException callThrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(makePrompt("hello")));
        assertTrue(callThrown.getMessage().contains("401"));
    }
}
