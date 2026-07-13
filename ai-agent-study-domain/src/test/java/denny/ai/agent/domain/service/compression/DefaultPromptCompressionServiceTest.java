package denny.ai.agent.domain.service.compression;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.service.armory.factory.element.CompressionPolicy;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.domain.service.runtime.RetryRuntimeContextHolder;
import denny.ai.agent.domain.util.TokenCountUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DefaultPromptCompressionServiceTest {

    @Mock
    private ApplicationContext applicationContext;

    private ChatClient compressionClient;
    private DefaultPromptCompressionService service;

    @Before
    public void setUp() {
        compressionClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        org.mockito.Mockito.lenient().when(applicationContext.containsBean(DefaultPromptCompressionService.COMPRESSION_CLIENT_BEAN))
                .thenReturn(true);
        org.mockito.Mockito.lenient().when(applicationContext.getBean(DefaultPromptCompressionService.COMPRESSION_CLIENT_BEAN, ChatClient.class))
                .thenReturn(compressionClient);
        service = new DefaultPromptCompressionService(applicationContext);
    }

    @Test
    public void preservesSystemCurrentUserTailAndOptions() {
        when(compressionClient.prompt(anyString()).call().content())
                .thenReturn("<分析>ignore</分析><摘要>compact history</摘要>");
        SystemMessage system = new SystemMessage("system instruction");
        UserMessage oldUser = new UserMessage("old question");
        AssistantMessage oldAssistant = new AssistantMessage("old answer");
        UserMessage currentUser = new UserMessage("current question");
        AssistantMessage tail = new AssistantMessage("tool continuation");
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("model-a").temperature(0.2).build();
        Prompt original = new Prompt(List.of(system, oldUser, oldAssistant, currentUser, tail), options);
        RetryRuntimeContext context = context(new ArrayList<>(List.of(
                message("user", "old question"), message("assistant", "old answer"))));

        Prompt compressed = service.compress(original, context, policy(4096, "unused-template"));

        assertSame(options, compressed.getOptions());
        assertEquals(4, compressed.getInstructions().size());
        assertSame(system, compressed.getInstructions().get(0));
        assertTrue(compressed.getInstructions().get(1) instanceof SystemMessage);
        assertTrue(compressed.getInstructions().get(1).getText().contains("compact history"));
        assertSame(currentUser, compressed.getInstructions().get(2));
        assertSame(tail, compressed.getInstructions().get(3));
        assertFalse(compressed.getInstructions().contains(oldUser));
        assertFalse(compressed.getInstructions().contains(oldAssistant));
    }

    @Test
    public void compressionRequestFitsBudgetAndDoesNotMutateHistory() {
        when(compressionClient.prompt(anyString()).call().content()).thenReturn("summary");
        clearInvocations(compressionClient);
        ChatMessageEntity old = message("user", "old ".repeat(2000));
        ChatMessageEntity recent = message("assistant", "recent marker");
        List<ChatMessageEntity> source = new ArrayList<>(List.of(old, recent));
        RetryRuntimeContext context = context(source);
        Prompt original = new Prompt(List.of(new SystemMessage("system"), new UserMessage("current")));

        service.compress(original, context, policy(1400, "template"));

        ArgumentCaptor<String> requestCaptor = ArgumentCaptor.forClass(String.class);
        verify(compressionClient).prompt(requestCaptor.capture());
        int budget = 1400 - 100 - 1024;
        assertTrue(TokenCountUtils.estimate(requestCaptor.getValue()) <= budget);
        assertTrue(requestCaptor.getValue().contains("recent marker"));
        assertEquals("old ".repeat(2000), source.get(0).getContent());
        assertEquals(2, source.size());
    }

    @Test
    public void failsWhenNoRuntimeOrPromptHistoryExists() {
        Prompt prompt = new Prompt(new UserMessage("current only"));

        CompressionExhaustedException error = assertThrows(CompressionExhaustedException.class,
                () -> service.compress(prompt, null, policy(4096, "template")));

        assertTrue(error.getMessage().contains("no compressible history"));
    }

    @Test
    public void completeClientDoesNotResolveModelOrRepeatTemplate() {
        when(compressionClient.prompt(anyString()).call().content()).thenReturn("summary");
        clearInvocations(compressionClient);
        Prompt original = new Prompt(List.of(new SystemMessage("system"), new UserMessage("current")));

        service.compress(original, context(List.of(message("user", "history"))),
                policy(4096, "DO_NOT_REPEAT_TEMPLATE"));

        ArgumentCaptor<String> requestCaptor = ArgumentCaptor.forClass(String.class);
        verify(compressionClient).prompt(requestCaptor.capture());
        assertFalse(requestCaptor.getValue().contains("DO_NOT_REPEAT_TEMPLATE"));
        verify(applicationContext, never()).getBean("ai_client_model_compression-model", ChatModel.class);
    }

    @Test
    public void fallsBackToSpringCompressionClientWhenRegistryIsEmpty() {
        ChatClient springClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(applicationContext.containsBean(DefaultPromptCompressionService.COMPRESSION_CLIENT_BEAN))
                .thenReturn(false);
        when(applicationContext.getBean(DefaultPromptCompressionService.COMPRESSION_CLIENT_BEAN,
                ChatClient.class)).thenReturn(springClient);
        when(springClient.prompt(anyString()).call().content()).thenReturn("summary");
        clearInvocations(springClient);
        Prompt original = new Prompt(List.of(new SystemMessage("original system"), new UserMessage("current")));

        service.compress(original, context(List.of(message("user", "history"))),
                policy(4096, "compression system template"));

        verify(springClient).prompt(anyString());
    }

    @Test
    public void resolvesCompressionClientFromRegistryFirst() {
        ArmoryObjectRegistry registry = new ArmoryObjectRegistry();
        ChatClient registryClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        registry.registerGlobalCompressionClient("3202", registryClient);
        when(registryClient.prompt(anyString()).call().content()).thenReturn("summary");
        clearInvocations(registryClient);
        DefaultPromptCompressionService registryService =
                new DefaultPromptCompressionService(applicationContext, registry);

        registryService.compress(new Prompt(new UserMessage("current")),
                context(List.of(message("user", "history"))), policy(4096, "unused"));

        verify(registryClient).prompt(anyString());
        verify(applicationContext, never()).containsBean(DefaultPromptCompressionService.COMPRESSION_CLIENT_BEAN);
    }

    @Test
    public void compressionCallUsesNestedScopeAndRestoresOuterContext() {
        RetryRuntimeContext outer = context(List.of(message("user", "history")));
        when(compressionClient.prompt(anyString()).call().content()).thenAnswer(invocation -> {
            assertTrue(RetryRuntimeContextHolder.current().isCompressionCall());
            return "summary";
        });

        RetryRuntimeContextHolder.withContext(outer, () -> {
            service.compress(new Prompt(new UserMessage("current")), outer, policy(4096, "template"));
            assertSame(outer, RetryRuntimeContextHolder.current());
            return null;
        });
    }

    @Test
    public void compressionModelOverflowBecomesDomainException() {
        RuntimeException overflow = new RuntimeException("{\"error\":{\"code\":\"1261\"}}");
        when(compressionClient.prompt(anyString()).call().content()).thenThrow(overflow);

        CompressionExhaustedException error = assertThrows(CompressionExhaustedException.class,
                () -> service.compress(new Prompt(new UserMessage("current")),
                        context(List.of(message("user", "history"))), policy(4096, "template")));

        assertSame(overflow, error.getCause());
        assertTrue(error.getMessage().contains("compression model context window"));
    }

    private CompressionPolicy policy(int threshold, String template) {
        return CompressionPolicy.builder()
                .proactiveThresholdTokens(threshold)
                .maxCompressionAttempts(2)
                .maxSummaryTokens(100)
                .promptTemplate(template)
                .build();
    }

    private RetryRuntimeContext context(List<ChatMessageEntity> messages) {
        return RetryRuntimeContext.builder()
                .sessionId("session")
                .traceId("trace")
                .recentMessages(messages)
                .build();
    }

    private ChatMessageEntity message(String role, String content) {
        return ChatMessageEntity.builder().role(role).content(content).build();
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
