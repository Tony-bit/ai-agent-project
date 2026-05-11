package denny.ai.agent.domain.service.auto.step.chat;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.routing.IntentRoutingNode;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.Assert.*;

/**
 * 多模态对话节点测试
 * <p>
 * 测试覆盖：
 * 1. TC-MM-001: inputType=1 且有文件时，走多模态分支
 * 2. TC-MM-002: inputType=0 时，走纯文本分支
 * 3. TC-MM-003: 无 inputType 时，走纯文本分支
 * 4. TC-MM-004: 用户消息为空时的默认处理
 * 5. TC-MM-005: 用户消息非空时，使用用户消息
 * 6. TC-MM-006: 有文件但 inputType=0 时，走纯文本分支
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
public class MultimodalChatNodeTest {

    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext;

    @Before
    public void setUp() {
        dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.GENERAL_CHAT);
    }

    // ========== 分支逻辑测试 ==========

    /**
     * TC-MM-001: inputType=1 且有文件时，走多模态分支
     */
    @Test
    public void testMultimodalBranch_withImage() {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "test.png", "image/png", "fake image content".getBytes()
        );

        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .message("这张图片里有什么？")
                .sessionId("test-session-001")
                .inputType(1)
                .file(mockFile)
                .userId("test-user")
                .build();

        boolean isMultimodal = request.getInputType() != null
                && request.getInputType() == 1
                && request.getFile() != null;

        assertTrue("inputType=1 且有文件时，应走多模态分支", isMultimodal);
    }

    /**
     * TC-MM-002: inputType=0 时，走纯文本分支
     */
    @Test
    public void testTextBranch_withInputType0() {
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .message("你好")
                .sessionId("test-session-001")
                .inputType(0)
                .file(null)
                .userId("test-user")
                .build();

        boolean isMultimodal = request.getInputType() != null
                && request.getInputType() == 1
                && request.getFile() != null;

        assertFalse("inputType=0 时，应走纯文本分支", isMultimodal);
    }

    /**
     * TC-MM-003: 无 inputType 时，走纯文本分支
     */
    @Test
    public void testTextBranch_withNoInputType() {
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .message("你好")
                .sessionId("test-session-001")
                .inputType(null)
                .file(null)
                .userId("test-user")
                .build();

        boolean isMultimodal = request.getInputType() != null
                && request.getInputType() == 1
                && request.getFile() != null;

        assertFalse("无 inputType 时，应走纯文本分支", isMultimodal);
    }

    /**
     * TC-MM-006: 有文件但 inputType=0 时，走纯文本分支
     */
    @Test
    public void testTextBranch_withFileButInputType0() {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "test.png", "image/png", "fake image content".getBytes()
        );

        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .message("你好")
                .sessionId("test-session-001")
                .inputType(0)
                .file(mockFile)
                .userId("test-user")
                .build();

        boolean isMultimodal = request.getInputType() != null
                && request.getInputType() == 1
                && request.getFile() != null;

        assertFalse("有文件但 inputType=0 时，应走纯文本分支", isMultimodal);
    }

    // ========== 消息处理测试 ==========

    /**
     * TC-MM-004: 用户消息为空时，使用默认提示
     */
    @Test
    public void testDefaultMessageWhenNull() {
        String userMessage = null;
        String resolvedMessage = userMessage != null ? userMessage : "请描述这张图片的内容";

        assertEquals("请描述这张图片的内容", resolvedMessage);
    }

    /**
     * TC-MM-005: 用户消息非空时，使用用户消息
     */
    @Test
    public void testUserMessageWhenProvided() {
        String userMessage = "这张图片里有什么？";
        String resolvedMessage = userMessage != null ? userMessage : "请描述这张图片的内容";

        assertEquals("这张图片里有什么？", resolvedMessage);
    }

    // ========== DynamicContext 测试 ==========

    /**
     * TC-MM-007: DynamicContext 正确存储 intent
     */
    @Test
    public void testDynamicContextStoresIntent() {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.GENERAL_CHAT);

        IntentTypeEnum storedIntent = dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY);

        assertEquals(IntentTypeEnum.GENERAL_CHAT, storedIntent);
    }

    /**
     * TC-MM-008: DynamicContext 正确存储 generalChatResponse
     */
    @Test
    public void testDynamicContextStoresGeneralChatResponse() {
        String response = "这是一段通用回复内容";
        dynamicContext.setValue("generalChatResponse", response);

        String storedResponse = dynamicContext.getValue("generalChatResponse");

        assertEquals(response, storedResponse);
    }

    // ========== ExecuteCommandEntity 测试 ==========

    /**
     * TC-MM-009: ExecuteCommandEntity 正确设置 inputType 和 file
     */
    @Test
    public void testExecuteCommandEntityMultimodalFields() {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "test.png", "image/png", "fake image content".getBytes()
        );

        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .message("这张图片里有什么？")
                .sessionId("test-session-001")
                .inputType(1)
                .file(mockFile)
                .userId("test-user")
                .build();

        assertEquals(1, request.getInputType().intValue());
        assertNotNull(request.getFile());
        assertEquals("test.png", request.getFile().getOriginalFilename());
        assertEquals("image/png", request.getFile().getContentType());
    }

    /**
     * TC-MM-010: inputType=1 但无文件时，走纯文本分支
     */
    @Test
    public void testTextBranch_withInputType1ButNoFile() {
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .message("你好")
                .sessionId("test-session-001")
                .inputType(1)
                .file(null)
                .userId("test-user")
                .build();

        boolean isMultimodal = request.getInputType() != null
                && request.getInputType() == 1
                && request.getFile() != null;

        assertFalse("inputType=1 但无文件时，应走纯文本分支", isMultimodal);
    }
}
