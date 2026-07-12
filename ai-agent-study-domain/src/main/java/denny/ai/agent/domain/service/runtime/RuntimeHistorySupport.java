package denny.ai.agent.domain.service.runtime;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.chatmemory.ChatMemoryPersistenceService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

@Slf4j
public final class RuntimeHistorySupport {

    private RuntimeHistorySupport() {
    }

    public static Optional<List<String>> preparedHistory(DefaultAutoAgentExecuteStrategyFactory.DynamicContext context) {
        Object value = context.getDataObjects().get(RuntimeContextKeys.RECENT_HISTORY_MESSAGES);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return Optional.of(list.stream().map(String.class::cast).toList());
        }
        log.warn("Invalid runtime history type, fallback to legacy loader: key={}, type={}",
                RuntimeContextKeys.RECENT_HISTORY_MESSAGES, value.getClass().getName());
        return Optional.empty();
    }

    public static List<String> loadLegacyHistory(String sessionId,
                                                 ChatMemoryPersistenceService chatMemoryPersistenceService) {
        try {
            return chatMemoryPersistenceService.getConversationHistory(sessionId).stream()
                    .filter(message -> message.getRole() != null && message.getContent() != null)
                    .map(RuntimeHistorySupport::format)
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to load conversation history: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private static String format(ChatMessageEntity message) {
        return message.getRole() + ": " + message.getContent();
    }
}
