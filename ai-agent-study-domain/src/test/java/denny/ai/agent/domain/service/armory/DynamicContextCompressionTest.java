package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.AiClientModelVO;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import org.junit.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * DynamicContext 压缩字段单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-DC-001: compressionRequired 默认值
 * 2. TC-DC-002: 设置 compressionRequired
 * 3. TC-DC-003: 设置 returnNode
 * 4. TC-DC-004: 设置 originalPrompt
 * 5. TC-DC-005: 设置 compressedPrompt
 * 6. TC-DC-006: 压缩完成后重置
 * 7. TC-DC-007: 压缩后 Prompt 覆盖
 * 8. TC-DC-008: aiAgentClientFlowConfigVOMap
 * </p>
 */
public class DynamicContextCompressionTest {

    private Prompt createPrompt(String text) {
        return Prompt.builder()
                .messages(new UserMessage(text))
                .build();
    }

    /**
     * TC-DC-001: compressionRequired 默认值
     */
    @Test
    public void testCompressionRequiredDefault() {
        DynamicContext context = new DynamicContext();

        assertFalse(context.isCompressionRequired());
    }

    /**
     * TC-DC-002: 设置 compressionRequired
     */
    @Test
    public void testSetCompressionRequired() {
        DynamicContext context = new DynamicContext();

        context.setCompressionRequired(true);
        assertTrue(context.isCompressionRequired());

        context.setCompressionRequired(false);
        assertFalse(context.isCompressionRequired());
    }

    /**
     * TC-DC-003: 设置 returnNode
     */
    @Test
    public void testSetReturnNode() {
        DynamicContext context = new DynamicContext();

        context.setReturnNode("aiClientModelNode");
        assertEquals("aiClientModelNode", context.getReturnNode());
    }

    /**
     * TC-DC-004: 设置 originalPrompt
     */
    @Test
    public void testSetOriginalPrompt() {
        DynamicContext context = new DynamicContext();
        Prompt originalPrompt = createPrompt("original content");

        context.setOriginalPrompt(originalPrompt);

        assertNotNull(context.getOriginalPrompt());
        assertEquals(originalPrompt, context.getOriginalPrompt());
    }

    /**
     * TC-DC-005: 设置 compressedPrompt
     */
    @Test
    public void testSetCompressedPrompt() {
        DynamicContext context = new DynamicContext();
        Prompt compressedPrompt = createPrompt("compressed content");

        context.setCompressedPrompt(compressedPrompt);

        assertNotNull(context.getCompressedPrompt());
        assertEquals(compressedPrompt, context.getCompressedPrompt());
    }

    /**
     * TC-DC-006: 压缩完成后重置
     */
    @Test
    public void testResetAfterCompression() {
        DynamicContext context = new DynamicContext();

        context.setCompressionRequired(true);
        context.setReturnNode("aiClientModelNode");
        context.setOriginalPrompt(createPrompt("original"));
        context.setCompressedPrompt(createPrompt("compressed"));

        // 模拟压缩完成
        context.setCompressionRequired(false);

        assertFalse(context.isCompressionRequired());
        assertEquals("aiClientModelNode", context.getReturnNode());
        assertNotNull(context.getOriginalPrompt());
        assertNotNull(context.getCompressedPrompt());
    }

    /**
     * TC-DC-007: 压缩后 Prompt 覆盖
     */
    @Test
    public void testCompressedPromptOverrides() {
        DynamicContext context = new DynamicContext();
        Prompt originalPrompt = createPrompt("original content");
        Prompt compressedPrompt = createPrompt("compressed content");

        context.setOriginalPrompt(originalPrompt);
        context.setCompressedPrompt(compressedPrompt);

        // 验证 compressedPrompt 存在
        assertNotNull(context.getCompressedPrompt());
        // 验证 originalPrompt 也存在
        assertNotNull(context.getOriginalPrompt());
    }

    /**
     * TC-DC-008: aiAgentClientFlowConfigVOMap
     */
    @Test
    public void testAiAgentClientFlowConfigVOMap() {
        DynamicContext context = new DynamicContext();
        Map<String, AiAgentClientFlowConfigVO> flowConfigMap = new HashMap<>();

        AiAgentClientFlowConfigVO config = AiAgentClientFlowConfigVO.builder()
                .clientId("3202")
                .clientName("压缩助手")
                .clientType("COMPRESSION_ASSISTANT")
                .maxSummaryTokens(2000)
                .build();
        flowConfigMap.put("COMPRESSION_ASSISTANT", config);

        context.setAiAgentClientFlowConfigVOMap(flowConfigMap);

        assertNotNull(context.getAiAgentClientFlowConfigVOMap());
        assertEquals(1, context.getAiAgentClientFlowConfigVOMap().size());
        assertEquals("3202", context.getAiAgentClientFlowConfigVOMap().get("COMPRESSION_ASSISTANT").getClientId());
    }

    /**
     * TC-DC-009: sessionId 字段
     */
    @Test
    public void testSessionId() {
        DynamicContext context = new DynamicContext();

        context.setSessionId("session-123");

        assertEquals("session-123", context.getSessionId());
    }
}
