package denny.ai.agent.domain.service.chatmemory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.verifyNoInteractions;

@RunWith(MockitoJUnitRunner.class)
public class ConversationContextAdvisorTest {

    @Mock
    private ConversationContextProvider conversationContextProvider;

    @Mock
    private SpringAiConversationMemoryRepository chatMemoryRepository;

    @Mock
    private AdvisorChain advisorChain;

    @Test
    public void skipsDuplicateHistoryInjectionWhenRoutingContextIsPreloaded() {
        ConversationContextAdvisor advisor = new ConversationContextAdvisor(
                conversationContextProvider, chatMemoryRepository, 20);
        ChatClientRequest request = new ChatClientRequest(new Prompt("route"), Map.of(
                ConversationContextAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, "session-consistent",
                ConversationContextAdvisor.CONVERSATION_CONTEXT_SCENE_KEY,
                ConversationContextAdvisor.SCENE_DECOMPOSITION,
                ConversationContextAdvisor.CONVERSATION_CONTEXT_PRELOADED_KEY, true));

        ChatClientRequest result = advisor.before(request, advisorChain);

        assertSame(request, result);
        verifyNoInteractions(conversationContextProvider, chatMemoryRepository);
    }
}
