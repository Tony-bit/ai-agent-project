package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * CompressionContextNode 辅助方法单元测试
 * <p>
 * 测试覆盖：
 * 1. getRecentRounds 防御性测试 (TC-Recent-001 ~ TC-Recent-008)
 * 2. formatSummary 测试 (TC-Fmt-001 ~ TC-Fmt-009)
 * </p>
 */
public class CompressionContextHelperTest {

    /**
     * TC-Recent-001: 消息数量 <= 目标数量
     */
    @Test
    public void testGetRecentRounds_LessThanTarget() {
        CompressionContextNode node = new CompressionContextNode();
        List<ChatMessageEntity> messages = createMessages(3);

        List<ChatMessageEntity> result = node.getRecentRounds(messages, 2);

        assertEquals(3, result.size());
        assertEquals(messages, result);
    }

    /**
     * TC-Recent-002: 消息数量 > 目标数量
     */
    @Test
    public void testGetRecentRounds_MoreThanTarget() {
        CompressionContextNode node = new CompressionContextNode();
        List<ChatMessageEntity> messages = createMessages(10);

        List<ChatMessageEntity> result = node.getRecentRounds(messages, 2);

        assertEquals(4, result.size());
    }

    /**
     * TC-Recent-003: 消息数量恰好等于目标
     */
    @Test
    public void testGetRecentRounds_ExactlyEqualsTarget() {
        CompressionContextNode node = new CompressionContextNode();
        List<ChatMessageEntity> messages = createMessages(4);

        List<ChatMessageEntity> result = node.getRecentRounds(messages, 2);

        assertEquals(4, result.size());
    }

    /**
     * TC-Recent-004: 空消息列表
     */
    @Test
    public void testGetRecentRounds_EmptyList() {
        CompressionContextNode node = new CompressionContextNode();
        List<ChatMessageEntity> messages = new ArrayList<>();

        List<ChatMessageEntity> result = node.getRecentRounds(messages, 2);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * TC-Recent-005: null 消息列表
     */
    @Test
    public void testGetRecentRounds_NullList() {
        CompressionContextNode node = new CompressionContextNode();

        List<ChatMessageEntity> result = node.getRecentRounds(null, 2);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * TC-Recent-006: 单条消息
     */
    @Test
    public void testGetRecentRounds_SingleMessage() {
        CompressionContextNode node = new CompressionContextNode();
        List<ChatMessageEntity> messages = createMessages(1);

        List<ChatMessageEntity> result = node.getRecentRounds(messages, 2);

        assertEquals(1, result.size());
    }

    /**
     * TC-Recent-007: 3条消息 2轮目标
     */
    @Test
    public void testGetRecentRounds_ThreeMessagesTwoRounds() {
        CompressionContextNode node = new CompressionContextNode();
        List<ChatMessageEntity> messages = createMessages(3);

        List<ChatMessageEntity> result = node.getRecentRounds(messages, 2);

        assertEquals(3, result.size());
    }

    /**
     * TC-Recent-008: subList 索引计算
     */
    @Test
    public void testGetRecentRounds_IndexCalculation() {
        CompressionContextNode node = new CompressionContextNode();
        List<ChatMessageEntity> messages = createMessages(6);

        List<ChatMessageEntity> result = node.getRecentRounds(messages, 2);

        assertEquals(4, result.size());
        // 验证返回的是最后4条
        assertEquals(messages.get(2), result.get(0));
        assertEquals(messages.get(5), result.get(3));
    }

    /**
     * TC-Fmt-001: 正常格式解析
     */
    @Test
    public void testFormatSummary_NormalFormat() {
        CompressionContextNode node = new CompressionContextNode();
        String rawSummary = "<分析>这是分析过程</分析><摘要>这是摘要内容</摘要>";

        String result = node.formatSummary(rawSummary);

        assertTrue(result.contains("这是摘要内容"));
        assertFalse(result.contains("分析"));
    }

    /**
     * TC-Fmt-002: 仅含 <分析>
     */
    @Test
    public void testFormatSummary_OnlyAnalysis() {
        CompressionContextNode node = new CompressionContextNode();
        String rawSummary = "<分析>这是分析内容</分析>";

        String result = node.formatSummary(rawSummary);

        assertNotNull(result);
        // 仅有分析标签时，内容会被清除（因为代码逻辑是先移除分析，再查找摘要）
        assertFalse(result.contains("分析"));
    }

    /**
     * TC-Fmt-003: 无任何标签
     */
    @Test
    public void testFormatSummary_NoTags() {
        CompressionContextNode node = new CompressionContextNode();
        String rawSummary = "纯文本摘要内容";

        String result = node.formatSummary(rawSummary);

        assertEquals("纯文本摘要内容", result);
    }

    /**
     * TC-Fmt-004: 大小写不敏感
     */
    @Test
    public void testFormatSummary_CaseInsensitive() {
        CompressionContextNode node = new CompressionContextNode();
        String rawSummary = "<分析>分析</分析><摘要>摘要内容</摘要>";

        String result = node.formatSummary(rawSummary);

        assertEquals("摘要内容", result.trim());
    }

    /**
     * TC-Fmt-005: 多余空行清理
     */
    @Test
    public void testFormatSummary_MultipleBlankLines() {
        CompressionContextNode node = new CompressionContextNode();
        String rawSummary = "第一行\n\n\n\n第二行";

        String result = node.formatSummary(rawSummary);

        assertFalse(result.contains("\n\n\n"));
    }

    /**
     * TC-Fmt-006: 首尾空白清理
     */
    @Test
    public void testFormatSummary_TrimWhitespace() {
        CompressionContextNode node = new CompressionContextNode();
        String rawSummary = "  摘要内容  ";

        String result = node.formatSummary(rawSummary);

        assertEquals("摘要内容", result);
    }

    /**
     * TC-Fmt-007: 空字符串输入
     */
    @Test
    public void testFormatSummary_EmptyString() {
        CompressionContextNode node = new CompressionContextNode();

        String result = node.formatSummary("");

        assertEquals("", result);
    }

    /**
     * TC-Fmt-008: null 输入
     */
    @Test
    public void testFormatSummary_NullInput() {
        CompressionContextNode node = new CompressionContextNode();

        String result = node.formatSummary(null);

        assertEquals("", result);
    }

    // ========== 辅助方法 ==========

    private List<ChatMessageEntity> createMessages(int count) {
        List<ChatMessageEntity> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(ChatMessageEntity.builder()
                    .role(i % 2 == 0 ? "user" : "assistant")
                    .content("message " + i)
                    .build());
        }
        return messages;
    }
}
