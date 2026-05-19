package denny.ai.agent.domain.service.armory.factory.element;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.Assert.*;

/**
 * AiErrorCodes 常量类单元测试
 * <p>
 * 测试覆盖：
 * - TC-AEC-01: 常量值正确性验证
 * - TC-AEC-02: 不可实例化验证
 * </p>
 */
public class AiErrorCodesTest {

    // ========== TC-AEC-01: 常量值正确性验证 ==========

    @Test
    public void testContextOverflowConstant() {
        assertEquals("1261", AiErrorCodes.CONTEXT_OVERFLOW);
    }

    @Test
    public void testUnknownConstant() {
        assertEquals("unknown", AiErrorCodes.UNKNOWN);
    }

    @Test
    public void testHttpStatusCodeConstants() {
        assertEquals("400", AiErrorCodes.HTTP_400);
        assertEquals("401", AiErrorCodes.HTTP_401);
        assertEquals("403", AiErrorCodes.HTTP_403);
        assertEquals("408", AiErrorCodes.HTTP_408);
        assertEquals("409", AiErrorCodes.HTTP_409);
        assertEquals("429", AiErrorCodes.HTTP_429);
        assertEquals("500", AiErrorCodes.HTTP_500);
        assertEquals("502", AiErrorCodes.HTTP_502);
        assertEquals("503", AiErrorCodes.HTTP_503);
        assertEquals("504", AiErrorCodes.HTTP_504);
        assertEquals("529", AiErrorCodes.HTTP_529);
    }

    @Test
    public void testRateLimitConstant() {
        assertEquals("rate_limit", AiErrorCodes.RATE_LIMIT);
    }

    @Test
    public void testTimeoutConstant() {
        assertEquals("timeout", AiErrorCodes.TIMEOUT);
    }

    @Test
    public void testHttpStatusCodesSet() {
        assertTrue(AiErrorCodes.HTTP_STATUS_CODES.contains("400"));
        assertTrue(AiErrorCodes.HTTP_STATUS_CODES.contains("401"));
        assertTrue(AiErrorCodes.HTTP_STATUS_CODES.contains("403"));
        assertTrue(AiErrorCodes.HTTP_STATUS_CODES.contains("408"));
        assertTrue(AiErrorCodes.HTTP_STATUS_CODES.contains("409"));
        assertTrue(AiErrorCodes.HTTP_STATUS_CODES.contains("429"));
        assertTrue(AiErrorCodes.HTTP_STATUS_CODES.contains("500"));
        assertTrue(AiErrorCodes.HTTP_STATUS_CODES.contains("502"));
        assertTrue(AiErrorCodes.HTTP_STATUS_CODES.contains("503"));
        assertTrue(AiErrorCodes.HTTP_STATUS_CODES.contains("504"));
        assertTrue(AiErrorCodes.HTTP_STATUS_CODES.contains("529"));
        assertEquals(11, AiErrorCodes.HTTP_STATUS_CODES.size());
    }

    @Test
    public void testNodeAiClientModelConstant() {
        assertEquals("aiClientModelNode", AiErrorCodes.NODE_AI_CLIENT_MODEL);
    }

    // ========== TC-AEC-02: 不可实例化验证 ==========

    @Test
    public void testPrivateConstructor() throws Exception {
        Constructor<AiErrorCodes> constructor = AiErrorCodes.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        assertThrows(Exception.class, () -> constructor.newInstance());
    }
}
