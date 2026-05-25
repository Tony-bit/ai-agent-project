package denny.ai.agent.domain.service.episodicmemory;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IEpisodicMemoryService 接口单元测试
 */
class IEpisodicMemoryServiceTest {

    @Test
    void testDefaultLimit_IsFive() {
        assertEquals(5, IEpisodicMemoryService.DEFAULT_LIMIT);
    }

    @Test
    void testInterface_IsPublic() {
        assertTrue(IEpisodicMemoryService.class.isInterface());
    }

    @Test
    void testSearchEpisodicMemories_MethodSignature() throws NoSuchMethodException {
        var method = IEpisodicMemoryService.class.getMethod("searchEpisodicMemories", String.class, String.class, int.class);
        assertNotNull(method);
        assertEquals(String.class, method.getReturnType());
    }

    @Test
    void testSearchEpisodicMemories_DefaultMethod() throws NoSuchMethodException {
        var method = IEpisodicMemoryService.class.getMethod("searchEpisodicMemories", String.class, String.class);
        assertNotNull(method);
        assertEquals(String.class, method.getReturnType());
    }

    @Test
    void testMethodParameters_CountIsCorrect() throws NoSuchMethodException {
        var method = IEpisodicMemoryService.class.getMethod("searchEpisodicMemories", String.class, String.class, int.class);
        assertNotNull(method);
        assertEquals(3, method.getParameterCount());
    }
}
