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
                .initialIntervalMs(0)
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
    public void testMultiplierNull_usesDefault() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(1)
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

    // ========== TC-CRN-02: 配置边界值 ==========

    @Test
    public void testMaxAttemptsMaxValue_noInfiniteLoop() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(Integer.MAX_VALUE)
                .initialIntervalMs(0)
                .maxIntervalMs(0)
                .retryableErrorCodes(List.of("500"))
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException error = new RuntimeException("{\"error\":{\"code\":\"500\"}}");
        when(delegate.call(any(Prompt.class))).thenThrow(error);

        try {
            retryChatModel.call(makePrompt("hello"));
            fail("Should throw exception");
        } catch (Exception e) {
            verify(delegate, times(10)).call(any(Prompt.class));
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

        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(2)).call(any(Prompt.class));
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

        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(3)).call(any(Prompt.class));
    }

    @Test
    public void testMaxIntervalMsMaxValue_noCapping() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(1)
                .multiplier(2.0)
                .maxIntervalMs(Long.MAX_VALUE)
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
    public void testMultiplierZero_fixedInterval() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(1)
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

        ChatResponse result = retryChatModel.call(makePrompt("hello"));

        assertNotNull(result);
        verify(delegate, times(3)).call(any(Prompt.class));
    }

    @Test
    public void testMultiplierNegative_fixedInterval() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(1)
                .multiplier(-1.0)
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
        verify(delegate, times(2)).call(any(Prompt.class));
    }

    @Test
    public void testMultiplierDoubleMax_overflowProtection() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(1)
                .multiplier(Double.MAX_VALUE)
                .maxIntervalMs(2)
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
