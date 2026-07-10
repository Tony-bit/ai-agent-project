package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.service.observability.ObservabilityService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ObservabilityAdvisorTest {

    @Mock
    private ObservabilityService observabilityService;

    @Mock
    private StreamAdvisorChain streamAdvisorChain;

    private ObservabilityAdvisor advisor;

    @Before
    public void setUp() {
        advisor = new ObservabilityAdvisor(observabilityService);
    }

    @Test
    public void testAdviseStream_logsAggregatedOutputOnceOnCompletion() {
        when(observabilityService.startTrace(eq("session-1"), eq("你好"), anyMap())).thenReturn("trace-1");
        when(observabilityService.startSpan(eq("trace-1"), eq("chat_client_call"), anyMap())).thenReturn("span-1");
        when(streamAdvisorChain.nextStream(any())).thenAnswer(invocation -> {
            ChatClientRequest advisedRequest = invocation.getArgument(0);
            return Flux.just(
                    createResponse("你", advisedRequest.context()),
                    createResponse("好", advisedRequest.context())
            );
        });

        Map<String, Object> context = new HashMap<>();
        context.put("chat_memory_conversation_id", "session-1");
        ChatClientRequest request = new ChatClientRequest(new Prompt("你好"), context);

        advisor.adviseStream(request, streamAdvisorChain).collectList().block();

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> outputCaptor = ArgumentCaptor.forClass(String.class);
        verify(observabilityService).logGeneration(
                eq("trace-1"),
                eq("span-1"),
                eq("chat-client"),
                promptCaptor.capture(),
                outputCaptor.capture(),
                anyMap(),
                anyMap()
        );
        verify(observabilityService).endTrace(eq("trace-1"), eq("你好"), anyMap());
        verify(observabilityService).endSpan("span-1", true, null);

        assertEquals("你好", promptCaptor.getValue());
        assertEquals("你好", outputCaptor.getValue());
    }

    private ChatClientResponse createResponse(String text, Map<String, Object> context) {
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        return new ChatClientResponse(chatResponse, context);
    }
}
