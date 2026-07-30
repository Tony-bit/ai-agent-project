package denny.ai.agent.infrastructure.adapter.repository;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ConversationRuntimeWindow;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ChatMemoryRepositoryAsyncTest {

    @Test
    void redisWriteMethodsUseConfiguredAsyncExecutor() throws NoSuchMethodException {
        assertAsyncExecutor(ChatMemoryRepository.class.getMethod(
                "cacheMessagesToRedis", String.class, List.class, int.class));
        assertAsyncExecutor(ChatMemoryRepository.class.getMethod(
                "cacheRuntimeWindowToRedis", String.class, ConversationRuntimeWindow.class, int.class));
    }

    private void assertAsyncExecutor(Method method) {
        Async async = method.getAnnotation(Async.class);
        assertNotNull(async, () -> method.getName() + " must be asynchronous");
        assertEquals("threadPoolExecutor", async.value());
    }
}
