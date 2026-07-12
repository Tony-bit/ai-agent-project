package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.CompressionConfig;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import denny.ai.agent.domain.service.armory.factory.element.RetryChatModel;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class CompressionRetryIntegrationTest {

    @Mock
    private ChatModel delegate;

    @Mock
    private ChatResponse successResponse;

    private RetryChatModel retryChatModel;
    private Prompt originalPrompt;

    @Before
    public void setUp() {
        RetryConfig retryConfig = RetryConfig.builder()
                .enabled(true)
                .maxAttempts(2)
                .initialIntervalMs(0)
                .maxIntervalMs(0)
                .retryableErrorCodes(List.of("429"))
                .build();
        CompressionConfig compressionConfig = CompressionConfig.builder()
                .enabled(true)
                .proactiveThresholdTokens(Integer.MAX_VALUE)
                .maxCompressionAttempts(1)
                .build();

        retryChatModel = new RetryChatModel(delegate, retryConfig);
        retryChatModel.setCompressionConfig(compressionConfig);
        retryChatModel.setDynamicContext(new DynamicContext());
        originalPrompt = Prompt.builder()
                .messages(new UserMessage("current question"))
                .build();
    }

    @Test
    public void contextOverflowCompressesAndCallsDelegateAgain() {
        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"1261\"}}"))
                .thenReturn(successResponse);

        ChatResponse response = retryChatModel.call(originalPrompt);

        assertSame(successResponse, response);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate, times(2)).call(promptCaptor.capture());
        assertNotEquals(promptCaptor.getAllValues().get(0), promptCaptor.getAllValues().get(1));
    }

    @Test
    public void rateLimitRetriesTheSamePrompt() {
        when(delegate.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("{\"error\":{\"code\":\"429\"}}"))
                .thenReturn(successResponse);

        ChatResponse response = retryChatModel.call(originalPrompt);

        assertSame(successResponse, response);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate, times(2)).call(promptCaptor.capture());
        assertEquals(promptCaptor.getAllValues().get(0), promptCaptor.getAllValues().get(1));
    }
}
