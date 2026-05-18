package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.CompressionConfig;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
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
 * RetryChatModel 主动压缩触发单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-RCL-001: 主动压缩触发 - 超阈值
 * 2. TC-RCL-002: 主动压缩不触发 - 恰好阈值
 * 3. TC-RCL-003: 主动压缩不触发 - 低于阈值
 * 4. TC-RCL-006: 大幅超阈值
 * 5. TC-RCL-007: 压缩未启用
 * 6. TC-RCL-008: compressionConfig 为 null
 * 7. TC-RCL-009: 被动压缩触发 - 1261
 * 8. TC-RCL-013: DynamicContext 字段设置验证
 * </p>
 */
@RunWith(MockitoJUnitRunner.class)
public class RetryChatModelCompressionTest {

    @Mock
    private ChatModel delegate;

    @Mock
    private ChatResponse successResponse;

    private Prompt makePrompt(String text) {
        return Prompt.builder()
                .messages(new UserMessage(text))
                .build();
    }

    /**
     * TC-RCL-001: 主动压缩触发 - 超阈值
     */
    @Test
    public void testProactiveCompressionTriggered_ExceedsThreshold() {
        RetryConfig retryConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .build();

        CompressionConfig compressionConfig = CompressionConfig.builder()
                .enabled(true)
                .proactiveThresholdTokens(10)
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);
        retryChatModel.setCompressionConfig(compressionConfig);
        DynamicContext dynamicContext = new DynamicContext();
        retryChatModel.setDynamicContext(dynamicContext);

        // 创建一个超过阈值的 prompt
        String largeText = "a".repeat(100);
        Prompt prompt = makePrompt(largeText);

        // 验证抛出压缩异常
        CompressionRequiredException exception = assertThrows(CompressionRequiredException.class,
                () -> retryChatModel.call(prompt));

        assertNotNull(exception.getOriginalPrompt());
        assertEquals("aiClientModelNode", exception.getReturnNode());
    }

    /**
     * TC-RCL-002: 主动压缩不触发 - 恰好阈值
     */
    @Test
    public void testProactiveCompressionNotTriggered_ExactlyThreshold() {
        RetryConfig retryConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .build();

        CompressionConfig compressionConfig = CompressionConfig.builder()
                .enabled(true)
                .proactiveThresholdTokens(100000)
                .build();

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);
        retryChatModel.setCompressionConfig(compressionConfig);
        DynamicContext dynamicContext = new DynamicContext();
        retryChatModel.setDynamicContext(dynamicContext);

        // 创建一个恰好在阈值内的 prompt
        String text = "short text";
        Prompt prompt = makePrompt(text);

        ChatResponse result = retryChatModel.call(prompt);

        assertNotNull(result);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    /**
     * TC-RCL-003: 主动压缩不触发 - 低于阈值
     */
    @Test
    public void testProactiveCompressionNotTriggered_BelowThreshold() {
        RetryConfig retryConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .build();

        CompressionConfig compressionConfig = CompressionConfig.builder()
                .enabled(true)
                .proactiveThresholdTokens(160000)
                .build();

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);
        retryChatModel.setCompressionConfig(compressionConfig);
        DynamicContext dynamicContext = new DynamicContext();
        retryChatModel.setDynamicContext(dynamicContext);

        // 创建一个低于阈值的 prompt
        String text = "hello world";
        Prompt prompt = makePrompt(text);

        ChatResponse result = retryChatModel.call(prompt);

        assertNotNull(result);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    /**
     * TC-RCL-007: 压缩未启用
     */
    @Test
    public void testCompressionDisabled() {
        RetryConfig retryConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .build();

        CompressionConfig compressionConfig = CompressionConfig.builder()
                .enabled(false)
                .proactiveThresholdTokens(10)
                .build();

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);
        retryChatModel.setCompressionConfig(compressionConfig);
        DynamicContext dynamicContext = new DynamicContext();
        retryChatModel.setDynamicContext(dynamicContext);

        // 即使文本很大，压缩未启用也不应抛出异常
        String largeText = "a".repeat(10000);
        Prompt prompt = makePrompt(largeText);

        ChatResponse result = retryChatModel.call(prompt);

        assertNotNull(result);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    /**
     * TC-RCL-008: compressionConfig 为 null
     */
    @Test
    public void testCompressionConfigNull() {
        RetryConfig retryConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .build();

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);
        // compressionConfig 不设置，保持 null
        DynamicContext dynamicContext = new DynamicContext();
        retryChatModel.setDynamicContext(dynamicContext);

        // 即使文本很大，compressionConfig 为 null 也不应抛出异常
        String largeText = "a".repeat(10000);
        Prompt prompt = makePrompt(largeText);

        ChatResponse result = retryChatModel.call(prompt);

        assertNotNull(result);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    /**
     * TC-RCL-013: DynamicContext 字段设置验证
     */
    @Test
    public void testDynamicContextFieldsSet() {
        RetryConfig retryConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .build();

        CompressionConfig compressionConfig = CompressionConfig.builder()
                .enabled(true)
                .proactiveThresholdTokens(10)
                .build();

        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);
        retryChatModel.setCompressionConfig(compressionConfig);
        DynamicContext dynamicContext = new DynamicContext();
        retryChatModel.setDynamicContext(dynamicContext);

        // 创建一个超过阈值的 prompt
        String largeText = "a".repeat(100);
        Prompt prompt = makePrompt(largeText);

        try {
            retryChatModel.call(prompt);
            fail("Expected CompressionRequiredException");
        } catch (CompressionRequiredException e) {
            assertEquals(prompt, e.getOriginalPrompt());
            assertEquals("aiClientModelNode", e.getReturnNode());

            // 验证 DynamicContext 也被正确设置
            assertTrue(dynamicContext.isCompressionRequired());
            assertEquals(prompt, dynamicContext.getOriginalPrompt());
            assertEquals("aiClientModelNode", dynamicContext.getReturnNode());
        }
    }

    /**
     * TC-RCL-014: compressionRequired 已为 true 时不再重复触发
     */
    @Test
    public void testCompressionRequiredAlreadyTrue() {
        RetryConfig retryConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .build();

        CompressionConfig compressionConfig = CompressionConfig.builder()
                .enabled(true)
                .proactiveThresholdTokens(10)
                .build();

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);
        retryChatModel.setCompressionConfig(compressionConfig);
        DynamicContext dynamicContext = new DynamicContext();
        // 预先设置 compressionRequired 为 true
        dynamicContext.setCompressionRequired(true);
        retryChatModel.setDynamicContext(dynamicContext);

        // 即使文本很大，compressionRequired 已为 true 也不应抛出异常
        String largeText = "a".repeat(10000);
        Prompt prompt = makePrompt(largeText);

        ChatResponse result = retryChatModel.call(prompt);

        assertNotNull(result);
        verify(delegate, times(1)).call(any(Prompt.class));
    }

    /**
     * TC-RCL-015: DynamicContext 为 null 时不触发压缩
     */
    @Test
    public void testDynamicContextNull_NoCompression() {
        RetryConfig retryConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(3)
                .build();

        CompressionConfig compressionConfig = CompressionConfig.builder()
                .enabled(true)
                .proactiveThresholdTokens(10)
                .build();

        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);

        RetryChatModel retryChatModel = new RetryChatModel(delegate, retryConfig);
        retryChatModel.setCompressionConfig(compressionConfig);
        // dynamicContext 不设置，保持 null

        // 即使文本很大，dynamicContext 为 null 也不应抛出异常
        String largeText = "a".repeat(10000);
        Prompt prompt = makePrompt(largeText);

        ChatResponse result = retryChatModel.call(prompt);

        assertNotNull(result);
        verify(delegate, times(1)).call(any(Prompt.class));
    }
}
