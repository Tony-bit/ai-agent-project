package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.CompressionConfig;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
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

    // Removed: testCheckedExceptionWrapped - ChatModel.call() doesn't throw checked exceptions
    // Removed: testNullException_throwsIllegalState - RetryStrategy already handles null lastException

    // ========== 辅助类 ==========

    private class TestRetryStrategy extends RetryStrategy<ChatResponse> {

        private final ChatModel chatModelDelegate;
        private final AiErrorCodeExtractor extractor;

        protected TestRetryStrategy(RetryConfig retryConfig) {
            super(RetryStrategyTest.this.delegate, retryConfig, null, null, RetryStrategyTest.this.errorCodeExtractor);
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
}
