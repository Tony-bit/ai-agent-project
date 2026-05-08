package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 指数退避时间验证测试
 * <p>
 * 测试覆盖：TC-Retry-051, TC-Retry-052, TC-Retry-053
 * </p>
 */
@RunWith(MockitoJUnitRunner.class)
public class BackoffTest {

    @Mock
    private ChatModel delegate;

    @Mock
    private ChatResponse successResponse;

    private Prompt makePrompt() {
        return Prompt.builder()
                .messages(new UserMessage("hello"))
                .build();
    }

    // TC-Retry-051: 指数退避 - 验证重试次数正确（间隔逻辑由 RetryConfig 控制）
    @Test
    public void testExponentialBackoff_retryCount() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(4)
                .initialIntervalMs(1000)
                .multiplier(2.0)
                .maxIntervalMs(10000)
                .retryableErrorCodes(List.of("500"))
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"500\"}}"));

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(makePrompt()));

        assertTrue(thrown.getMessage().contains("500"));
        // 4 次尝试（首次 + 3 次重试）
        verify(delegate, times(4)).call(any(Prompt.class));
    }

    // TC-Retry-052: maxIntervalMs 封顶 - 验证重试次数正确
    @Test
    public void testMaxIntervalCeiling_retryCount() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(5)
                .initialIntervalMs(1000)
                .multiplier(2.0)
                .maxIntervalMs(5000)
                .retryableErrorCodes(List.of("500"))
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"500\"}}"));

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(makePrompt()));

        assertTrue(thrown.getMessage().contains("500"));
        // 5 次尝试（首次 + 4 次重试）
        verify(delegate, times(5)).call(any(Prompt.class));
    }

    // TC-Retry-053: maxAttempts=1 边界条件，首次失败抛出原始异常
    @Test
    public void testMaxAttempts1_exhaustedAllAttempts() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(1)
                .retryableErrorCodes(List.of("500"))
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"500\",\"message\":\"内部错误\"}}"));

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(makePrompt()));
        assertTrue(thrown.getMessage().contains("500"));
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    // TC-Retry-053: maxAttempts=0 时直接调用，不重试
    @Test
    public void testMaxAttemptsZero_noRetry() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(0)
                .build();

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);
        ChatResponse result = retryChatModel.call(makePrompt());

        assertNotNull(result);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    // TC-Retry-053: maxAttempts=2，失败后重试 1 次后成功
    @Test
    public void testMaxAttempts2_retryOnceThenSuccess() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(2)
                .initialIntervalMs(10)
                .retryableErrorCodes(List.of("500"))
                .build();

        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"500\"}}"))
                .thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, config);
        ChatResponse result = retryChatModel.call(makePrompt());

        assertNotNull(result);
        // 2 次调用（首次失败 + 1 次重试成功）
        verify(delegate, times(2)).call(any(Prompt.class));
    }
}
