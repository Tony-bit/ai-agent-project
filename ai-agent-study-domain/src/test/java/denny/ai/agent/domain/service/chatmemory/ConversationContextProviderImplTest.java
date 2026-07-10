package denny.ai.agent.domain.service.chatmemory;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ConversationMemorySnapshot;
import denny.ai.agent.domain.model.entity.RoutingConversationContext;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ConversationContextProviderImplTest {

    @Mock
    private ConversationMemoryService conversationMemoryService;

    private ConversationContextProviderImpl provider;

    @Before
    public void setUp() throws Exception {
        provider = new ConversationContextProviderImpl();
        set(provider, "conversationMemoryService", conversationMemoryService);
        set(provider, "runtimeWindowSize", 20);
    }

    @Test
    public void getRoutingContext_formatsRoleAndContentOnly() {
        when(conversationMemoryService.loadSnapshot(eq("s1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ConversationMemorySnapshot.builder()
                        .sessionId("s1")
                        .durable(true)
                        .recentMessages(List.of(
                                ChatMessageEntity.builder().role("user").content("hello").build(),
                                ChatMessageEntity.builder().role(null).content("ignored").build(),
                                ChatMessageEntity.builder().role("assistant").content("hi").build()))
                        .build());

        RoutingConversationContext context = provider.getRoutingContext("s1");

        assertEquals(List.of("user: hello", "assistant: hi"), context.getHistoryMessages());
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
