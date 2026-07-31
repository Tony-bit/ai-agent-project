package denny.ai.agent.domain.service.auto.step.chat;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.routing.IntentRoutingNode;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.service.armory.factory.element.RetryChatModel;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;
import reactor.test.publisher.TestPublisher;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * GeneralChatNode 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-GC-001: 正常对话_意图设置
 * 2. TC-GC-002: AMBIGUOUS意图_澄清引导
 * 3. TC-GC-003: UNKNOWN意图_降级处理
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
@RunWith(MockitoJUnitRunner.class)
public class GeneralChatNodeTest {

    private GeneralChatNode generalChatNode;

    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext;

    private ExecuteCommandEntity request;

    @Before
    public void setUp() {
        generalChatNode = new GeneralChatNode();

        dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();

        request = ExecuteCommandEntity.builder()
                .sessionId("test-session-123")
                .message("你好")
                .userId("user-001")
                .build();
    }

    // ========== TC-GC-001 ~ TC-GC-003: 核心功能测试 ==========

    /**
     * TC-GC-001: recognizedIntent 为 GENERAL_CHAT 时，正常设置
     */
    @Test
    public void testGeneralChatIntent_setsCorrectIntent() {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.GENERAL_CHAT);

        IntentTypeEnum intent = dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY);
        assertEquals(IntentTypeEnum.GENERAL_CHAT, intent);
    }

    /**
     * TC-GC-002: AMBIGUOUS意图_澄清引导
     * 验证 recognizedIntent 为 AMBIGUOUS 时，会设置对应的 prompt
     */
    @Test
    public void testAmbiguousIntent_setsCorrectIntent() {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.AMBIGUOUS);

        IntentTypeEnum intent = dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY);
        assertEquals(IntentTypeEnum.AMBIGUOUS, intent);
    }

    /**
     * TC-GC-003: UNKNOWN意图_降级处理
     * 验证 recognizedIntent 为 UNKNOWN 时，流程继续不阻断
     */
    @Test
    public void testUnknownIntent_setsCorrectIntent() {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.UNKNOWN);

        IntentTypeEnum intent = dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY);
        assertEquals(IntentTypeEnum.UNKNOWN, intent);
    }

    /**
     * TC-GC-004: recognizedIntent 为 null 时，使用默认处理
     */
    @Test
    public void testNullIntent_returnsNull() {
        IntentTypeEnum intent = dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY);
        assertNull(intent);
    }

    /**
     * TC-GC-005: dynamicContext 设置 generalChatResponse
     */
    @Test
    public void testGeneralChatResponse_storedInContext() {
        String response = "这是一段通用回复内容";
        dynamicContext.setValue("generalChatResponse", response);

        String storedResponse = dynamicContext.getValue("generalChatResponse");
        assertEquals(response, storedResponse);
    }

    @Test
    public void should_not_send_model_chunks_before_attempt_completion() throws Exception {
        Prompt prompt = new Prompt(new UserMessage("question"));
        ChatModel delegate = mock(ChatModel.class);
        TestPublisher<ChatResponse> firstAttempt = TestPublisher.create();
        CountDownLatch subscribed = new CountDownLatch(1);
        when(delegate.stream(prompt))
                .thenReturn(firstAttempt.flux().doOnSubscribe(ignored -> subscribed.countDown()))
                .thenReturn(Flux.just(response("successful")));
        RetryChatModel retryModel = new RetryChatModel(delegate, RetryConfig.builder()
                .enabled(true).maxAttempts(2).initialIntervalMs(0).maxIntervalMs(0).build());

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(retryModel.stream(prompt)
                .map(value -> value.getResult().getOutput().getText()));
        ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);
        dynamicContext.setValue("emitter", emitter);

        CompletableFuture<String> result = CompletableFuture.supplyAsync(() ->
                ReflectionTestUtils.invokeMethod(generalChatNode, "streamToEmitter",
                        dynamicContext, requestSpec, "general_chat_response", "session"));
        assertTrue(subscribed.await(2, TimeUnit.SECONDS));

        firstAttempt.next(response("discarded"));
        verify(emitter, times(1)).send(any(Object.class));
        firstAttempt.error(new RuntimeException("connection reset by peer"));

        assertEquals("successful", result.get(5, TimeUnit.SECONDS));
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(emitter, atLeast(3)).send(events.capture());
        String allEvents = events.getAllValues().toString();
        assertFalse(allEvents.contains("discarded"));
        assertTrue(allEvents.contains("successful"));
    }

    @Test
    public void should_propagate_stream_failure_after_sending_error_event() throws Exception {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.error(new IllegalStateException("tool failed")));
        ResponseBodyEmitter emitter = mock(ResponseBodyEmitter.class);
        dynamicContext.setValue("emitter", emitter);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                ReflectionTestUtils.invokeMethod(generalChatNode, "streamToEmitter",
                        dynamicContext, requestSpec, "general_chat_response", "session"));

        assertTrue(error.getMessage().contains("tool failed"));
        verify(emitter, atLeast(2)).send(any(Object.class));
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
