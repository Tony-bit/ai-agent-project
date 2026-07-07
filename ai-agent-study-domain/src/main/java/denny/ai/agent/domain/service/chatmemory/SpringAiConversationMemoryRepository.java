package denny.ai.agent.domain.service.chatmemory;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ConversationMemoryOptions;
import denny.ai.agent.domain.model.entity.ConversationMemorySnapshot;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SpringAiConversationMemoryRepository implements ChatMemoryRepository {

    @Resource
    private ConversationMemoryService conversationMemoryService;

    @Override
    public List<String> findConversationIds() {
        return Collections.emptyList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        ConversationMemorySnapshot snapshot = conversationMemoryService.loadSnapshot(
                conversationId, ConversationMemoryOptions.defaults());
        return snapshot.getRecentMessages().stream()
                .map(this::toSpringMessage)
                .toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        AtomicInteger index = new AtomicInteger(1);
        List<ChatMessageEntity> entities = messages.stream()
                .map(message -> toEntity(conversationId, index.getAndIncrement(), message))
                .toList();
        conversationMemoryService.refreshRuntimeCache(conversationId, entities);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        conversationMemoryService.clearRuntimeMemory(conversationId);
    }

    private Message toSpringMessage(ChatMessageEntity entity) {
        String role = entity.getRole();
        String content = entity.getContent() == null ? "" : entity.getContent();
        if (ChatMessageEntity.ROLE_ASSISTANT.equals(role)) {
            return new AssistantMessage(content);
        }
        if ("system".equals(role)) {
            return new SystemMessage(content);
        }
        return new UserMessage(content);
    }

    private ChatMessageEntity toEntity(String conversationId, int index, Message message) {
        return ChatMessageEntity.builder()
                .sessionId(conversationId)
                .messageIndex(index)
                .role(toRole(message.getMessageType()))
                .content(message.getText())
                .createTime(LocalDateTime.now())
                .build();
    }

    private String toRole(MessageType messageType) {
        if (MessageType.ASSISTANT.equals(messageType)) {
            return ChatMessageEntity.ROLE_ASSISTANT;
        }
        if (MessageType.SYSTEM.equals(messageType)) {
            return "system";
        }
        return ChatMessageEntity.ROLE_USER;
    }
}
