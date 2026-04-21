package denny.ai.agent.infrastructure.service;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SessionEndDetectionServiceImpl 单元测试
 * <p>
 * 测试覆盖：
 * 1. 正则层 - matchEndKeyword 方法
 * 2. 滑动窗口层 - checkBySlidingWindow 方法
 * 3. LLM 层 - parseLlmResponse / checkByLlm 方法
 * 4. isSessionEnded - 三层集成测试（通过 spy 控制 LLM 返回值）
 * <p>
 * 不覆盖：需要真实 API 调用的 LLM 集成测试
 *
 * @author denny
 */
@RunWith(MockitoJUnitRunner.class)
public class SessionEndDetectionServiceImplTest {

    // 真实对象，LLM 相关方法通过 spy 控制
    @Spy
    @InjectMocks
    private SessionEndDetectionServiceImpl sessionEndDetectionService;

    @Mock
    private SessionActivityTracker sessionActivityTracker;

    @Mock
    private ChatClient chatClient;

    // ========== 第一层：正则层测试 ==========

    /**
     * 正则层：标准结束语 - "好的，我明白了" 应命中结束
     */
    @Test
    public void testMatchEndKeyword_ok() {
        assertTrue("\"好的，我明白了\" 应判定为已结束",
                sessionEndDetectionService.matchEndKeyword("好的，我明白了"));
    }

    /**
     * 正则层：标准结束语 - "好的" 应命中结束
     */
    @Test
    public void testMatchEndKeyword_好的() {
        assertTrue("\"好的\" 应判定为已结束",
                sessionEndDetectionService.matchEndKeyword("好的"));
    }

    /**
     * 正则层：标准结束语 - "好的。" 应命中结束
     */
    @Test
    public void testMatchEndKeyword_好的WithPunct() {
        assertTrue("\"好的。\" 应判定为已结束",
                sessionEndDetectionService.matchEndKeyword("好的。"));
    }

    /**
     * 正则层：标准结束语 - "明白了" 应命中结束
     */
    @Test
    public void testMatchEndKeyword_明白了() {
        assertTrue("\"明白了\" 应判定为已结束",
                sessionEndDetectionService.matchEndKeyword("明白了"));
    }

    /**
     * 正则层：标准结束语 - "没问题了" 应命中结束
     */
    @Test
    public void testMatchEndKeyword_没问题了() {
        assertTrue("\"没问题了\" 应判定为已结束",
                sessionEndDetectionService.matchEndKeyword("没问题了"));
    }

    /**
     * 正则层：标准结束语 - "解决了" 应命中结束
     */
    @Test
    public void testMatchEndKeyword_解决了() {
        assertTrue("\"解决了\" 应判定为已结束",
                sessionEndDetectionService.matchEndKeyword("解决了"));
    }

    /**
     * 正则层：标准结束语 - "已解决" 应命中结束
     */
    @Test
    public void testMatchEndKeyword_已解决() {
        assertTrue("\"已解决\" 应判定为已结束",
                sessionEndDetectionService.matchEndKeyword("已解决"));
    }

    /**
     * 正则层：标准结束语 - "好的好的" 应命中结束
     */
    @Test
    public void testMatchEndKeyword_好的好的() {
        assertTrue("\"好的好的\" 应判定为已结束",
                sessionEndDetectionService.matchEndKeyword("好的好的"));
    }

    /**
     * 正则层：标准结束语 - "嗯嗯" 应命中结束
     */
    @Test
    public void testMatchEndKeyword_嗯嗯() {
        assertTrue("\"嗯嗯\" 应判定为已结束",
                sessionEndDetectionService.matchEndKeyword("嗯嗯"));
    }

    /**
     * 正则层：标准结束语 - "了解了" 应命中结束
     */
    @Test
    public void testMatchEndKeyword_了解了() {
        assertTrue("\"了解了\" 应判定为已结束",
                sessionEndDetectionService.matchEndKeyword("了解了"));
    }

    /**
     * 正则层：标准结束语 - "就这样吧" 应命中结束
     */
    @Test
    public void testMatchEndKeyword_就这样吧() {
        assertTrue("\"就这样吧\" 应判定为已结束",
                sessionEndDetectionService.matchEndKeyword("就这样吧"));
    }

    /**
     * 正则层：否定场景 - "好的，但是xxx其它问题怎么处理" 不应命中结束
     */
    @Test
    public void testMatchEndKeyword_goodButOtherIssue() {
        assertFalse("\"好的，但是xxx其它问题怎么处理\" 应判定为未结束",
                sessionEndDetectionService.matchEndKeyword("好的，但是xxx其它问题怎么处理"));
    }

    /**
     * 正则层：否定场景 - "好的，不过还有问题" 不应命中结束
     */
    @Test
    public void testMatchEndKeyword_goodButStillIssue() {
        assertFalse("\"好的，不过还有问题\" 应判定为未结束",
                sessionEndDetectionService.matchEndKeyword("好的，不过还有问题"));
    }

    /**
     * 正则层：否定场景 - "好的，另外一个问题" 不应命中结束
     */
    @Test
    public void testMatchEndKeyword_goodAnotherIssue() {
        assertFalse("\"好的，另外一个问题\" 应判定为未结束",
                sessionEndDetectionService.matchEndKeyword("好的，另外一个问题"));
    }

    /**
     * 正则层：否定场景 - "但是怎么处理" 不应命中结束
     */
    @Test
    public void testMatchEndKeyword_但是怎么处理() {
        assertFalse("\"但是怎么处理\" 应判定为未结束",
                sessionEndDetectionService.matchEndKeyword("但是怎么处理"));
    }

    /**
     * 正则层：否定场景 - "然而这不对" 不应命中结束
     */
    @Test
    public void testMatchEndKeyword_然而() {
        assertFalse("\"然而这不对\" 应判定为未结束",
                sessionEndDetectionService.matchEndKeyword("然而这不对"));
    }

    /**
     * 正则层：否定场景 - "可是怎么办" 不应命中结束
     */
    @Test
    public void testMatchEndKeyword_可是怎么办() {
        assertFalse("\"可是怎么办\" 应判定为未结束",
                sessionEndDetectionService.matchEndKeyword("可是怎么办"));
    }

    /**
     * 正则层：否定场景 - "还是有问题" 不应命中结束
     */
    @Test
    public void testMatchEndKeyword_还是有问题() {
        assertFalse("\"还是有问题\" 应判定为未结束",
                sessionEndDetectionService.matchEndKeyword("还是有问题"));
    }

    /**
     * 正则层：否定场景 - "继续问下去" 不应命中结束
     */
    @Test
    public void testMatchEndKeyword_继续问下去() {
        assertFalse("\"继续问下去\" 应判定为未结束",
                sessionEndDetectionService.matchEndKeyword("继续问下去"));
    }

    /**
     * 正则层：否定场景 - "还有补充" 不应命中结束
     */
    @Test
    public void testMatchEndKeyword_还有补充() {
        assertFalse("\"还有补充\" 应判定为未结束",
                sessionEndDetectionService.matchEndKeyword("还有补充"));
    }

    /**
     * 正则层：边界 - null 输入应返回 false
     */
    @Test
    public void testMatchEndKeyword_null() {
        assertFalse("null 输入应返回 false",
                sessionEndDetectionService.matchEndKeyword(null));
    }

    /**
     * 正则层：边界 - 空字符串应返回 false
     */
    @Test
    public void testMatchEndKeyword_empty() {
        assertFalse("空字符串应返回 false",
                sessionEndDetectionService.matchEndKeyword(""));
    }

    /**
     * 正则层：边界 - 仅空格应返回 false
     */
    @Test
    public void testMatchEndKeyword_blank() {
        assertFalse("仅空格应返回 false",
                sessionEndDetectionService.matchEndKeyword("   "));
    }

    /**
     * 正则层：普通对话 - 正常提问不应命中结束
     */
    @Test
    public void testMatchEndKeyword_normalQuestion() {
        assertFalse("\"请问 Java 如何实现多线程\" 应判定为未结束",
                sessionEndDetectionService.matchEndKeyword("请问 Java 如何实现多线程"));
    }

    /**
     * 正则层：标准结束语 - "感谢" 应命中结束
     */
    @Test
    public void testMatchEndKeyword_感谢() {
        assertTrue("\"感谢\" 应判定为已结束",
                sessionEndDetectionService.matchEndKeyword("感谢"));
    }

    // ========== 第三层：滑动窗口层测试 ==========

    /**
     * 滑动窗口层：userId 为空，应跳过判断返回 false
     */
    @Test
    public void testCheckBySlidingWindow_nullUserId() {
        boolean result = sessionEndDetectionService.checkBySlidingWindow("session-123", null, "你好");
        assertFalse("userId 为空应返回 false", result);
    }

    /**
     * 滑动窗口层：userId 为空字符串，应跳过判断返回 false
     */
    @Test
    public void testCheckBySlidingWindow_blankUserId() {
        boolean result = sessionEndDetectionService.checkBySlidingWindow("session-123", "  ", "你好");
        assertFalse("userId 为空字符串应返回 false", result);
    }

    /**
     * 滑动窗口层：会话已超时（isExpired 返回 true），应判定为已结束
     */
    @Test
    public void testCheckBySlidingWindow_expired() {
        doNothing().when(sessionActivityTracker).recordActivity(anyString(), anyString(), any());
        when(sessionActivityTracker.isExpired(anyString(), anyString())).thenReturn(true);

        boolean result = sessionEndDetectionService.checkBySlidingWindow("session-123", "user-001", "你好");

        assertTrue("会话已超时应判定为已结束", result);
        verify(sessionActivityTracker).recordActivity(eq("user-001"), eq("session-123"), eq("你好"));
        verify(sessionActivityTracker).isExpired(eq("user-001"), eq("session-123"));
    }

    /**
     * 滑动窗口层：会话未超时（isExpired 返回 false），应判定为未结束
     */
    @Test
    public void testCheckBySlidingWindow_active() {
        doNothing().when(sessionActivityTracker).recordActivity(anyString(), anyString(), any());
        when(sessionActivityTracker.isExpired(anyString(), anyString())).thenReturn(false);

        boolean result = sessionEndDetectionService.checkBySlidingWindow("session-123", "user-001", "你好");

        assertFalse("会话未超时应判定为未结束", result);
        verify(sessionActivityTracker).recordActivity(eq("user-001"), eq("session-123"), eq("你好"));
        verify(sessionActivityTracker).isExpired(eq("user-001"), eq("session-123"));
    }

    /**
     * 滑动窗口层：应先记录活动，再判断是否超时
     */
    @Test
    public void testCheckBySlidingWindow_recordThenCheck() {
        doNothing().when(sessionActivityTracker).recordActivity(anyString(), anyString(), any());
        when(sessionActivityTracker.isExpired(anyString(), anyString())).thenReturn(false);

        sessionEndDetectionService.checkBySlidingWindow("session-456", "user-001", "请问如何优化 SQL");

        var inOrder = inOrder(sessionActivityTracker);
        inOrder.verify(sessionActivityTracker).recordActivity(eq("user-001"), eq("session-456"), eq("请问如何优化 SQL"));
        inOrder.verify(sessionActivityTracker).isExpired(eq("user-001"), eq("session-456"));
    }

    /**
     * 滑动窗口层：lastMessage 为空也应记录活动
     */
    @Test
    public void testCheckBySlidingWindow_nullLastMessage() {
        doNothing().when(sessionActivityTracker).recordActivity(anyString(), anyString(), any());
        when(sessionActivityTracker.isExpired(anyString(), anyString())).thenReturn(false);

        boolean result = sessionEndDetectionService.checkBySlidingWindow("session-123", "user-001", null);

        assertFalse("会话未超时应判定为未结束", result);
        verify(sessionActivityTracker).recordActivity(eq("user-001"), eq("session-123"), isNull());
    }

    /**
     * 滑动窗口层：recordActivity 抛异常时，超时仍应返回 true
     */
    @Test
    public void testCheckBySlidingWindow_recordActivityThrows() {
        doThrow(new RuntimeException("tracker error"))
                .when(sessionActivityTracker).recordActivity(anyString(), anyString(), anyString());
        when(sessionActivityTracker.isExpired(anyString(), anyString())).thenReturn(true);

        boolean result = sessionEndDetectionService.checkBySlidingWindow("session-123", "user-001", "你好");

        assertTrue("即使记录失败，超时仍应判定为已结束", result);
    }

    /**
     * 滑动窗口层：同一 sessionId 不同 userId 应独立追踪
     */
    @Test
    public void testCheckBySlidingWindow_sameSessionDifferentUser() {
        doNothing().when(sessionActivityTracker).recordActivity(anyString(), anyString(), any());
        when(sessionActivityTracker.isExpired(eq("user-001"), anyString())).thenReturn(false);
        when(sessionActivityTracker.isExpired(eq("user-002"), anyString())).thenReturn(true);

        boolean result1 = sessionEndDetectionService.checkBySlidingWindow("session-shared", "user-001", "用户1的消息");
        boolean result2 = sessionEndDetectionService.checkBySlidingWindow("session-shared", "user-002", "用户2的消息");

        assertFalse("user-001 会话未超时", result1);
        assertTrue("user-002 会话已超时", result2);
    }

    // ========== 第二层：LLM 层测试（直接测试响应解析） ==========

    /**
     * LLM 解析：返回包含 "ended":true 的 JSON，应判定为已结束
     */
    @Test
    public void testParseLlmResponse_endedTrue() {
        assertTrue("\"ended\":true 应判定为已结束",
                sessionEndDetectionService.parseLlmResponse("{\"ended\":true,\"reason\":\"用户表示已解决\"}"));
    }

    /**
     * LLM 解析：返回包含 "ended": true（有空格）的 JSON，也应判定为已结束
     */
    @Test
    public void testParseLlmResponse_endedTrueWithSpace() {
        assertTrue("\"ended\": true（有空格）应判定为已结束",
                sessionEndDetectionService.parseLlmResponse("{\"ended\": true, \"reason\": \"用户还有疑问\"}"));
    }

    /**
     * LLM 解析：返回包含 "ended":false 的 JSON，应判定为未结束
     */
    @Test
    public void testParseLlmResponse_endedFalse() {
        assertFalse("\"ended\":false 应判定为未结束",
                sessionEndDetectionService.parseLlmResponse("{\"ended\": false, \"reason\": \"用户还有疑问\"}"));
    }

    /**
     * LLM 解析：null 响应应返回 false
     */
    @Test
    public void testParseLlmResponse_null() {
        assertFalse("null 响应应返回 false", sessionEndDetectionService.parseLlmResponse(null));
    }

    /**
     * LLM 解析：空字符串应返回 false
     */
    @Test
    public void testParseLlmResponse_empty() {
        assertFalse("空字符串应返回 false", sessionEndDetectionService.parseLlmResponse(""));
    }

    /**
     * LLM 解析：不含 ended 关键字应返回 false
     */
    @Test
    public void testParseLlmResponse_noEndedKeyword() {
        assertFalse("不含 ended 关键字应返回 false",
                sessionEndDetectionService.parseLlmResponse("{\"result\": \"ok\"}"));
    }

    /**
     * LLM 层：lastMessage 为空，应直接返回 false，不调 LLM
     */
    @Test
    public void testCheckByLlm_emptyMessage() {
        assertFalse("null 应返回 false", sessionEndDetectionService.checkByLlm(null));
        assertFalse("空字符串应返回 false", sessionEndDetectionService.checkByLlm(""));
        assertFalse("纯空格应返回 false", sessionEndDetectionService.checkByLlm("   "));
        verifyNoInteractions(chatClient);
    }

    /**
     * LLM 层：LLM 调用异常，应返回 false（降级）
     */
    @Test
    public void testCheckByLlm_llmException() {
        lenient().when(chatClient.prompt()).thenThrow(new RuntimeException("网络超时"));

        boolean result = sessionEndDetectionService.checkByLlm("好的，明白了");

        assertFalse("LLM 调用异常应降级返回 false", result);
    }

    // ========== isSessionEnded 集成测试（通过 spy 控制 LLM 返回值） ==========

    /**
     * 集成测试：命中正则层，应直接返回 true，不走 LLM 和滑动窗口
     */
    @Test
    public void testIsSessionEnded_matchKeyword() {
        boolean result = sessionEndDetectionService.isSessionEnded("session-123", "user-001", "好的，明白了");
        assertTrue("命中结束关键词应返回 true", result);
        verifyNoInteractions(sessionActivityTracker);
        verifyNoInteractions(chatClient);
    }

    /**
     * 集成测试：未命中正则，LLM 判断为结束，应返回 true
     */
    @Test
    public void testIsSessionEnded_llmEnded() {
        // "问题解决了" 不在正则结束词列表开头，不命中正则
        assertFalse(sessionEndDetectionService.matchEndKeyword("问题解决了"));

        // spy 控制 checkByLlm 返回 true
        doReturn(true).when(sessionEndDetectionService).checkByLlm(anyString());

        boolean result = sessionEndDetectionService.isSessionEnded("session-123", "user-001", "问题解决了");

        assertTrue("LLM 判断为已结束应返回 true", result);
        verify(sessionEndDetectionService).checkByLlm("问题解决了");
        verifyNoInteractions(sessionActivityTracker);
    }

    /**
     * 集成测试：未命中正则，LLM 判断为未结束，滑动窗口超时，应返回 true
     */
    @Test
    public void testIsSessionEnded_llmNotEndButSlidingWindowExpired() {
        // "请再解释一下" 不命中任何正则
        assertFalse(sessionEndDetectionService.matchEndKeyword("请再解释一下"));

        doReturn(false).when(sessionEndDetectionService).checkByLlm(anyString());
        doNothing().when(sessionActivityTracker).recordActivity(anyString(), anyString(), any());
        when(sessionActivityTracker.isExpired(anyString(), anyString())).thenReturn(true);

        boolean result = sessionEndDetectionService.isSessionEnded("session-123", "user-001", "请再解释一下");

        assertTrue("滑动窗口超时应返回 true", result);
        verify(sessionEndDetectionService).checkByLlm("请再解释一下");
        verify(sessionActivityTracker).isExpired(eq("user-001"), eq("session-123"));
    }

    /**
     * 集成测试：未命中正则，LLM 异常降级，滑动窗口未超时，应返回 false
     */
    @Test
    public void testIsSessionEnded_llmExceptionSlidingWindowActive() {
        // "请再详细说明" 不命中任何正则
        assertFalse(sessionEndDetectionService.matchEndKeyword("请再详细说明"));

        doThrow(new RuntimeException("超时")).when(sessionEndDetectionService).checkByLlm(anyString());
        doNothing().when(sessionActivityTracker).recordActivity(anyString(), anyString(), any());
        when(sessionActivityTracker.isExpired(anyString(), anyString())).thenReturn(false);

        boolean result = sessionEndDetectionService.isSessionEnded("session-123", "user-001", "请再详细说明");

        assertFalse("LLM 异常且滑动窗口未超时应返回 false", result);
    }

    /**
     * 集成测试：三步都未结束，应返回 false
     */
    @Test
    public void testIsSessionEnded_allNotEnd() {
        doReturn(false).when(sessionEndDetectionService).checkByLlm(anyString());
        doNothing().when(sessionActivityTracker).recordActivity(anyString(), anyString(), any());
        when(sessionActivityTracker.isExpired(anyString(), anyString())).thenReturn(false);

        boolean result = sessionEndDetectionService.isSessionEnded("session-123", "user-001", "请问如何优化性能");

        assertFalse("三步都未结束应返回 false", result);
    }
}
