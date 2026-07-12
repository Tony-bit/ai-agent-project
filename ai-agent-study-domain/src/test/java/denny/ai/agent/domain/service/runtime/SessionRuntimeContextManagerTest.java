package denny.ai.agent.domain.service.runtime;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.valobj.runtime.SessionRuntimeContext;
import denny.ai.agent.domain.service.chatmemory.ChatMemoryPersistenceService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class SessionRuntimeContextManagerTest {

    @Mock
    private ChatMemoryPersistenceService chatMemoryPersistenceService;

    private SessionRuntimeContextManager manager;

    @Before
    public void setUp() throws Exception {
        manager = new SessionRuntimeContextManager();
        set(manager, "chatMemoryPersistenceService", chatMemoryPersistenceService);
        set(manager, "ttlMs", 300_000L);
    }

    @Test
    public void formatsAndCachesRecentHistoryMessages() {
        when(chatMemoryPersistenceService.getConversationHistory("s1")).thenReturn(List.of(
                ChatMessageEntity.builder().role("user").content("hello").messageIndex(1).build(),
                ChatMessageEntity.builder().role("assistant").content("hi").messageIndex(2).build(),
                ChatMessageEntity.builder().role(null).content("ignored").messageIndex(3).build()
        ));

        SessionRuntimeContext first = manager.getOrLoad("s1", "u1");
        SessionRuntimeContext second = manager.getOrLoad("s1", "u1");

        assertEquals(List.of("user: hello", "assistant: hi"), first.getRecentHistoryMessages());
        assertEquals(Integer.valueOf(3), first.getLastMessageIndex());
        assertEquals(first, second);
        verify(chatMemoryPersistenceService, times(1)).getConversationHistory("s1");
    }

    @Test
    public void returnsEmptyContextWhenHistoryLoadFails() {
        when(chatMemoryPersistenceService.getConversationHistory("s1")).thenThrow(new RuntimeException("redis down"));

        SessionRuntimeContext context = manager.getOrLoad("s1", "u1");

        assertEquals("s1", context.getSessionId());
        assertEquals("u1", context.getUserId());
        assertEquals(List.of(), context.getRecentHistoryMessages());
    }

    @Test
    public void reloadsHistoryWhenCacheIsDisabledByDefaultTtl() throws Exception {
        set(manager, "ttlMs", 0L);
        when(chatMemoryPersistenceService.getConversationHistory("s1"))
                .thenReturn(List.of(ChatMessageEntity.builder().role("user").content("first").messageIndex(1).build()))
                .thenReturn(List.of(ChatMessageEntity.builder().role("user").content("second").messageIndex(2).build()));

        SessionRuntimeContext first = manager.getOrLoad("s1", "u1");
        SessionRuntimeContext second = manager.getOrLoad("s1", "u1");

        assertEquals(List.of("user: first"), first.getRecentHistoryMessages());
        assertEquals(List.of("user: second"), second.getRecentHistoryMessages());
        verify(chatMemoryPersistenceService, times(2)).getConversationHistory("s1");
    }

    @Test
    public void blankSessionIdDoesNotLoadOrCacheHistory() {
        SessionRuntimeContext context = manager.getOrLoad(" ", "u1");

        assertEquals(" ", context.getSessionId());
        assertEquals(List.of(), context.getRecentHistoryMessages());
        verify(chatMemoryPersistenceService, times(0)).getConversationHistory(org.mockito.Mockito.any());
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
