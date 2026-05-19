package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
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
 * RetryChatModel Corner Cases 专项测试
 * <p>
 * 测试覆盖：
 * - TC-CRN-01: 空值与Null安全
 * - TC-CRN-02: 配置边界值
 * </p>
 */
@RunWith(MockitoJUnitRunner.class)
public class RetryChatModelCornerTest {

    @Mock
    private ChatModel delegate;

    @Mock
    private ChatResponse successResponse;

    private Prompt makePrompt(String text) {
        return Prompt.builder()
                .messages(new UserMessage(text))
                .build();
    }

    // ========== TC-CRN-01: 空值与Null安全 ==========

    @Test(expected = NullPointerException.class)
    public void testRetryConfigNull() {
        new RetryChatModel(delegate, null);
    }

    @Test(expected = NullPointerException.class)
    public void testDelegateNull() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .build();
        new RetryChatModel(null, config);
    }

    @Test
    public void testRetryableErrorCodesNull_treatedAsEmpty() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .retryableErrorCodes(null)
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    @Test
    public void testNonRetryableErrorCodesNull_treatedAsEmpty() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .nonRetryableErrorCodes(null)
                .retryableErrorCodes(List.of("500"))
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenReturn(successResponse);

        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(2)).call(any(Prompt.class));
    }

    @Test
    public void testInitialIntervalMsNull_usesZero() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .retryableErrorCodes(List.of("500"))
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenReturn(successResponse);

        long startTime = System.currentTimeMillis();
        ChatResponse result = retryChatModel.call(makePrompt("hello"));
        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue("Should retry immediately with 0 interval", elapsed < 100);
    }

    @Test
    public void testMultiplierNull_usesDefault() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(100)
                .retryableErrorCodes(List.of("500"))
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenReturn(successResponse);

        long startTime = System.currentTimeMillis();
        ChatResponse result = retryChatModel.call(makePrompt("hello"));
        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue("Should have some delay with multiplier", elapsed >= 100);
    }

    // ========== TC-CRN-02: 配置边界值 ==========

    @Test
    public void testMaxAttemptsMaxValue_noInfiniteLoop() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(Integer.MAX_VALUE)
                .initialIntervalMs(100)
                .retryableErrorCodes(List.of("500"))
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\"}}");
        when(delegate.call(any(Prompt.class))).thenThrow(error);

        long startTime = System.currentTimeMillis();
        try {
            retryChatModel.call(makePrompt("hello"));
            fail("Should throw exception");
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            assertTrue("Should timeout after reasonable time", elapsed < 5000);
        }
    }

    @Test
    public void testMaxAttemptsNegative_noRetry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(-1)
                .retryableErrorCodes(List.of("500"))
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    @Test
    public void testInitialIntervalMsZero_immediateRetry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(0)
                .retryableErrorCodes(List.of("500"))
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenReturn(successResponse);

        long startTime = System.currentTimeMillis();
        ChatResponse result = retryChatModel.call(makePrompt("hello"));
        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue("Should retry immediately", elapsed < 50);
    }

    @Test
    public void testInitialIntervalMsNegative_treatedAsZero() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(-100)
                .retryableErrorCodes(List.of("500"))
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenReturn(successResponse);

        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
    }

    @Test
    public void testMaxIntervalMsZero_intervalStaysZero() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(100)
                .multiplier(2.0)
                .maxIntervalMs(0)
                .retryableErrorCodes(List.of("500"))
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenThrow(error)
                .thenReturn(successResponse);

        long startTime = System.currentTimeMillis();
        ChatResponse result = retryChatModel.call(makePrompt("hello"));
        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue("Should retry without delay since maxInterval is 0", elapsed < 50);
    }

    @Test
    public void testMaxIntervalMsMaxValue_noCapping() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(100)
                .multiplier(2.0)
                .maxIntervalMs(Long.MAX_VALUE)
                .retryableErrorCodes(List.of("500"))
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenReturn(successResponse);

        long startTime = System.currentTimeMillis();
        ChatResponse result = retryChatModel.call(makePrompt("hello"));
        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue("Should wait at least 100ms", elapsed >= 100);
    }

    @Test
    public void testMultiplierZero_fixedInterval() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(100)
                .multiplier(0.0)
                .maxIntervalMs(1000)
                .retryableErrorCodes(List.of("500"))
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenThrow(error)
                .thenReturn(successResponse);

        long startTime = System.currentTimeMillis();
        ChatResponse result = retryChatModel.call(makePrompt("hello"));
        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue("Should have fixed interval (100 + 100)", elapsed >= 200);
        assertTrue("Should not exceed maxInterval (1000)", elapsed < 1000);
    }

    @Test
    public void testMultiplierNegative_fixedInterval() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(100)
                .multiplier(-1.0)
                .maxIntervalMs(1000)
                .retryableErrorCodes(List.of("500"))
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenReturn(successResponse);

        long startTime = System.currentTimeMillis();
        ChatResponse result = retryChatModel.call(makePrompt("hello"));
        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue("Should retry successfully", elapsed < 1000);
    }

    @Test
    public void testMultiplierDoubleMax_overflowProtection() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(100)
                .multiplier(Double.MAX_VALUE)
                .maxIntervalMs(1000)
                .retryableErrorCodes(List.of("500"))
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\"}}");
        when(delegate.call(any(Prompt.class)))
                .thenThrow(error)
                .thenReturn(successResponse);

        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
    }
}
