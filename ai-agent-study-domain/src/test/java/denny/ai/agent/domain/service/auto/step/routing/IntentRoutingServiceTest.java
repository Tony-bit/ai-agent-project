package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.entity.IntentFewshotSample;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.StockSlot;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.domain.service.intent.IntentFewshotService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * IntentRoutingService 单元测试
 *
 * @author denny
 * 2026/5/11
 */
@RunWith(MockitoJUnitRunner.class)
public class IntentRoutingServiceTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private IntentFewshotService intentFewshotService;

    @Mock
    private ArmoryObjectRegistry armoryObjectRegistry;

    private IntentRoutingService intentRoutingService;

    private AiAgentClientFlowConfigVO configVO;

    @Before
    public void setUp() throws Exception {
        intentRoutingService = new IntentRoutingService();
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        configVO = AiAgentClientFlowConfigVO.builder().clientId("intent-routing-client").build();

        when(armoryObjectRegistry.get("ai_client_intent-routing-clienttaskType0")).thenReturn(chatClient);

        setField(intentRoutingService, "armoryObjectRegistry", armoryObjectRegistry);
        setField(intentRoutingService, "intentFewshotService", intentFewshotService);
    }

    @Test
    public void testUnifiedPromptContainsFewshotSamples() {
        List<IntentFewshotSample> fewshotSamples = List.of(
                IntentFewshotSample.builder()
                        .queryText("什么是向量数据库")
                        .exampleJson("{\"intent\":\"PE_RETRIEVAL\"}")
                        .build()
        );

        String prompt = IntentRoutingPrompt.buildUnifiedRoutingPrompt(
                "解释RAG",
                List.of("user: 之前问过检索增强"),
                fewshotSamples
        );

        assertTrue(prompt.contains("## 参考示例"));
        assertTrue(prompt.contains("用户: 什么是向量数据库"));
        assertTrue(prompt.contains("输出: {\"intent\":\"PE_RETRIEVAL\"}"));
    }

    @Test
    public void testParseUnifiedSingleTaskResponse() {
        String response = """
                {
                  "multiTask": false,
                  "needsClarification": false,
                  "reasoning": "知识检索任务",
                  "taskList": [
                    {
                      "taskId": "sub-1",
                      "taskIndex": 1,
                      "totalTasks": 1,
                      "content": "解释向量数据库",
                      "intent": "PE_RETRIEVAL",
                      "executorNode": "step1AnalyzerNode",
                      "confidence": "HIGH",
                      "taskType": 0,
                      "slots": {
                        "baseSlot": {"topic": "向量数据库", "sentiment": "neutral"},
                        "intentSpecificSlots": {"topic": "向量数据库"}
                      }
                    }
                  ]
                }
                """;

        MultiIntentRoutingResult result = intentRoutingService.parseUnifiedResponse(response);

        assertFalse(result.getMultiTask());
        assertFalse(result.getNeedsClarification());
        assertEquals(1, result.getTaskList().size());
        assertEquals(IntentTypeEnum.PE_RETRIEVAL, result.getTaskList().get(0).getIntent());
        assertEquals(ConfidenceEnum.HIGH, result.getTaskList().get(0).getConfidence());
    }

    @Test
    public void testParseUnifiedMultiTaskResponse() {
        String response = """
                {
                  "multiTask": true,
                  "needsClarification": false,
                  "reasoning": "包含两个独立任务",
                  "taskList": [
                    {
                      "taskId": "sub-1",
                      "taskIndex": 1,
                      "totalTasks": 2,
                      "content": "分析贵州茅台",
                      "intent": "STOCK_ANALYSIS",
                      "executorNode": "tradingStarter",
                      "confidence": "HIGH",
                      "taskType": 0,
                      "slots": {
                        "baseSlot": {"topic": "贵州茅台", "sentiment": "neutral"},
                        "intentSpecificSlots": {
                          "stockCode": "600519",
                          "stockQueryType": "TECHNICAL",
                          "timeRange": "近三个月",
                          "exchange": "SH"
                        }
                      }
                    },
                    {
                      "taskId": "sub-2",
                      "taskIndex": 2,
                      "totalTasks": 2,
                      "content": "解释估值逻辑",
                      "intent": "PE_REASONING",
                      "executorNode": "step1AnalyzerNode",
                      "confidence": "MEDIUM",
                      "taskType": 0,
                      "slots": {
                        "baseSlot": {"topic": "估值逻辑", "sentiment": "neutral"},
                        "intentSpecificSlots": {}
                      }
                    }
                  ]
                }
                """;

        MultiIntentRoutingResult result = intentRoutingService.parseUnifiedResponse(response);
        SubTask firstTask = result.getTaskList().get(0);
        Object stockSlotObj = ((java.util.Map<?, ?>) firstTask.getSlots().get("intentSpecificSlots")).get("stockSlot");

        assertTrue(result.getMultiTask());
        assertEquals(2, result.getTaskList().size());
        assertTrue(stockSlotObj instanceof StockSlot);
        assertEquals("600519", ((StockSlot) stockSlotObj).getStockCode());
    }

    @Test
    public void testParseUnifiedClarificationResponse() {
        String response = """
                {
                  "multiTask": false,
                  "needsClarification": true,
                  "missingInfo": ["stockCode"],
                  "clarificationPrompt": "请提供股票代码",
                  "reasoning": "缺少股票标的",
                  "taskList": []
                }
                """;

        MultiIntentRoutingResult result = intentRoutingService.parseUnifiedResponse(response);

        assertTrue(result.getNeedsClarification());
        assertEquals("请提供股票代码", result.getClarificationPrompt());
        assertEquals(1, result.getMissingInfo().size());
        assertEquals("stockCode", result.getMissingInfo().get(0));
    }

    @Test
    public void testParseUnifiedInvalidJsonFallback() {
        MultiIntentRoutingResult result = intentRoutingService.parseUnifiedResponse("invalid json");

        assertFalse(result.getMultiTask());
        assertFalse(result.getNeedsClarification());
        assertEquals(IntentTypeEnum.GENERAL_CHAT, result.getTaskList().get(0).getIntent());
        assertTrue(result.getReasoning().contains("JSON解析失败"));
    }

    @Test
    public void testRouteUnified_WhenFewshotFails_StillWorks() throws Exception {
        doThrow(new RuntimeException("vector store unavailable"))
                .when(intentFewshotService).retrieveTopK("解释向量数据库", 5);
        mockLLMResponse("""
                {
                  "multiTask": false,
                  "needsClarification": false,
                  "reasoning": "知识检索",
                  "taskList": [
                    {
                      "taskId": "sub-1",
                      "taskIndex": 1,
                      "totalTasks": 1,
                      "content": "解释向量数据库",
                      "intent": "PE_RETRIEVAL",
                      "executorNode": "step1AnalyzerNode",
                      "confidence": "HIGH",
                      "taskType": 0,
                      "slots": {
                        "baseSlot": {"topic": "向量数据库", "sentiment": "neutral"},
                        "intentSpecificSlots": {"topic": "向量数据库"}
                      }
                    }
                  ]
                }
                """);

        MultiIntentRoutingResult result = intentRoutingService.routeUnified("解释向量数据库", List.of(), configVO);

        assertEquals(1, result.getTaskList().size());
        assertEquals(IntentTypeEnum.PE_RETRIEVAL, result.getTaskList().get(0).getIntent());
    }

    @Test
    public void testParseResponse_MissingConfidenceDefaultsLow() {
        String response = "{\"intent\":\"STOCK_ANALYSIS\"}";
        assertEquals(ConfidenceEnum.LOW, intentRoutingService.parseResponse(response).getConfidence());
    }

    // ========== Few-Shot 检索边界场景 ==========

    /**
     * TC-201: Few-Shot 检索成功但返回空列表，Prompt 仍可正常构建
     */
    @Test
    public void should_build_prompt_without_examples_when_fewshot_list_is_empty() {
        String prompt = IntentRoutingPrompt.buildUnifiedRoutingPrompt(
                "你好",
                List.of("user: 之前聊过股票"),
                List.of()
        );

        assertFalse(prompt.contains("## 参考示例"));
        assertTrue(prompt.contains("user: 之前聊过股票"));
        assertTrue(prompt.contains("你好"));
    }

    /**
     * TC-202: historyMessages 为空列表，Prompt 构造成功
     */
    @Test
    public void should_build_prompt_without_history_when_history_messages_are_empty() {
        String prompt = IntentRoutingPrompt.buildUnifiedRoutingPrompt(
                "什么是 RAG",
                List.of(),
                List.of(
                        IntentFewshotSample.builder()
                                .queryText("RAG是什么")
                                .exampleJson("{\"intent\":\"PE_RETRIEVAL\"}")
                                .build()
                )
        );

        assertTrue(prompt.contains("## 参考示例"));
        assertTrue(prompt.contains("（无历史对话）"));
        assertTrue(prompt.contains("RAG是什么"));
    }

    /**
     * TC-206: 超长用户消息下 Prompt 构造稳定，不出现 null/拼接错误
     */
    @Test
    public void should_build_prompt_stably_when_user_message_is_very_long() {
        String longMessage = "A".repeat(10000);
        List<IntentFewshotSample> fewshotSamples = List.of(
                IntentFewshotSample.builder()
                        .queryText("长文本知识检索示例" + "B".repeat(1000))
                        .exampleJson("{\"intent\":\"PE_RETRIEVAL\"}")
                        .build()
        );

        String prompt = IntentRoutingPrompt.buildUnifiedRoutingPrompt(
                longMessage,
                List.of("user: " + "C".repeat(1000)),
                fewshotSamples
        );

        assertNotNull(prompt);
        assertTrue(prompt.contains("## 参考示例"));
        assertTrue(prompt.contains(longMessage));
    }

    // ========== 统一路由解析异常/边界场景 ==========

    /**
     * TC-103: taskList 为空时降级为 GENERAL_CHAT
     */
    @Test
    public void should_fallback_to_general_chat_when_task_list_is_empty() {
        String response = """
                {
                  "multiTask": false,
                  "needsClarification": false,
                  "reasoning": "无任务",
                  "taskList": []
                }
                """;

        MultiIntentRoutingResult result = intentRoutingService.parseUnifiedResponse(response);

        assertFalse(result.getMultiTask());
        assertFalse(result.getNeedsClarification());
        assertEquals(1, result.getTaskList().size());
        assertEquals(IntentTypeEnum.GENERAL_CHAT, result.getTaskList().get(0).getIntent());
        assertTrue(result.getReasoning().contains("taskList为空"));
    }

    /**
     * TC-104: clarification 场景下 clarificationPrompt 缺失时安全处理
     */
    @Test
    public void should_handle_missing_clarification_prompt_safely() {
        String response = """
                {
                  "multiTask": false,
                  "needsClarification": true,
                  "missingInfo": ["stockCode"],
                  "reasoning": "缺少股票标的"
                }
                """;

        MultiIntentRoutingResult result = intentRoutingService.parseUnifiedResponse(response);

        assertTrue(result.getNeedsClarification());
        assertEquals(1, result.getMissingInfo().size());
        assertEquals("stockCode", result.getMissingInfo().get(0));
        assertNotNull(result.getClarificationPrompt());
    }

    /**
     * TC-203: multiTask=true 但 taskList 仅 1 项时，系统按单任务处理
     */
    @Test
    public void should_keep_single_task_when_multi_task_flag_is_true_but_only_one_task() {
        String response = """
                {
                  "multiTask": true,
                  "needsClarification": false,
                  "reasoning": "误判多任务",
                  "taskList": [
                    {
                      "taskId": "sub-1",
                      "taskIndex": 1,
                      "totalTasks": 1,
                      "content": "仅有一个任务",
                      "intent": "PE_RETRIEVAL",
                      "executorNode": "step1AnalyzerNode",
                      "confidence": "HIGH",
                      "taskType": 0,
                      "slots": {}
                    }
                  ]
                }
                """;

        MultiIntentRoutingResult result = intentRoutingService.parseUnifiedResponse(response);

        assertFalse(result.getMultiTask());
        assertEquals(1, result.getTaskList().size());
        assertEquals(IntentTypeEnum.PE_RETRIEVAL, result.getTaskList().get(0).getIntent());
    }

    /**
     * TC-204: slots 为空对象时安全处理
     */
    @Test
    public void should_parse_empty_slots_safely() {
        String response = """
                {
                  "multiTask": false,
                  "needsClarification": false,
                  "reasoning": "无槽位",
                  "taskList": [
                    {
                      "taskId": "sub-1",
                      "taskIndex": 1,
                      "totalTasks": 1,
                      "content": "无槽位任务",
                      "intent": "GENERAL_CHAT",
                      "executorNode": "generalChatNode",
                      "confidence": "MEDIUM",
                      "taskType": 0,
                      "slots": {}
                    }
                  ]
                }
                """;

        MultiIntentRoutingResult result = intentRoutingService.parseUnifiedResponse(response);

        assertFalse(result.getMultiTask());
        assertEquals(1, result.getTaskList().size());
        assertEquals(IntentTypeEnum.GENERAL_CHAT, result.getTaskList().get(0).getIntent());
    }

    /**
     * TC-205: needsClarification=true 但 missingInfo 为空时系统安全处理
     */
    @Test
    public void should_keep_clarification_state_when_missing_info_is_empty() {
        String response = """
                {
                  "multiTask": false,
                  "needsClarification": true,
                  "missingInfo": [],
                  "clarificationPrompt": "请补充信息",
                  "reasoning": "需要补全"
                }
                """;

        MultiIntentRoutingResult result = intentRoutingService.parseUnifiedResponse(response);

        assertTrue(result.getNeedsClarification());
        assertEquals(0, result.getMissingInfo().size());
        assertEquals("请补充信息", result.getClarificationPrompt());
    }

    /**
     * TC-105: taskList 单项缺失 intent 时降级为默认意图
     */
    @Test
    public void should_default_intent_when_task_intent_is_missing() {
        String response = """
                {
                  "multiTask": false,
                  "needsClarification": false,
                  "reasoning": "无意图",
                  "taskList": [
                    {
                      "taskId": "sub-1",
                      "taskIndex": 1,
                      "totalTasks": 1,
                      "content": "内容"
                    }
                  ]
                }
                """;

        MultiIntentRoutingResult result = intentRoutingService.parseUnifiedResponse(response);

        assertFalse(result.getMultiTask());
        assertEquals(1, result.getTaskList().size());
        assertEquals(IntentTypeEnum.GENERAL_CHAT, result.getTaskList().get(0).getIntent());
    }

    /**
     * TC-106: 统一路由 LLM 调用异常时降级为 GENERAL_CHAT，不崩溃
     */
    @Test
    public void should_fallback_to_general_chat_when_route_unified_throws() throws Exception {
        doThrow(new RuntimeException("LLM unavailable"))
                .when(chatModel).call(any(org.springframework.ai.chat.prompt.Prompt.class));

        MultiIntentRoutingResult result = intentRoutingService.routeUnified("测试", List.of(), configVO);

        assertFalse(result.getMultiTask());
        assertFalse(result.getNeedsClarification());
        assertEquals(1, result.getTaskList().size());
        assertEquals(IntentTypeEnum.GENERAL_CHAT, result.getTaskList().get(0).getIntent());
        assertTrue(result.getReasoning().contains("LLM调用异常"));
    }

    /**
     * TC-102: LLM 返回非法 JSON 时降级成功
     */
    @Test
    public void should_fallback_when_llm_returns_invalid_json() {
        MultiIntentRoutingResult result = intentRoutingService.parseUnifiedResponse("not a json at all");

        assertFalse(result.getMultiTask());
        assertFalse(result.getNeedsClarification());
        assertEquals(IntentTypeEnum.GENERAL_CHAT, result.getTaskList().get(0).getIntent());
        assertTrue(result.getReasoning().contains("JSON解析失败"));
    }

    @Test
    public void should_fallback_to_general_chat_when_route_unified_client_is_missing() {
        when(armoryObjectRegistry.get("ai_client_intent-routing-clienttaskType0")).thenReturn(null);

        MultiIntentRoutingResult result = intentRoutingService.routeUnified("测试", List.of(), configVO);

        assertFalse(result.getMultiTask());
        assertFalse(result.getNeedsClarification());
        assertEquals(1, result.getTaskList().size());
        assertEquals(IntentTypeEnum.GENERAL_CHAT, result.getTaskList().get(0).getIntent());
        assertTrue(result.getReasoning().contains("LLM调用异常"));
    }

    @Test
    public void should_fallback_to_general_chat_when_chat_client_is_missing() {
        AiAgentClientFlowConfigVO missingConfig = AiAgentClientFlowConfigVO.builder()
                .clientId("missing-client")
                .build();

        MultiIntentRoutingResult result = intentRoutingService.routeUnified("测试", List.of(), missingConfig);

        assertFalse(result.getMultiTask());
        assertFalse(result.getNeedsClarification());
        assertEquals(1, result.getTaskList().size());
        assertEquals(IntentTypeEnum.GENERAL_CHAT, result.getTaskList().get(0).getIntent());
        assertTrue(result.getReasoning().contains("LLM调用异常"));
    }

    private void mockLLMResponse(String responseContent) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(responseContent))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(chatResponse);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = getFieldRecursive(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Field getFieldRecursive(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null) {
                return getFieldRecursive(superClass, fieldName);
            }
            throw e;
        }
    }
}
