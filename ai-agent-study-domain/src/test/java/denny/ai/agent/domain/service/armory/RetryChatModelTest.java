package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.service.armory.factory.element.ResponseValidationContext;
import denny.ai.agent.domain.service.armory.factory.element.ResponseValidationException;
import denny.ai.agent.domain.service.armory.factory.element.ResponseValidationFailureType;
import denny.ai.agent.domain.service.armory.factory.element.RetryChatModel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RetryChatModel 装饰器单元测试
 * <p>
 * 测试覆盖：
 * 1. 正常路径 - TC-Retry-001, TC-Retry-002
 * 2. 黑名单异常 - TC-Retry-011, TC-Retry-012, TC-Retry-013
 * 3. 白名单异常 - TC-Retry-021, TC-Retry-022, TC-Retry-023
 * 4. 默认规则 - TC-Retry-031, TC-Retry-032, TC-Retry-033, TC-Retry-034, TC-Retry-035, TC-Retry-046, TC-Retry-047
 * 5. 边界条件 - TC-Retry-053
 * </p>
 */
@RunWith(MockitoJUnitRunner.class)
public class RetryChatModelTest {

    @Mock
    private ChatModel delegate;

    @Mock
    private ChatResponse successResponse;

    private Prompt makePrompt(String text) {
        return Prompt.builder()
                .messages(new UserMessage(text))
                .build();
    }

    // ========== 2.1 正常路径 ==========

    /**
     * TC-Retry-001: 首次调用成功，无重试
     */
    @Test
    public void testFirstCallSuccess_noRetry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(1000)
                .multiplier(2.0)
                .retryableErrorCodes(List.of("500"))
                .nonRetryableErrorCodes(List.of("401"))
                .build();

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);
        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    // ========== 2.2 黑名单异常（不重试）==========

    /**
     * TC-Retry-011: 异常命中黑名单 errorCode=401，直接抛出不重试
     */
    @Test
    public void testBlacklist401_noRetry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .nonRetryableErrorCodes(List.of("401"))
                .build();

        RuntimeException ex = new RuntimeException("{\"error\":{\"code\":\"401\",\"message\":\"auth failed\"}}");
        when(delegate.call(any(Prompt.class))).thenThrow(ex);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(makePrompt("hello")));
        assertTrue(thrown.getMessage().contains("401"));
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    /**
     * TC-Retry-012: 黑名单 code=403 不重试
     */
    @Test
    public void testBlacklist403_noRetry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .nonRetryableErrorCodes(List.of("403", "1211", "1301"))
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"403\",\"message\":\"forbidden\"}}"));

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(makePrompt("hello")));
        assertTrue(thrown.getMessage().contains("403"));
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    /**
     * TC-Retry-012: 黑名单 code=1211 不重试
     */
    @Test
    public void testBlacklist1211_noRetry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .nonRetryableErrorCodes(List.of("403", "1211", "1301"))
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"1211\",\"message\":\"model not found\"}}"));

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(makePrompt("hello")));
        assertTrue(thrown.getMessage().contains("1211"));
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    /**
     * TC-Retry-012: 黑名单 code=1301 不重试
     */
    @Test
    public void testBlacklist1301_noRetry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .nonRetryableErrorCodes(List.of("403", "1211", "1301"))
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"1301\",\"message\":\"sensitive content\"}}"));

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(makePrompt("hello")));
        assertTrue(thrown.getMessage().contains("1301"));
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    /**
     * TC-Retry-013: HTTP 401 状态码命中黑名单
     */
    @Test
    public void testHttp401Blacklist_noRetry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .nonRetryableErrorCodes(List.of("401", "403"))
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("HTTP 401 Unauthorized"));

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(makePrompt("hello")));
        assertTrue(thrown.getMessage().contains("401"));
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    // ========== 2.3 白名单异常（强制重试）==========

    /**
     * TC-Retry-021: 异常命中白名单 errorCode=500，触发重试后成功
     */
    @Test
    public void testWhitelist500_retryThenSuccess() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .multiplier(2.0)
                .retryableErrorCodes(List.of("500"))
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"500\"}}"))
                .thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);
        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(2)).call(any(Prompt.class));
    }

    @Test
    public void testResponseValidationException_retryThenSuccessWithOriginalPrompt() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .build();
        Prompt prompt = makePrompt("hello");
        AtomicInteger validationCount = new AtomicInteger();

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);
        ChatResponse result = ResponseValidationContext.withValidator(response -> {
            if (validationCount.incrementAndGet() == 1) {
                throw new ResponseValidationException(ResponseValidationFailureType.JSON_PARSE_ERROR,
                        "invalid json");
            }
        }, () -> retryChatModel.call(prompt));

        assertSame(successResponse, result);
        verify(delegate, times(2)).call(same(prompt));
        assertEquals(2, validationCount.get());
    }

    @Test
    public void should_default_max_attempts_to_three_for_response_validation_errors() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .initialIntervalMs(10)
                .build();
        Prompt prompt = makePrompt("hello");
        AtomicInteger validationCount = new AtomicInteger();

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);
        ChatResponse result = ResponseValidationContext.withValidator(response -> {
            if (validationCount.incrementAndGet() < 3) {
                throw new ResponseValidationException(ResponseValidationFailureType.SCHEMA_VALIDATION_ERROR,
                        "schema mismatch");
            }
        }, () -> retryChatModel.call(prompt));

        assertSame(successResponse, result);
        verify(delegate, times(3)).call(same(prompt));
        assertEquals(3, validationCount.get());
    }

    @Test
    public void should_exhaust_configured_attempts_for_response_validation_errors() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(2)
                .initialIntervalMs(10)
                .build();
        Prompt prompt = makePrompt("hello");

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);
        ResponseValidationException thrown = assertThrows(ResponseValidationException.class, () ->
                ResponseValidationContext.withValidator(response -> {
                    throw new ResponseValidationException(ResponseValidationFailureType.BUSINESS_VALIDATION_ERROR,
                            "business rule failed");
                }, () -> retryChatModel.call(prompt)));

        assertEquals(ResponseValidationFailureType.BUSINESS_VALIDATION_ERROR, thrown.getFailureType());
        verify(delegate, times(2)).call(same(prompt));
    }

    /**
     * TC-Retry-022: 异常命中白名单 maxAttempts 次后成功
     */
    @Test
    public void testWhitelist503_maxAttemptsThenSuccess() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .multiplier(2.0)
                .retryableErrorCodes(List.of("503"))
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"503\"}}"))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"503\"}}"))
                .thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);
        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(3)).call(any(Prompt.class));
    }

    /**
     * TC-Retry-023: 异常命中白名单，达到最大重试次数后抛出
     */
    @Test
    public void testWhitelist500_maxAttemptsThenThrow() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .retryableErrorCodes(List.of("500"))
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"500\"}}"));

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(makePrompt("hello")));
        assertTrue(thrown.getMessage().contains("500"));
        verify(delegate, times(3)).call(any(Prompt.class));
    }

    // ========== 2.4 默认规则（isRetryable 兜底）==========

    /**
     * TC-Retry-031: TransientAiException 触发重试
     */
    @Test
    public void testTransientAiException_retry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .multiplier(2.0)
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new TransientAiException())
                .thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);
        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(2)).call(any(Prompt.class));
    }

    /**
     * TC-Retry-032: SocketTimeoutException 触发重试
     */
    @Test
    public void testSocketTimeoutException_retry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .multiplier(2.0)
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new CustomSocketTimeoutException())
                .thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);
        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(2)).call(any(Prompt.class));
    }

    /**
     * TC-Retry-033: ResourceAccessException 触发重试
     */
    @Test
    public void testResourceAccessException_retry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .multiplier(2.0)
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new CustomResourceAccessException())
                .thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);
        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(2)).call(any(Prompt.class));
    }

    /**
     * TC-Retry-034: 异常消息含 ECONNRESET 关键词触发重试
     */
    @Test
    public void testConnectionResetKeyword_retry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .multiplier(2.0)
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("java.net.SocketException: Connection reset by peer"))
                .thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);
        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(2)).call(any(Prompt.class));
    }

    /**
     * TC-Retry-035: 未知异常（不可重试）直接抛出
     */
    @Test
    public void testUnknownException_rethrow() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new IllegalArgumentException("Invalid param"));

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(makePrompt("hello")));
        assertTrue(thrown.getMessage().contains("Invalid param"));
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    /**
     * TC-Retry-046: 无法提取 errorCode 时走 isRetryable 兜底（无匹配，返回 unknown，非可重试，直接抛出）
     */
    @Test
    public void testNoErrorCode_noRetry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("some random error with no code"));

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(makePrompt("hello")));
        assertTrue(thrown.getMessage().contains("some random error"));
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    /**
     * TC-Retry-047: 异常消息为 null 时不抛 NPE
     */
    @Test
    public void testNullMessage_noNPE() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(2)
                .retryableErrorCodes(List.of("unknown"))
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException((String) null))
                .thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);
        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(2)).call(any(Prompt.class));
    }

    // ========== 2.6 边界条件 ==========

    /**
     * TC-Retry-053: maxAttempts=1 边界条件，首次失败抛出原始异常
     */
    @Test
    public void testMaxAttempts1_throwOriginal() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(1)
                .retryableErrorCodes(List.of("500"))
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"500\"}}"));

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(makePrompt("hello")));
        assertTrue(thrown.getMessage().contains("500"));
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    /**
     * TC-Retry-053: maxAttempts<=0 时直接调用 delegate，不重试
     */
    @Test
    public void testMaxAttemptsZero_noRetry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(0)
                .build();

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);
        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    // ========== 辅助异常类（用于 isRetryable 测试）==========

    private static class TransientAiException extends RuntimeException {
        public TransientAiException() {
            super("AI service temporarily unavailable");
        }
    }

    private static class CustomSocketTimeoutException extends RuntimeException {
        public CustomSocketTimeoutException() {
            super("Read timed out");
        }
    }

    private static class CustomResourceAccessException extends org.springframework.web.client.ResourceAccessException {
        public CustomResourceAccessException() {
            super("Connection failed", null);
        }
    }
}
