package denny.ai.agent.domain.service.chatmemory;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ConversationMemorySnapshot;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class SpringAiConversationMemoryRepositoryTest {

    @Mock
    private ConversationMemoryService conversationMemoryService;

    private SpringAiConversationMemoryRepository repository;

    @Before
    public void setUp() throws Exception {
        repository = new SpringAiConversationMemoryRepository();
        set(repository, "conversationMemoryService", conversationMemoryService);
    }

    @Test
    public void findByConversationId_loadsMessagesFromUnifiedMemory() {
        when(conversationMemoryService.loadSnapshot(eq("s1"), any())).thenReturn(ConversationMemorySnapshot.builder()
                .sessionId("s1")
                .recentMessages(List.of(
                        ChatMessageEntity.builder().role(ChatMessageEntity.ROLE_USER).content("hello").build(),
                        ChatMessageEntity.builder().role(ChatMessageEntity.ROLE_ASSISTANT).content("hi").build()))
                .build());

        List<Message> messages = repository.findByConversationId("s1");

        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof UserMessage);
        assertTrue(messages.get(1) instanceof AssistantMessage);
    }

    @Test
    public void saveAll_refreshesRuntimeCacheOnly() {
        repository.saveAll("s2", List.of(new UserMessage("hello"), new AssistantMessage("hi")));

        ArgumentCaptor<List<ChatMessageEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(conversationMemoryService).refreshRuntimeCache(eq("s2"), captor.capture());
        assertEquals(2, captor.getValue().size());
        assertEquals(ChatMessageEntity.ROLE_USER, captor.getValue().get(0).getRole());
        assertEquals(ChatMessageEntity.ROLE_ASSISTANT, captor.getValue().get(1).getRole());
    }

    @Test
    public void deleteByConversationId_clearsRuntimeMemory() {
        repository.deleteByConversationId("s3");

        verify(conversationMemoryService).clearRuntimeMemory("s3");
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
