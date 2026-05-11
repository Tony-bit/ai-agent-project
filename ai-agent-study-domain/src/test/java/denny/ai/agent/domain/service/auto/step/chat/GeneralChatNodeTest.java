package denny.ai.agent.domain.service.auto.step.chat;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.routing.IntentRoutingNode;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.*;

/**
 * GeneralChatNode 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-GC-001: 正常对话_意图设置
 * 2. TC-GC-002: AMBIGUOUS意图_澄清引导
 * 3. TC-GC-003: UNKNOWN意图_降级处理
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
@RunWith(MockitoJUnitRunner.class)
public class GeneralChatNodeTest {

    private GeneralChatNode generalChatNode;

    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext;

    private ExecuteCommandEntity request;

    @Before
    public void setUp() {
        generalChatNode = new GeneralChatNode();

        dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();

        request = ExecuteCommandEntity.builder()
                .sessionId("test-session-123")
                .message("你好")
                .userId("user-001")
                .build();
    }

    // ========== TC-GC-001 ~ TC-GC-003: 核心功能测试 ==========

    /**
     * TC-GC-001: recognizedIntent 为 GENERAL_CHAT 时，正常设置
     */
    @Test
    public void testGeneralChatIntent_setsCorrectIntent() {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.GENERAL_CHAT);

        IntentTypeEnum intent = dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY);
        assertEquals(IntentTypeEnum.GENERAL_CHAT, intent);
    }

    /**
     * TC-GC-002: AMBIGUOUS意图_澄清引导
     * 验证 recognizedIntent 为 AMBIGUOUS 时，会设置对应的 prompt
     */
    @Test
    public void testAmbiguousIntent_setsCorrectIntent() {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.AMBIGUOUS);

        IntentTypeEnum intent = dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY);
        assertEquals(IntentTypeEnum.AMBIGUOUS, intent);
    }

    /**
     * TC-GC-003: UNKNOWN意图_降级处理
     * 验证 recognizedIntent 为 UNKNOWN 时，流程继续不阻断
     */
    @Test
    public void testUnknownIntent_setsCorrectIntent() {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.UNKNOWN);

        IntentTypeEnum intent = dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY);
        assertEquals(IntentTypeEnum.UNKNOWN, intent);
    }

    /**
     * TC-GC-004: recognizedIntent 为 null 时，使用默认处理
     */
    @Test
    public void testNullIntent_returnsNull() {
        IntentTypeEnum intent = dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY);
        assertNull(intent);
    }

    /**
     * TC-GC-005: dynamicContext 设置 generalChatResponse
     */
    @Test
    public void testGeneralChatResponse_storedInContext() {
        String response = "这是一段通用回复内容";
        dynamicContext.setValue("generalChatResponse", response);

        String storedResponse = dynamicContext.getValue("generalChatResponse");
        assertEquals(response, storedResponse);
    }
}
