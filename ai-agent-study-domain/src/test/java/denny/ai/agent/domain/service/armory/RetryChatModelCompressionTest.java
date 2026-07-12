package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.service.armory.factory.element.CompressionPolicy;
import denny.ai.agent.domain.service.armory.factory.element.RetryChatModel;
import denny.ai.agent.domain.service.compression.PromptCompressionService;
import denny.ai.agent.domain.service.runtime.RetryRuntimeContextHolder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class RetryChatModelCompressionTest {

    @Mock
    private ChatModel delegate;

    @Mock
    private PromptCompressionService compressionService;

    @Mock
    private ChatResponse successResponse;

    @Test
    public void proactiveCompressionUsesCapturedRuntimeContext() {
        Prompt original = prompt("a".repeat(500));
        Prompt compressed = prompt("x");
        RetryRuntimeContext context = context("session-a");
        when(compressionService.compress(eq(original), eq(context), any())).thenReturn(compressed);
        when(delegate.call(compressed)).thenReturn(successResponse);

        ChatResponse response = RetryRuntimeContextHolder.withContext(context,
                () -> model(true, 1).call(original));

        assertSame(successResponse, response);
        verify(delegate).call(compressed);
    }

    @Test
    public void passiveOverflowCompressesAndCallsDelegateAgain() {
        Prompt original = prompt("a".repeat(500));
        Prompt compressed = prompt("x");
        RetryRuntimeContext context = context("session-a");
        when(delegate.call(original)).thenThrow(new RuntimeException("{\"error\":{\"code\":\"1261\"}}"));
        when(delegate.call(compressed)).thenReturn(successResponse);
        when(compressionService.compress(eq(original), eq(context), any())).thenReturn(compressed);

        ChatResponse response = RetryRuntimeContextHolder.withContext(context,
                () -> model(true, Integer.MAX_VALUE).call(original));

        assertSame(successResponse, response);
        verify(delegate, times(2)).call(any(Prompt.class));
    }

    @Test
    public void sequentialRequestsDoNotShareRuntimeContext() {
        Prompt first = prompt("a".repeat(500));
        Prompt second = prompt("b".repeat(500));
        Prompt compressedFirst = prompt("x");
        Prompt compressedSecond = prompt("y");
        RetryRuntimeContext firstContext = context("session-a");
        RetryRuntimeContext secondContext = context("session-b");
        when(compressionService.compress(eq(first), eq(firstContext), any())).thenReturn(compressedFirst);
        when(compressionService.compress(eq(second), eq(secondContext), any())).thenReturn(compressedSecond);
        when(delegate.call(any(Prompt.class))).thenReturn(successResponse);
        RetryChatModel model = model(true, 1);

        RetryRuntimeContextHolder.withContext(firstContext, () -> model.call(first));
        RetryRuntimeContextHolder.withContext(secondContext, () -> model.call(second));

        verify(compressionService).compress(eq(first), eq(firstContext), any());
        verify(compressionService).compress(eq(second), eq(secondContext), any());
    }

    @Test
    public void disabledCompressionCallsDelegateDirectly() {
        Prompt prompt = prompt("a".repeat(500));
        when(delegate.call(prompt)).thenReturn(successResponse);

        assertSame(successResponse, model(false, 1).call(prompt));

        verify(compressionService, never()).compress(any(), any(), any());
    }

    private RetryChatModel model(boolean compressionEnabled, int threshold) {
        RetryConfig retryConfig = RetryConfig.builder().enabled(true).maxAttempts(2).build();
        CompressionPolicy policy = CompressionPolicy.builder()
                .enabled(compressionEnabled)
                .proactiveThresholdTokens(threshold)
                .maxCompressionAttempts(2)
                .build();
        return new RetryChatModel(delegate, retryConfig, policy, compressionService, null);
    }

    private RetryRuntimeContext context(String sessionId) {
        return RetryRuntimeContext.builder().sessionId(sessionId).traceId("trace").build();
    }

    private Prompt prompt(String text) {
        return new Prompt(new UserMessage(text));
    }
}
