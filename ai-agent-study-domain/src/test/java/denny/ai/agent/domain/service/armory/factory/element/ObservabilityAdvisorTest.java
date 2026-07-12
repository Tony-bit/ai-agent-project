package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.service.observability.ObservabilityService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ObservabilityAdvisorTest {

    @Mock
    private ObservabilityService observabilityService;

    @Mock
    private CallAdvisorChain callAdvisorChain;

    private ObservabilityAdvisor observabilityAdvisor;

    @Before
    public void setUp() {
        observabilityAdvisor = new ObservabilityAdvisor(observabilityService);
    }

    @Test
    public void testBeforeShouldStoreInputInContext() {
        when(observabilityService.startTrace(anyString(), anyString(), anyMap())).thenReturn("trace-1");
        when(observabilityService.startSpan(anyString(), anyString(), anyMap())).thenReturn("span-1");

        ChatClientRequest request = buildRequest("请解释一下 PE");

        ChatClientRequest advisedRequest = observabilityAdvisor.before(request, callAdvisorChain);

        assertEquals("请解释一下 PE", advisedRequest.context().get("input"));
        assertEquals("trace-1", advisedRequest.context().get("trace_id"));
        assertEquals("span-1", advisedRequest.context().get("span_id"));
        assertTrue(advisedRequest.context().containsKey("observe_start_at"));
    }

    @Test
    public void testAfterShouldLogOutputFromGenerationResponse() {
        ChatClientResponse response = buildResponse("模型输出内容", buildContext());

        observabilityAdvisor.after(response, callAdvisorChain);

        verify(observabilityService).logGeneration(
                eq("trace-1"),
                eq("span-1"),
                eq("chat-client"),
                eq("用户问题"),
                eq("模型输出内容"),
                anyMap(),
                anyMap());
        verify(observabilityService).endSpan("span-1", true, null);
    }

    @Test
    public void testAdviseCallShouldEndSpanWhenChainThrows() {
        when(observabilityService.startTrace(anyString(), anyString(), anyMap())).thenReturn("trace-1");
        when(observabilityService.startSpan(anyString(), anyString(), anyMap())).thenReturn("span-1");
        when(callAdvisorChain.nextCall(any(ChatClientRequest.class))).thenThrow(new RuntimeException("boom"));

        try {
            observabilityAdvisor.adviseCall(buildRequest("用户问题"), callAdvisorChain);
        } catch (RuntimeException e) {
            assertEquals("boom", e.getMessage());
        }

        verify(observabilityService).endSpan("span-1", false, "boom");
    }

    private ChatClientRequest buildRequest(String userText) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage(userText)))
                .context(new HashMap<>())
                .build();
    }

    private ChatClientResponse buildResponse(String output, Map<String, Object> context) {
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(context)
                .build();
    }

    private Map<String, Object> buildContext() {
        Map<String, Object> context = new HashMap<>();
        context.put("trace_id", "trace-1");
        context.put("span_id", "span-1");
        context.put("observe_start_at", String.valueOf(System.currentTimeMillis() - 5));
        context.put("input", "用户问题");
        context.put("qa_retrieved_documents", "doc1\ndoc2");
        return context;
    }
}
