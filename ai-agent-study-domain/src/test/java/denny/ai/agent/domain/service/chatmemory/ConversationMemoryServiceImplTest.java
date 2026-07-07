package denny.ai.agent.domain.service.chatmemory;

import denny.ai.agent.domain.adapter.repository.IChatMemoryRepository;
import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ChatSessionEntity;
import denny.ai.agent.domain.model.entity.ConversationMemoryOptions;
import denny.ai.agent.domain.model.entity.ConversationMemorySnapshot;
import denny.ai.agent.domain.model.entity.ConversationRuntimeWindow;
import denny.ai.agent.domain.model.entity.ConversationTurn;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ConversationMemoryServiceImplTest {

    @Mock
    private IChatMemoryRepository chatMemoryRepository;

    private ConversationMemoryServiceImpl service;

    @Before
    public void setUp() throws Exception {
        service = new ConversationMemoryServiceImpl();
        set(service, "chatMemoryRepository", chatMemoryRepository);
        set(service, "runtimeWindowSize", 20);
        set(service, "localCacheTtlMinutes", 60);
        set(service, "localCacheMaxSessions", 10000);
        service.init();
    }

    @Test
    public void loadSnapshot_redisHitBackfillsLocalCache() {
        ConversationRuntimeWindow redisWindow = ConversationRuntimeWindow.builder()
                .sessionId("s1")
                .durable(true)
                .source(ConversationRuntimeWindow.SOURCE_DURABLE_REBUILD)
                .runtimeVersion(2)
                .durableVersion(2)
                .recentMessages(List.of(message(1, "user", "hello"), message(2, "assistant", "hi")))
                .updatedAt(LocalDateTime.now())
                .build();
        when(chatMemoryRepository.getCachedRuntimeWindowFromRedis("s1")).thenReturn(redisWindow);

        ConversationMemorySnapshot first = service.loadSnapshot("s1", ConversationMemoryOptions.defaults());
        ConversationMemorySnapshot second = service.loadSnapshot("s1", ConversationMemoryOptions.defaults());

        assertEquals(2, first.getRecentMessages().size());
        assertEquals(2, second.getRecentMessages().size());
        verify(chatMemoryRepository, times(1)).getCachedRuntimeWindowFromRedis("s1");
        verify(chatMemoryRepository, never()).queryMessagesBySessionId("s1");
    }

    @Test
    public void loadSnapshot_mysqlHitRebuildsDurableRuntimeWindow() {
        when(chatMemoryRepository.getCachedRuntimeWindowFromRedis("s2")).thenReturn(null);
        when(chatMemoryRepository.queryMessagesBySessionId("s2")).thenReturn(messages(25));

        ConversationMemorySnapshot snapshot = service.loadSnapshot("s2", ConversationMemoryOptions.defaults());

        assertTrue(snapshot.isDurable());
        assertEquals(20, snapshot.getRecentMessages().size());
        assertEquals(Integer.valueOf(6), snapshot.getRecentMessages().get(0).getMessageIndex());
        ArgumentCaptor<ConversationRuntimeWindow> captor = ArgumentCaptor.forClass(ConversationRuntimeWindow.class);
        verify(chatMemoryRepository).cacheRuntimeWindowToRedis(eq("s2"), captor.capture(), eq(20));
        assertTrue(captor.getValue().isDurable());
        assertEquals(ConversationRuntimeWindow.SOURCE_DURABLE_REBUILD, captor.getValue().getSource());
    }

    @Test
    public void refreshRuntimeCache_marksWindowAsNonDurable() {
        service.refreshRuntimeCache("s3", List.of(message(1, "user", "hello")));

        ArgumentCaptor<ConversationRuntimeWindow> captor = ArgumentCaptor.forClass(ConversationRuntimeWindow.class);
        verify(chatMemoryRepository).cacheRuntimeWindowToRedis(eq("s3"), captor.capture(), eq(20));
        assertFalse(captor.getValue().isDurable());
        assertEquals(ConversationRuntimeWindow.SOURCE_ADVISOR_RUNTIME, captor.getValue().getSource());
    }

    @Test
    public void saveTurn_writesMysqlThenRebuildsDurableRuntimeWindow() {
        when(chatMemoryRepository.querySessionBySessionId("s4")).thenReturn(null);
        when(chatMemoryRepository.queryMessagesBySessionId("s4"))
                .thenReturn(List.of())
                .thenReturn(List.of(message(1, "user", "q"), message(2, "assistant", "a")));

        service.saveTurn(ConversationTurn.builder()
                .sessionId("s4")
                .userId("u1")
                .agentId("a1")
                .clientId("c1")
                .query("q")
                .response("a")
                .model("m")
                .latencyMs(10L)
                .traceId("t1")
                .build());

        verify(chatMemoryRepository).saveSession(any(ChatSessionEntity.class));
        verify(chatMemoryRepository, times(2)).saveMessage(any(ChatMessageEntity.class));
        verify(chatMemoryRepository).updateSessionLastResponse("s4", "a", 2);
        ArgumentCaptor<ConversationRuntimeWindow> captor = ArgumentCaptor.forClass(ConversationRuntimeWindow.class);
        verify(chatMemoryRepository).cacheRuntimeWindowToRedis(eq("s4"), captor.capture(), anyInt());
        assertTrue(captor.getValue().isDurable());
    }

    private List<ChatMessageEntity> messages(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> message(i, i % 2 == 0 ? "assistant" : "user", "m" + i))
                .toList();
    }

    private ChatMessageEntity message(int index, String role, String content) {
        return ChatMessageEntity.builder()
                .sessionId("s")
                .messageIndex(index)
                .role(role)
                .content(content)
                .createTime(LocalDateTime.now())
                .build();
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
