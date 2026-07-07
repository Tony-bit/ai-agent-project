package denny.ai.agent.domain.service.chatmemory;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ConversationMemoryOptions;
import denny.ai.agent.domain.model.entity.ConversationMemorySnapshot;
import denny.ai.agent.domain.model.entity.ConversationTurn;

import java.util.List;

public interface ConversationMemoryService {

    ConversationMemorySnapshot loadSnapshot(String sessionId, ConversationMemoryOptions options);

    void saveTurn(ConversationTurn turn);

    void refreshRuntimeCache(String sessionId, List<ChatMessageEntity> recentMessages);

    void clearRuntimeMemory(String sessionId);
}
