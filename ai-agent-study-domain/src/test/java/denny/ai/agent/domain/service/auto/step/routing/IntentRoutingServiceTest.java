package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.routing.IntentRoutingService.IntentRoutingResult;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * IntentRoutingService 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-Service-001 ~ TC-Service-007: LLM 响应解析测试（7种意图）
 * 2. TC-Service-008: LLM返回非法JSON降级
 * 3. TC-Service-009: LLM返回空JSON降级
 * 4. TC-Service-010: LLM返回缺省字段降级
 * 5. TC-Service-011: 历史消息注入验证（Prompt 构建）
 * 6. TC-Service-012: LLM调用异常降级处理
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
@RunWith(MockitoJUnitRunner.class)
public class IntentRoutingServiceTest {

    @Mock
    private ChatModel chatModel;

    private IntentRoutingService intentRoutingService;

    @Before
    public void setUp() throws Exception {
        intentRoutingService = new IntentRoutingService();
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        Field chatClientField = IntentRoutingService.class.getDeclaredField("chatClient");
        chatClientField.setAccessible(true);
        chatClientField.set(intentRoutingService, chatClient);
    }

    private void mockLLMResponse(String responseContent) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(responseContent))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(chatResponse);
    }

    // ========== TC-Service-001 ~ TC-Service-007: LLM 响应解析测试 ==========

    /**
     * TC-Service-001: 股票分析_高置信度
     */
    @Test
    public void testStockAnalysis_HighConfidence() {
        String response = "{\"intent\":\"STOCK_ANALYSIS\",\"confidence\":\"HIGH\",\"reasoning\":\"用户明确询问股票走势\"}";
        mockLLMResponse(response);

        IntentRoutingResult result = intentRoutingService.route("分析平安银行", "prompt");

        assertEquals(IntentTypeEnum.STOCK_ANALYSIS, result.getIntent());
        assertEquals(ConfidenceEnum.HIGH, result.getConfidence());
        assertEquals("用户明确询问股票走势", result.getReasoning());
    }

    /**
     * TC-Service-002: PE推理_中置信度
     */
    @Test
    public void testPEReasoning_MediumConfidence() {
        String response = "{\"intent\":\"PE_REASONING\",\"confidence\":\"MEDIUM\",\"reasoning\":\"逻辑推理任务\"}";
        mockLLMResponse(response);

        IntentRoutingResult result = intentRoutingService.route("分析房价下跌影响", "prompt");

        assertEquals(IntentTypeEnum.PE_REASONING, result.getIntent());
        assertEquals(ConfidenceEnum.MEDIUM, result.getConfidence());
        assertEquals("逻辑推理任务", result.getReasoning());
    }

    /**
     * TC-Service-003: PE计算_高置信度
     */
    @Test
    public void testPECalculation_HighConfidence() {
        String response = "{\"intent\":\"PE_CALCULATION\",\"confidence\":\"HIGH\",\"reasoning\":\"数学计算\"}";
        mockLLMResponse(response);

        IntentRoutingResult result = intentRoutingService.route("计算1.05的12次方", "prompt");

        assertEquals(IntentTypeEnum.PE_CALCULATION, result.getIntent());
        assertEquals(ConfidenceEnum.HIGH, result.getConfidence());
        assertEquals("数学计算", result.getReasoning());
    }

    /**
     * TC-Service-004: PE检索_中置信度
     */
    @Test
    public void testPERetrieval_MediumConfidence() {
        String response = "{\"intent\":\"PE_RETRIEVAL\",\"confidence\":\"MEDIUM\",\"reasoning\":\"知识查询\"}";
        mockLLMResponse(response);

        IntentRoutingResult result = intentRoutingService.route("什么是RAG技术", "prompt");

        assertEquals(IntentTypeEnum.PE_RETRIEVAL, result.getIntent());
        assertEquals(ConfidenceEnum.MEDIUM, result.getConfidence());
        assertEquals("知识查询", result.getReasoning());
    }

    /**
     * TC-Service-005: 系统巡检_高置信度
     */
    @Test
    public void testInspection_HighConfidence() {
        String response = "{\"intent\":\"INSPECTION\",\"confidence\":\"HIGH\",\"reasoning\":\"健康检查请求\"}";
        mockLLMResponse(response);

        IntentRoutingResult result = intentRoutingService.route("执行系统健康检查", "prompt");

        assertEquals(IntentTypeEnum.INSPECTION, result.getIntent());
        assertEquals(ConfidenceEnum.HIGH, result.getConfidence());
        assertEquals("健康检查请求", result.getReasoning());
    }

    /**
     * TC-Service-006: 通用对话_低置信度
     */
    @Test
    public void testGeneralChat_LowConfidence() {
        String response = "{\"intent\":\"GENERAL_CHAT\",\"confidence\":\"LOW\",\"reasoning\":\"闲聊内容\"}";
        mockLLMResponse(response);

        IntentRoutingResult result = intentRoutingService.route("今天天气怎么样", "prompt");

        assertEquals(IntentTypeEnum.GENERAL_CHAT, result.getIntent());
        assertEquals(ConfidenceEnum.LOW, result.getConfidence());
        assertEquals("闲聊内容", result.getReasoning());
    }

    /**
     * TC-Service-007: 模糊意图_低置信度
     */
    @Test
    public void testAmbiguous_LowConfidence() {
        String response = "{\"intent\":\"AMBIGUOUS\",\"confidence\":\"LOW\",\"reasoning\":\"意图不明确\"}";
        mockLLMResponse(response);

        IntentRoutingResult result = intentRoutingService.route("那个事情怎么样了", "prompt");

        assertEquals(IntentTypeEnum.AMBIGUOUS, result.getIntent());
        assertEquals(ConfidenceEnum.LOW, result.getConfidence());
        assertEquals("意图不明确", result.getReasoning());
    }

    // ========== TC-Service-008 ~ TC-Service-010: 边界与降级测试 ==========

    /**
     * TC-Service-008: LLM返回非法JSON，降级为UNKNOWN+LOW
     */
    @Test
    public void testParseInvalidJson() {
        String response = "invalid json response";
        mockLLMResponse(response);

        IntentRoutingResult result = intentRoutingService.route("hello", "prompt");

        assertEquals(IntentTypeEnum.UNKNOWN, result.getIntent());
        assertEquals(ConfidenceEnum.LOW, result.getConfidence());
        assertTrue(result.getReasoning().contains("JSON解析失败"));
    }

    /**
     * TC-Service-009: LLM返回空JSON，降级处理
     */
    @Test
    public void testParseEmptyJson() {
        String response = "{}";
        mockLLMResponse(response);

        IntentRoutingResult result = intentRoutingService.route("hello", "prompt");

        assertEquals(IntentTypeEnum.UNKNOWN, result.getIntent());
        assertEquals(ConfidenceEnum.LOW, result.getConfidence());
    }

    /**
     * TC-Service-010: LLM返回缺省字段，intent有效但confidence缺省时降级为LOW
     */
    @Test
    public void testParseMissingFields() {
        String response = "{\"intent\":\"STOCK_ANALYSIS\"}";
        mockLLMResponse(response);

        IntentRoutingResult result = intentRoutingService.route("分析股票", "prompt");

        assertEquals(IntentTypeEnum.STOCK_ANALYSIS, result.getIntent());
        assertEquals(ConfidenceEnum.LOW, result.getConfidence());
    }

    /**
     * TC-Service-010b: LLM返回缺省字段，intent缺省时降级为UNKNOWN
     */
    @Test
    public void testParseMissingIntentField() {
        String response = "{\"confidence\":\"HIGH\",\"reasoning\":\"test\"}";
        mockLLMResponse(response);

        IntentRoutingResult result = intentRoutingService.route("hello", "prompt");

        assertEquals(IntentTypeEnum.UNKNOWN, result.getIntent());
    }

    // ========== TC-Service-011: 历史消息注入测试 ==========

    /**
     * TC-Service-011: 历史消息注入验证 - Prompt 包含历史消息内容
     */
    @Test
    public void testHistoryMessagesInjection() {
        String userMessage = "今天股票如何";
        List<String> historyMessages = List.of("user: 帮我分析平安银行", "assistant: 平安银行今天表现不错");

        String prompt = IntentRoutingPrompt.buildPrompt(userMessage, historyMessages);

        assertTrue(prompt.contains("user: 帮我分析平安银行"));
        assertTrue(prompt.contains("assistant: 平安银行今天表现不错"));
        assertTrue(prompt.contains("历史上下文"));
    }

    /**
     * TC-Service-011b: 无历史消息时的Prompt构建
     */
    @Test
    public void testNoHistoryMessages() {
        String userMessage = "你好";
        List<String> historyMessages = List.of();

        String prompt = IntentRoutingPrompt.buildPrompt(userMessage, historyMessages);

        assertTrue(prompt.contains("（无历史对话）"));
    }

    // ========== TC-Service-012: LLM调用异常降级测试 ==========

    /**
     * TC-Service-012: LLM调用异常_降级处理，流程不阻断
     */
    @Test
    public void testLLMCallException_GracefulDegradation() {
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("LLM service unavailable"));

        IntentRoutingResult result = intentRoutingService.route("hello", "prompt");

        assertEquals(IntentTypeEnum.UNKNOWN, result.getIntent());
        assertEquals(ConfidenceEnum.LOW, result.getConfidence());
        assertTrue(result.getReasoning().contains("LLM调用异常"));
    }

    // ========== 额外边界测试 ==========

    /**
     * TC-Service-013: null响应降级处理
     */
    @Test
    public void testNullResponse() {
        IntentRoutingResult result = intentRoutingService.parseResponse(null);

        assertEquals(IntentTypeEnum.UNKNOWN, result.getIntent());
        assertEquals(ConfidenceEnum.LOW, result.getConfidence());
    }

    /**
     * TC-Service-014: 空字符串响应降级处理
     */
    @Test
    public void testBlankResponse() {
        IntentRoutingResult result = intentRoutingService.parseResponse("   ");

        assertEquals(IntentTypeEnum.UNKNOWN, result.getIntent());
        assertEquals(ConfidenceEnum.LOW, result.getConfidence());
    }

    /**
     * TC-Service-015: reasoning字段为null时的处理
     */
    @Test
    public void testNullReasoning() {
        String response = "{\"intent\":\"STOCK_ANALYSIS\",\"confidence\":\"HIGH\"}";
        mockLLMResponse(response);

        IntentRoutingResult result = intentRoutingService.route("分析股票", "prompt");

        assertEquals("无推理过程", result.getReasoning());
    }
}
