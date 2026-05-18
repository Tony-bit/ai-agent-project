package denny.ai.agent.domain.service.armory;

import org.junit.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import static org.junit.Assert.*;

/**
 * CompressionRequiredException 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-Except-001: 正常构造异常
 * 2. TC-Except-002: 获取原始 Prompt
 * 3. TC-Except-003: 获取返回节点
 * 4. TC-Except-004: 异常消息验证
 * 5. TC-Except-005: null Prompt 构造
 * </p>
 */
public class CompressionRequiredExceptionTest {

    private Prompt createPrompt(String text) {
        return Prompt.builder()
                .messages(new UserMessage(text))
                .build();
    }

    /**
     * TC-Except-001: 正常构造异常
     */
    @Test
    public void testNormalConstruction() {
        Prompt prompt = createPrompt("test prompt");
        String returnNode = "aiClientModelNode";

        CompressionRequiredException exception = new CompressionRequiredException(prompt, returnNode);

        assertNotNull(exception);
        assertEquals(prompt, exception.getOriginalPrompt());
        assertEquals(returnNode, exception.getReturnNode());
        assertTrue(exception.getMessage().contains("Compression required"));
    }

    /**
     * TC-Except-002: 获取原始 Prompt
     */
    @Test
    public void testGetOriginalPrompt() {
        Prompt prompt = createPrompt("original prompt content");
        CompressionRequiredException exception = new CompressionRequiredException(prompt, "testNode");

        Prompt retrievedPrompt = exception.getOriginalPrompt();
        assertNotNull(retrievedPrompt);
        assertEquals(prompt, retrievedPrompt);
    }

    /**
     * TC-Except-003: 获取返回节点
     */
    @Test
    public void testGetReturnNode() {
        Prompt prompt = createPrompt("test");
        String expectedNode = "compressionNode";

        CompressionRequiredException exception = new CompressionRequiredException(prompt, expectedNode);

        assertEquals(expectedNode, exception.getReturnNode());
    }

    /**
     * TC-Except-004: 异常消息验证
     */
    @Test
    public void testExceptionMessage() {
        CompressionRequiredException exception = new CompressionRequiredException(null, "testNode");

        String message = exception.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("Compression required"));
    }

    /**
     * TC-Except-005: null Prompt 构造（被动压缩场景）
     */
    @Test
    public void testNullPromptConstruction() {
        CompressionRequiredException exception = new CompressionRequiredException(null, "aiClientModelNode");

        assertNull(exception.getOriginalPrompt());
        assertEquals("aiClientModelNode", exception.getReturnNode());
    }
}
