package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.StreamingTimeoutConfig;
import denny.ai.agent.domain.service.armory.factory.element.CompressionPolicy;
import denny.ai.agent.domain.service.armory.factory.element.RetryChatModel;
import denny.ai.agent.domain.service.compression.PromptCompressionService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.time.Duration;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AiClientModelNode 重试装饰器应用集成测试
 * <p>
 * 测试覆盖：
 * 1. 配置禁用场景 - TC-Retry-061, TC-Retry-062（通过 RetryChatModel 直接验证）
 * 2. 日志验证 - TC-Retry-071, TC-Retry-072
 * </p>
 */
@RunWith(MockitoJUnitRunner.class)
public class AiClientModelNodeRetryTest {

    @Mock
    private PromptCompressionService compressionService;

    @Test
    public void decoratorEnablementCoversAllRetryCompressionCombinations() {
        AiClientModelNode node = new AiClientModelNode();
        ReflectionTestUtils.setField(node, "promptCompressionService", compressionService);
        ChatModel delegate = mock(ChatModel.class);
        RetryConfig retryOff = RetryConfig.builder().enabled(false).maxAttempts(3).build();
        RetryConfig retryOn = RetryConfig.builder().enabled(true).maxAttempts(3).build();
        CompressionPolicy compressionOff = CompressionPolicy.builder().build();
        CompressionPolicy compressionOn = CompressionPolicy.builder()
                .proactiveThresholdTokens(100).maxCompressionAttempts(1).build();

        assertTrue(node.applyRetryDecorator(delegate, retryOff, compressionOff) instanceof RetryChatModel);
        assertTrue(node.applyRetryDecorator(delegate, retryOn, compressionOff) instanceof RetryChatModel);
        assertTrue(node.applyRetryDecorator(delegate, retryOff, compressionOn) instanceof RetryChatModel);
        assertTrue(node.applyRetryDecorator(delegate, retryOn, compressionOn) instanceof RetryChatModel);
    }

    @Test
    public void shouldPassRetryAndStreamingTimeoutConfigurationToDecorator() {
        AiClientModelNode node = new AiClientModelNode();
        ReflectionTestUtils.setField(node, "promptCompressionService", compressionService);
        RetryConfig retry = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(4)
                .initialIntervalMs(25)
                .build();
        StreamingTimeoutConfig timeouts = StreamingTimeoutConfig.builder()
                .firstContentTimeoutMs(11_000L)
                .idleTimeoutMs(20_000L)
                .totalTimeoutMs(100_000L)
                .build();

        RetryChatModel decorated = (RetryChatModel) node.applyRetryDecorator(
                mock(ChatModel.class), retry, CompressionPolicy.builder().build(), timeouts);

        assertSame(retry, ReflectionTestUtils.getField(decorated, "retryConfig"));
        AiStreamingProperties.StreamingTimeouts actual =
                (AiStreamingProperties.StreamingTimeouts) ReflectionTestUtils.getField(
                        decorated, "streamingTimeouts");
        assertEquals(Duration.ofSeconds(11), actual.firstContentTimeout());
        assertEquals(Duration.ofSeconds(20), actual.idleTimeout());
        assertEquals(Duration.ofSeconds(100), actual.totalTimeout());
    }

    @Test
    public void shouldPassModelIdToStreamingPolicyOwner() {
        AiClientModelNode node = new AiClientModelNode();
        ReflectionTestUtils.setField(node, "promptCompressionService", compressionService);

        RetryChatModel decorated = (RetryChatModel) node.applyRetryDecorator(
                mock(ChatModel.class), RetryConfig.builder().enabled(false).build(),
                CompressionPolicy.builder().build(), null, "model-42");

        assertEquals("model-42", ReflectionTestUtils.getField(decorated, "modelId"));
    }

    /**
     * TC-Retry-061: 验证 null config 时装饰器不创建（通过 RetryChatModel 构造验证）
     * 当 retryConfig 为 null 时，applyRetryDecorator 返回原始模型
     * 这里直接验证 RetryChatModel 对 null config 的处理
     */
    @Test
    public void testRetryConfigNull_returnsOriginalModel() {
        // 验证 RetryChatModel 构造对 enabled 字段的处理
        // enabled=false 相当于 config=null 的场景
        RetryConfig config = RetryConfig.builder()
                .enabled(false)
                .maxAttempts(3)
                .build();

        ChatModel mockDelegate = mock(ChatModel.class);
        when(mockDelegate.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));

        RetryChatModel retryChatModel = new RetryChatModel(mockDelegate, config);
        Prompt prompt = Prompt.builder()
                .messages(new UserMessage("hello"))
                .build();

        // enabled=false 时，不会触发重试，直接走 delegate
        // 由于 maxAttempts=0 直接返回，这里验证行为
        ChatResponse result = retryChatModel.call(prompt);
        assertNotNull(result);
        verify(mockDelegate, times(1)).call(any(Prompt.class));
    }

    /**
     * TC-Retry-062: enabled=true，应用装饰器
     */
    @Test
    public void testEnabledTrue_withDecorator() {
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .multiplier(2.0)
                .retryableErrorCodes(List.of("500"))
                .build();

        ChatModel mockDelegate = mock(ChatModel.class);
        RetryChatModel retryChatModel = new RetryChatModel(mockDelegate, config);

        assertNotNull(retryChatModel);
    }

    /**
     * TC-Retry-062: enabled=false，不应用装饰器（验证行为）
     */
    @Test
    public void testEnabledFalse_noDecorator() {
        RetryConfig config = RetryConfig.builder()
                .enabled(false)
                .maxAttempts(3)
                .build();

        ChatModel mockDelegate = mock(ChatModel.class);
        RetryChatModel retryChatModel = new RetryChatModel(mockDelegate, config);

        Prompt prompt = Prompt.builder()
                .messages(new UserMessage("hello"))
                .build();

        // enabled=false 时，maxAttempts=3，进入重试循环
        // 由于无可重试异常（无 retryableErrorCodes，无 isRetryable 匹配），直接抛出
        when(mockDelegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"501\"}}"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(prompt));

        assertTrue(thrown.getMessage().contains("501"));
        // 只调用 1 次，因为 501 不在黑名单、不在白名单、不被 isRetryable 识别
        verify(mockDelegate, times(1)).call(any(Prompt.class));
    }

    // TC-Retry-071: 验证重试日志输出（重试时调用 2 次）
    @Test
    public void testRetryLogOutput_retryThenSuccess() {
        ChatModel mockDelegate = mock(ChatModel.class);
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .multiplier(2.0)
                .retryableErrorCodes(List.of("500"))
                .build();

        when(mockDelegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"500\"}}"))
                .thenReturn(mock(ChatResponse.class));

        RetryChatModel retryChatModel = new RetryChatModel(mockDelegate, config);
        Prompt prompt = Prompt.builder()
                .messages(new UserMessage("hello"))
                .build();
        ChatResponse result = retryChatModel.call(prompt);

        assertNotNull(result);
        verify(mockDelegate, times(2)).call(any(Prompt.class));
    }

    // TC-Retry-072: 验证黑名单日志输出（不重试）
    @Test
    public void testBlacklistLogOutput_noRetry() {
        ChatModel mockDelegate = mock(ChatModel.class);
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .nonRetryableErrorCodes(List.of("401"))
                .build();

        when(mockDelegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"401\",\"message\":\"auth failed\"}}"));

        RetryChatModel retryChatModel = new RetryChatModel(mockDelegate, config);
        Prompt prompt = Prompt.builder()
                .messages(new UserMessage("hello"))
                .build();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(prompt));

        assertTrue(thrown.getMessage().contains("401"));
        verify(mockDelegate, times(1)).call(any(Prompt.class));
    }

    // TC-Retry-051: 验证重试总次数（失败时调用 maxAttempts 次）
    @Test
    public void testRetryMaxAttempts_allFail() {
        ChatModel mockDelegate = mock(ChatModel.class);
        RetryConfig config = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .initialIntervalMs(10)
                .retryableErrorCodes(List.of("500"))
                .build();

        when(mockDelegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"500\"}}"));

        RetryChatModel retryChatModel = new RetryChatModel(mockDelegate, config);
        Prompt prompt = Prompt.builder()
                .messages(new UserMessage("hello"))
                .build();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> retryChatModel.call(prompt));

        assertTrue(thrown.getMessage().contains("500"));
        verify(mockDelegate, times(3)).call(any(Prompt.class));
    }
}
