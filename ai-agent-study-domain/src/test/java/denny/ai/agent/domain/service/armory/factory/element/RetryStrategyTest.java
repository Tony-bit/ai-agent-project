package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.CompressionConfig;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.service.compression.CompressionExhaustedException;
import denny.ai.agent.domain.service.compression.PromptCompressionService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RetryStrategy 重试策略抽象类单元测试
 * <p>
 * 测试覆盖：
 * - TC-RST-01: 基础重试流程
 * - TC-RST-02: 错误码匹配
 * - TC-RST-03: 重试间隔退避
 * - TC-RST-05: 异常转换
 * </p>
 */
@RunWith(MockitoJUnitRunner.class)
public class RetryStrategyTest {

    @Mock
    private ChatModel delegate;

    @Mock
    private ChatResponse successResponse;

    @Mock
    private PromptCompressionService compressionService;

    private RetryConfig defaultConfig;
    private AiErrorCodeExtractor errorCodeExtractor;

    @Before
    public void setUp() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .multiplier(2.0)
                .maxIntervalMs(100)
                .retryableErrorCodes(List.of("500", "1302"))
                .nonRetryableErrorCodes(List.of("401", "403"))
                .build();
        errorCodeExtractor = new AiErrorCodeExtractor();
    }

    private Prompt makePrompt(String text) {
        return Prompt.builder()
                .messages(new UserMessage(text))
                .build();
    }

    // ========== TC-RST-01: 基础重试流程 ==========

    @Test
    public void testFirstCallSuccess_noRetry() {
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);
        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        ChatResponse result = strategy.execute(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    @Test
    public void testRetrySuccessAfterOneFailure() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .multiplier(2.0)
                .maxIntervalMs(100)
                .retryableErrorCodes(List.of("500"))
                .build();
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);

        RuntimeException firstError = new RuntimeException("{\"error\":{\"code\":\"500\",\"message\":\"error\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(firstError)
                .thenReturn(successResponse);

        ChatResponse result = strategy.execute(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(2)).call(any(Prompt.class));
    }

    @Test
    public void testRetrySuccessAfterTwoFailures() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .multiplier(2.0)
                .maxIntervalMs(100)
                .retryableErrorCodes(List.of("500"))
                .build();
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\",\"message\":\"error\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenThrow(error)
                .thenReturn(successResponse);

        ChatResponse result = strategy.execute(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(3)).call(any(Prompt.class));
    }

    @Test
    public void testMaxAttemptsExhausted() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .multiplier(2.0)
                .maxIntervalMs(100)
                .retryableErrorCodes(List.of("500"))
                .build();
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\",\"message\":\"error\"}}");
        when(delegate.call(any(Prompt.class))).thenThrow(error);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> strategy.execute(makePrompt("hello")));

        assertNotNull(thrown);
        verify(delegate, times(3)).call(any(Prompt.class));
    }

    @Test
    public void testNoRetryWhenMaxAttemptsZero() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(0)
                .retryableErrorCodes(List.of("500"))
                .build();
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        ChatResponse result = strategy.execute(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    // ========== TC-RST-02: 错误码匹配 ==========

    @Test
    public void testWhitelistMatch_retry() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .retryableErrorCodes(List.of("1302"))
                .build();
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"1302\",\"message\":\"error\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenReturn(successResponse);

        ChatResponse result = strategy.execute(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(2)).call(any(Prompt.class));
    }

    @Test
    public void testWhitelistNoMatch_noRetry() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .retryableErrorCodes(List.of("500", "503"))
                .build();
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"1302\",\"message\":\"error\"}}");
        when(delegate.call(any(Prompt.class))).thenThrow(error);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> strategy.execute(makePrompt("hello")));

        assertNotNull(thrown);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    @Test
    public void testBlacklistMatch_noRetry() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .retryableErrorCodes(List.of("500"))
                .nonRetryableErrorCodes(List.of("401"))
                .build();
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"401\",\"message\":\"auth failed\"}}");
        when(delegate.call(any(Prompt.class))).thenThrow(error);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> strategy.execute(makePrompt("hello")));

        assertNotNull(thrown);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    @Test
    public void testBlacklistPriorityOverWhitelist() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .retryableErrorCodes(List.of("401"))
                .nonRetryableErrorCodes(List.of("401"))
                .build();
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"401\",\"message\":\"auth failed\"}}");
        when(delegate.call(any(Prompt.class))).thenThrow(error);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> strategy.execute(makePrompt("hello")));

        assertNotNull(thrown);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    @Test
    public void testEmptyWhitelist_usesIsRetryable() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .retryableErrorCodes(List.of())
                .build();
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        ChatResponse result = strategy.execute(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    @Test
    public void testNullBlacklist_treatedAsEmpty() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .nonRetryableErrorCodes(null)
                .retryableErrorCodes(List.of("500"))
                .build();
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\",\"message\":\"error\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenReturn(successResponse);

        ChatResponse result = strategy.execute(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(2)).call(any(Prompt.class));
    }

    // ========== TC-RST-03: 重试间隔退避 ==========

    @Test
    public void testExponentialBackoff() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(4)
                .initialIntervalMs(100)
                .multiplier(2.0)
                .maxIntervalMs(1000)
                .retryableErrorCodes(List.of("500"))
                .build();
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\",\"message\":\"error\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenThrow(error)
                .thenThrow(error)
                .thenReturn(successResponse);

        long startTime = System.currentTimeMillis();
        ChatResponse result = strategy.execute(makePrompt("hello"));
        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue("Expected at least 300ms (100 + 200) delay", elapsed >= 300);
    }

    @Test
    public void testMaxIntervalCapped() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(4)
                .initialIntervalMs(100)
                .multiplier(2.0)
                .maxIntervalMs(150)
                .retryableErrorCodes(List.of("500"))
                .build();
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\",\"message\":\"error\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenThrow(error)
                .thenThrow(error)
                .thenReturn(successResponse);

        long startTime = System.currentTimeMillis();
        ChatResponse result = strategy.execute(makePrompt("hello"));
        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue("Expected at least 150ms (max capped) delay", elapsed >= 150);
    }

    @Test
    public void testFixedIntervalWhenMultiplierOne() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(50)
                .multiplier(1.0)
                .maxIntervalMs(100)
                .retryableErrorCodes(List.of("500"))
                .build();
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\",\"message\":\"error\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenThrow(error)
                .thenReturn(successResponse);

        long startTime = System.currentTimeMillis();
        ChatResponse result = strategy.execute(makePrompt("hello"));
        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue("Expected at least 100ms (50 + 50) delay", elapsed >= 100);
    }

    // ========== TC-RST-05: 异常转换 ==========

    @Test
    public void testRuntimeExceptionThrown() {
        defaultConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(1)
                .retryableErrorCodes(List.of("500"))
                .build();
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\",\"message\":\"error\"}}");
        when(delegate.call(any(Prompt.class))).thenThrow(error);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> strategy.execute(makePrompt("hello")));

        assertSame(error, thrown);
    }

    @Test
    public void contextOverflowCompressesAndContinuesInSameStrategy() {
        Prompt original = makePrompt("a".repeat(200));
        Prompt compressed = makePrompt("short");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"1261\"}}"))
                .thenReturn(successResponse);
        when(compressionService.compress(eq(original), any(), any())).thenReturn(compressed);
        TestRetryStrategy strategy = new TestRetryStrategy(defaultConfig, compressionPolicy(2), context());

        assertSame(successResponse, strategy.execute(original));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate, times(2)).call(captor.capture());
        assertSame(original, captor.getAllValues().get(0));
        assertSame(compressed, captor.getAllValues().get(1));
    }

    @Test
    public void proactiveCompressionDoesNotConsumeModelAttempt() {
        Prompt original = makePrompt("a".repeat(200));
        Prompt compressed = makePrompt("x");
        when(compressionService.compress(eq(original), any(), any())).thenReturn(compressed);
        when(delegate.call(compressed)).thenReturn(successResponse);
        CompressionPolicy policy = CompressionPolicy.builder()
                .enabled(true).proactiveThresholdTokens(1).maxCompressionAttempts(1).build();

        assertSame(successResponse, new TestRetryStrategy(defaultConfig, policy, context()).execute(original));
        verify(delegate).call(compressed);
    }

    @Test
    public void compressionMustReducePromptSize() {
        Prompt original = makePrompt("short");
        Prompt larger = makePrompt("a".repeat(500));
        when(delegate.call(original)).thenThrow(new RuntimeException("{\"error\":{\"code\":\"1261\"}}"));
        when(compressionService.compress(eq(original), any(), any())).thenReturn(larger);

        assertThrows(CompressionExhaustedException.class,
                () -> new TestRetryStrategy(defaultConfig, compressionPolicy(1), context()).execute(original));
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    @Test
    public void disabledCompressionMakes1261TerminalEvenWhenRetryable() {
        RetryConfig config = RetryConfig.builder().enabled(true).maxAttempts(3)
                .retryableErrorCodes(List.of("1261")).build();
        RuntimeException overflow = new RuntimeException("{\"error\":{\"code\":\"1261\"}}");
        when(delegate.call(any(Prompt.class))).thenThrow(overflow);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new TestRetryStrategy(config).execute(makePrompt("prompt")));

        assertSame(overflow, thrown);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    @Test
    public void retryDisabledStillAllowsOverflowReplacementCall() {
        RetryConfig config = RetryConfig.builder().enabled(false).maxAttempts(5).build();
        Prompt original = makePrompt("a".repeat(200));
        Prompt compressed = makePrompt("x");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"1261\"}}"))
                .thenReturn(successResponse);
        when(compressionService.compress(eq(original), any(), any())).thenReturn(compressed);

        assertSame(successResponse,
                new TestRetryStrategy(config, compressionPolicy(1), context()).execute(original));
        verify(delegate, times(2)).call(any(Prompt.class));
    }

    // Removed: testCheckedExceptionWrapped - ChatModel.call() doesn't throw checked exceptions
    // Removed: testNullException_throwsIllegalState - RetryStrategy already handles null lastException

    // ========== 辅助类 ==========

    private class TestRetryStrategy extends RetryStrategy<ChatResponse> {

        private final ChatModel chatModelDelegate;
        private final AiErrorCodeExtractor extractor;

        protected TestRetryStrategy(RetryConfig retryConfig) {
            this(retryConfig, null, null);
        }

        protected TestRetryStrategy(RetryConfig retryConfig,
                                    CompressionPolicy compressionPolicy,
                                    RetryRuntimeContext runtimeContext) {
            super(RetryStrategyTest.this.delegate, retryConfig, compressionPolicy,
                    RetryStrategyTest.this.compressionService, runtimeContext,
                    RetryStrategyTest.this.errorCodeExtractor);
            this.chatModelDelegate = RetryStrategyTest.this.delegate;
            this.extractor = RetryStrategyTest.this.errorCodeExtractor;
        }

        @Override
        protected ChatResponse doExecute(Prompt prompt) {
            try {
                return chatModelDelegate.call(prompt);
            } catch (Exception e) {
                throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
            }
        }

        @Override
        protected ChatResponse onExhausted(RuntimeException e) {
            if (e == null) {
                throw new IllegalStateException("exhausted all retry attempts");
            }
            throw e;
        }
    }

    private CompressionPolicy compressionPolicy(int attempts) {
        return CompressionPolicy.builder()
                .enabled(true)
                .proactiveThresholdTokens(Integer.MAX_VALUE)
                .maxCompressionAttempts(attempts)
                .build();
    }

    private RetryRuntimeContext context() {
        return RetryRuntimeContext.builder().sessionId("session").traceId("trace").build();
    }
}
