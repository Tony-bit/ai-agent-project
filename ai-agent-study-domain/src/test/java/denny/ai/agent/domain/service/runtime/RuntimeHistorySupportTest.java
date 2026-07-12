package denny.ai.agent.domain.service.runtime;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.chatmemory.ChatMemoryPersistenceService;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RuntimeHistorySupportTest {

    @Test
    public void readsPreparedHistoryWhenListContainsOnlyStrings() {
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext context =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        context.setValue(RuntimeContextKeys.RECENT_HISTORY_MESSAGES, List.of("user: hello"));

        assertEquals(List.of("user: hello"), RuntimeHistorySupport.preparedHistory(context).orElseThrow());
    }

    @Test
    public void returnsEmptyOptionalWhenPreparedHistoryTypeIsInvalid() {
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext context =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        context.setValue(RuntimeContextKeys.RECENT_HISTORY_MESSAGES, "user: hello");

        assertTrue(RuntimeHistorySupport.preparedHistory(context).isEmpty());
    }

    @Test
    public void legacyLoaderFormatsAndFiltersHistory() {
        ChatMemoryPersistenceService chatMemoryPersistenceService = mock(ChatMemoryPersistenceService.class);
        when(chatMemoryPersistenceService.getConversationHistory("s1")).thenReturn(List.of(
                ChatMessageEntity.builder().role("user").content("hello").build(),
                ChatMessageEntity.builder().role("assistant").content("hi").build(),
                ChatMessageEntity.builder().role("user").content(null).build()
        ));

        assertEquals(List.of("user: hello", "assistant: hi"),
                RuntimeHistorySupport.loadLegacyHistory("s1", chatMemoryPersistenceService));
    }
}
