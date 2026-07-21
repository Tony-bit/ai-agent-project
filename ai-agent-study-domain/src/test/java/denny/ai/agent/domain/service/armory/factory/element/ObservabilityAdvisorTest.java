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
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ObservabilityAdvisorTest {

    @Mock
    private ObservabilityService observabilityService;

    @Mock
    private CallAdvisorChain callAdvisorChain;

    @Mock
    private StreamAdvisorChain streamAdvisorChain;

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
                eq("test-model"),
                eq("用户问题"),
                eq("模型输出内容"),
                anyMap(),
                anyMap());
        verify(observabilityService).endSpan("span-1", true, null);
        verify(observabilityService).endTrace(eq("trace-1"), eq("模型输出内容"), anyMap());
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
        verify(observabilityService).endTrace(eq("trace-1"), eq(""), anyMap());
    }

    @Test
    public void testAdviseStreamShouldLogAggregatedOutputOnceOnCompletion() {
        when(observabilityService.startTrace(eq("session-1"), eq("你好"), anyMap())).thenReturn("trace-1");
        when(observabilityService.startSpan(eq("trace-1"), eq("chat_client_call"), anyMap())).thenReturn("span-1");
        when(streamAdvisorChain.nextStream(any())).thenAnswer(invocation -> {
            ChatClientRequest advisedRequest = invocation.getArgument(0);
            return Flux.just(
                    buildResponse("你", advisedRequest.context()),
                    buildResponse("好", advisedRequest.context())
            );
        });

        Map<String, Object> context = new HashMap<>();
        context.put("chat_memory_conversation_id", "session-1");
        ChatClientRequest request = new ChatClientRequest(new Prompt("你好"), context);

        observabilityAdvisor.adviseStream(request, streamAdvisorChain).collectList().block();

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> outputCaptor = ArgumentCaptor.forClass(String.class);
        verify(observabilityService).logGeneration(
                eq("trace-1"),
                eq("span-1"),
                eq("test-model"),
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

    @Test
    public void sharedTraceShouldEndSpanButNotRootTrace() {
        when(observabilityService.startSpan(eq("root-trace"), anyString(), anyMap())).thenReturn("span-1");
        Map<String, Object> context = new HashMap<>();
        context.put("trace_id", "root-trace");
        ChatClientRequest advisedRequest = observabilityAdvisor.before(
                new ChatClientRequest(new Prompt("hello"), context), callAdvisorChain);

        observabilityAdvisor.after(buildResponse("world", advisedRequest.context()), callAdvisorChain);

        verify(observabilityService, never()).startTrace(anyString(), anyString(), anyMap());
        verify(observabilityService).endSpan("span-1", true, null);
        verify(observabilityService, never()).endTrace(anyString(), anyString(), anyMap());
    }

    @Test
    public void observationNameShouldNameSpanAndGeneration() {
        when(observabilityService.startSpan(eq("root-trace"), eq("unified-routing"), anyMap()))
                .thenReturn("span-1");
        Map<String, Object> context = new HashMap<>();
        context.put("trace_id", "root-trace");
        context.put("observation_name", "unified-routing");

        ChatClientRequest advisedRequest = observabilityAdvisor.before(
                new ChatClientRequest(new Prompt("route me"), context), callAdvisorChain);
        observabilityAdvisor.after(buildResponse("routed", advisedRequest.context()), callAdvisorChain);

        verify(observabilityService).startSpan(eq("root-trace"), eq("unified-routing"), anyMap());
        verify(observabilityService).logGeneration(
                eq("root-trace"), eq("span-1"), eq("test-model"),
                eq("route me"), eq("routed"),
                org.mockito.ArgumentMatchers.argThat(metadata ->
                        "unified-routing".equals(metadata.get("observationName"))),
                anyMap());
    }

    private ChatClientRequest buildRequest(String userText) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage(userText)))
                .context(new HashMap<>())
                .build();
    }

    private ChatClientResponse buildResponse(String output, Map<String, Object> context) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(output))),
                ChatResponseMetadata.builder().model("test-model").build());
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
        context.put("observability_trace_owned", true);
        context.put("qa_retrieved_documents", "doc1\ndoc2");
        return context;
    }
}
