package denny.ai.agent.domain.service.auto.step.routing;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.BaseSlot;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.StockSlot;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.model.valobj.stock.StockAnalysisMode;
import denny.ai.agent.domain.model.valobj.stock.StockRequestRouteDecisionType;
import denny.ai.agent.domain.model.valobj.stock.StockRequestRoutingDecision;
import denny.ai.agent.domain.model.entity.RoutingConversationContext;
import denny.ai.agent.domain.service.auto.step.chat.GeneralChatNode;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.pe.Step1AnalyzerNode;
import denny.ai.agent.domain.service.auto.step.react.IntelligentInspection;
import denny.ai.agent.domain.service.chatmemory.ConversationContextProvider;
import denny.ai.agent.domain.service.stock.StockResolutionPendingRepository;
import denny.ai.agent.domain.service.runtime.RuntimeContextKeys;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.lang.reflect.Field;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * IntentRoutingNode 单元测试
 *
 * @author denny
 * 2026/5/11
 */
@RunWith(MockitoJUnitRunner.class)
public class IntentRoutingNodeTest {

    @Mock
    private IntentRoutingService intentRoutingService;
    @Mock
    private StockRequestResolver stockRequestResolver;
    @Mock
    private StockResolutionPendingRepository stockResolutionPendingRepository;

    @Mock
    private ConversationContextProvider conversationContextProvider;

    @Mock
    private Step1AnalyzerNode step1AnalyzerNode;

    @Mock
    private IntelligentInspection intelligentInspection;

    @Mock
    private GeneralChatNode generalChatNode;

    @Mock
    private MultiTaskExecutionNode multiTaskExecutionNode;

    @Mock
    private StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> tradingRequestNode;

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private ResponseBodyEmitter emitter;

    private IntentRoutingNode intentRoutingNode;

    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext;

    private ExecuteCommandEntity request;

    @Before
    public void setUp() throws Exception {
        intentRoutingNode = new IntentRoutingNode();
        setField(intentRoutingNode, "intentRoutingService", intentRoutingService);
        setField(intentRoutingNode, "stockRequestResolver", stockRequestResolver);
        setField(intentRoutingNode, "stockResolutionPendingRepository", stockResolutionPendingRepository);
        setField(intentRoutingNode, "analysisDepthFollowUpResolver", new AnalysisDepthFollowUpResolver());
        setField(intentRoutingNode, "conversationContextProvider", conversationContextProvider);
        setField(intentRoutingNode, "step1AnalyzerNode", step1AnalyzerNode);
        setField(intentRoutingNode, "intelligentInspection", intelligentInspection);
        setField(intentRoutingNode, "generalChatNode", generalChatNode);
        setField(intentRoutingNode, "multiTaskExecutionNode", multiTaskExecutionNode);
        setField(intentRoutingNode, "applicationContext", applicationContext);

        dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        Map<String, AiAgentClientFlowConfigVO> configMap = new HashMap<>();
        configMap.put(AiClientTypeEnumVO.INTENT_ROUTING.getCode(),
                AiAgentClientFlowConfigVO.builder().clientId("intent-routing-client").build());
        configMap.put(AiClientTypeEnumVO.TASK_ANALYZER_CLIENT.getCode(),
                AiAgentClientFlowConfigVO.builder().clientId("task-analyzer-client").build());
        configMap.put(AiClientTypeEnumVO.OPS_ASSISTANT.getCode(),
                AiAgentClientFlowConfigVO.builder().clientId("ops-assistant-client").build());
        configMap.put(AiClientTypeEnumVO.RESPONSE_ASSISTANT.getCode(),
                AiAgentClientFlowConfigVO.builder().clientId("response-assistant-client").build());
        dynamicContext.setAiAgentClientFlowConfigVOMap(configMap);

        request = ExecuteCommandEntity.builder()
                .sessionId("test-session-123")
                .message("测试消息")
                .build();
        lenient().when(conversationContextProvider.getRoutingContext(anyString()))
                .thenReturn(RoutingConversationContext.builder().historyMessages(List.of()).build());
        lenient().when(stockRequestResolver.resolve(anyString(), anyString(), any(), any()))
                .thenReturn(null);
        lenient().when(stockResolutionPendingRepository.deleteClaimed(anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    @Test
    public void testDoApplyUsesUnifiedRoutingService() throws Exception {
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(buildSingleTaskResult(IntentTypeEnum.PE_RETRIEVAL, "step1AnalyzerNode"));

        intentRoutingNode.doApply(request, dynamicContext);

        verify(intentRoutingService).routeUnified(
                anyString(), org.mockito.ArgumentMatchers.anyList(),
                any(AiAgentClientFlowConfigVO.class), eq("test-session-123"));
        assertEquals(IntentTypeEnum.PE_RETRIEVAL, dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY));
    }

    @Test
    public void testMultiTaskWritesTaskList() throws Exception {
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(MultiIntentRoutingResult.builder()
                        .multiTask(true)
                        .needsClarification(false)
                        .reasoning("多任务")
                        .taskList(List.of(
                                buildSubTask("sub-1", 1, 2, IntentTypeEnum.PE_RETRIEVAL, "step1AnalyzerNode", Map.of()),
                                buildSubTask("sub-2", 2, 2, IntentTypeEnum.GENERAL_CHAT, "generalChatNode", Map.of())
                        ))
                        .build());

        intentRoutingNode.doApply(request, dynamicContext);

        List<SubTask> taskList = dynamicContext.getValue(MultiTaskExecutionNode.TASK_LIST_KEY);
        assertNotNull(taskList);
        assertEquals(2, taskList.size());
    }

    @Test
    public void testNeedsClarificationReturnsPrompt() throws Exception {
        dynamicContext.setValue("emitter", emitter);
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(MultiIntentRoutingResult.builder()
                        .multiTask(false)
                        .needsClarification(true)
                        .missingInfo(List.of("stockCode"))
                        .clarificationPrompt("请提供股票代码")
                        .reasoning("缺少股票标的")
                        .taskList(List.of())
                        .build());

        String result = intentRoutingNode.doApply(request, dynamicContext);

        assertEquals("请提供股票代码", result);
        assertEquals("请提供股票代码", dynamicContext.getValue("clarificationPrompt"));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(emitter, times(2)).send(eventCaptor.capture());
        List<Object> frames = eventCaptor.getAllValues();
        AutoAgentExecuteResultEntity clarification = parseSseFrame(frames.get(0));
        AutoAgentExecuteResultEntity complete = parseSseFrame(frames.get(1));
        assertEquals("summary", clarification.getType());
        assertEquals("clarification", clarification.getSubType());
        assertEquals("请提供股票代码", clarification.getContent());
        assertEquals("complete", complete.getType());
        assertTrue(complete.getCompleted());
    }

    @Test
    public void shouldSendResolverClarificationThroughExistingTerminalProtocol() throws Exception {
        dynamicContext.setValue("emitter", emitter);
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(),
                any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(buildSingleTaskResult(IntentTypeEnum.GENERAL_CHAT, "generalChatNode"));
        when(stockRequestResolver.resolve(eq("test-session-123"), eq("测试消息"), eq(IntentTypeEnum.GENERAL_CHAT), any()))
                .thenReturn(StockRequestRoutingDecision.builder()
                        .decisionType(StockRequestRouteDecisionType.CLARIFY_TARGET)
                        .analysisMode(StockAnalysisMode.FULL)
                        .clarificationPrompt("请选择股票")
                        .build());

        String response = intentRoutingNode.doApply(request, dynamicContext);

        assertEquals("请选择股票", response);
        verify(generalChatNode, never()).apply(any(), any());
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(emitter, times(2)).send(eventCaptor.capture());
        List<Object> frames = eventCaptor.getAllValues();
        AutoAgentExecuteResultEntity clarification = parseSseFrame(frames.get(0));
        AutoAgentExecuteResultEntity complete = parseSseFrame(frames.get(1));
        assertEquals("clarification", clarification.getSubType());
        assertEquals("请选择股票", clarification.getContent());
        assertEquals("complete", complete.getType());
    }

    @Test
    public void shouldForwardResolverQuickExecutionQueryToGeneralChatNode() throws Exception {
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(),
                any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(buildSingleTaskResult(IntentTypeEnum.STOCK_ANALYSIS, "tradingRequestNode"));
        when(stockRequestResolver.resolve(eq("test-session-123"), eq("测试消息"), eq(IntentTypeEnum.STOCK_ANALYSIS), any()))
                .thenReturn(StockRequestRoutingDecision.builder()
                        .decisionType(StockRequestRouteDecisionType.ROUTE_GENERAL_CHAT)
                        .analysisMode(StockAnalysisMode.QUICK)
                        .executionQuery("EXECUTION_QUERY")
                        .pendingVersion("v-1")
                        .claimId("claim-1")
                        .build());
        when(generalChatNode.apply(any(), eq(dynamicContext))).thenReturn("quick-response");

        String response = intentRoutingNode.doApply(request, dynamicContext);

        assertEquals("quick-response", response);
        ArgumentCaptor<ExecuteCommandEntity> requestCaptor = ArgumentCaptor.forClass(ExecuteCommandEntity.class);
        verify(generalChatNode).apply(requestCaptor.capture(), eq(dynamicContext));
        assertEquals("EXECUTION_QUERY", requestCaptor.getValue().getMessage());
        assertEquals(IntentTypeEnum.FINANCIAL_GENERAL, dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY));
        verify(stockResolutionPendingRepository).deleteClaimed("test-session-123", "v-1", "claim-1");
    }

    @Test
    public void shouldSendOneCompletedErrorWhenDownstreamRegistersErrorTerminal() throws Exception {
        dynamicContext.setValue("emitter", emitter);
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(),
                any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(buildSingleTaskResult(IntentTypeEnum.STOCK_ANALYSIS, "tradingRequestNode"));
        when(applicationContext.getBean("tradingRequestNode")).thenReturn(tradingRequestNode);
        doAnswer(invocation -> {
            dynamicContext.setValue("routingTerminalKind", "ERROR");
            dynamicContext.setValue("routingTerminalResponse", "股票数据服务暂时不可用，请稍后重试");
            return "股票数据服务暂时不可用，请稍后重试";
        }).when(tradingRequestNode).apply(any(), eq(dynamicContext));

        String response = intentRoutingNode.doApply(request, dynamicContext);

        assertEquals("股票数据服务暂时不可用，请稍后重试", response);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(emitter, times(1)).send(eventCaptor.capture());
        AutoAgentExecuteResultEntity error = parseSseFrame(eventCaptor.getValue());
        assertEquals("error", error.getType());
        assertTrue(error.getCompleted());
    }

    @Test
    public void shouldNotRetryWhenClarificationSseDeliveryFails() throws Exception {
        dynamicContext.setValue("emitter", emitter);
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(),
                any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(MultiIntentRoutingResult.builder()
                        .multiTask(false)
                        .needsClarification(true)
                        .missingInfo(List.of("stockCode"))
                        .clarificationPrompt("请提供股票代码")
                        .taskList(List.of())
                        .build());
        doThrow(new IOException("client disconnected")).when(emitter).send(any(Object.class));

        String response = intentRoutingNode.doApply(request, dynamicContext);

        assertEquals("请提供股票代码", response);
        verify(emitter, times(1)).send(any(Object.class));
    }

    @Test
    public void testSingleTaskPERetrievalRouting() throws Exception {
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(buildSingleTaskResult(IntentTypeEnum.PE_RETRIEVAL, "step1AnalyzerNode"));

        intentRoutingNode.doApply(request, dynamicContext);
        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        assertEquals(step1AnalyzerNode, handler);
    }

    @Test
    public void testSingleTaskGeneralChatRouting() throws Exception {
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(buildSingleTaskResult(IntentTypeEnum.GENERAL_CHAT, "generalChatNode"));

        intentRoutingNode.doApply(request, dynamicContext);
        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        assertEquals(generalChatNode, handler);
    }

    @Test
    public void testStockSlotStoredInContext() throws Exception {
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(buildSingleTaskResult(IntentTypeEnum.STOCK_ANALYSIS, "tradingStarter"));

        intentRoutingNode.doApply(request, dynamicContext);

        StockSlot stockSlot = dynamicContext.getValue(IntentRoutingNode.STOCK_SLOT_KEY);
        assertNotNull(stockSlot);
        assertEquals("600519", stockSlot.getStockCode());
    }

    @Test
    public void testGetRoutesInspectionToInspectionNode() throws Exception {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.INSPECTION);

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        assertEquals(intelligentInspection, handler);
    }

    @Test
    public void should_route_to_trading_node_when_intent_is_stock_analysis() throws Exception {
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(buildSingleTaskResult(IntentTypeEnum.STOCK_ANALYSIS, "tradingStarter"));
        when(applicationContext.getBean("tradingRequestNode")).thenReturn(tradingRequestNode);

        intentRoutingNode.doApply(request, dynamicContext);
        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        assertEquals(tradingRequestNode, handler);
    }

    @Test
    public void should_keep_single_task_context_mapping_compatible_after_unified_routing() throws Exception {
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(buildSingleTaskResult(IntentTypeEnum.PE_RETRIEVAL, "step1AnalyzerNode"));

        intentRoutingNode.doApply(request, dynamicContext);

        assertEquals(IntentTypeEnum.PE_RETRIEVAL, dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY));
        assertNotNull(dynamicContext.getValue(IntentRoutingNode.ROUTING_RESULT_KEY));
        assertNotNull(dynamicContext.getValue(IntentRoutingNode.BASE_SLOT_KEY));
        assertNotNull(dynamicContext.getValue(IntentRoutingNode.INTENT_SPECIFIC_SLOTS_KEY));
    }

    @Test
    public void should_keep_downstream_node_selection_unchanged_after_mainline_switch() throws Exception {
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(buildSingleTaskResult(IntentTypeEnum.PE_REASONING, "step1AnalyzerNode"));

        intentRoutingNode.doApply(request, dynamicContext);
        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        assertEquals(step1AnalyzerNode, handler);
    }

    @Test
    public void should_fallback_to_general_chat_when_trading_node_is_missing() throws Exception {
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(buildSingleTaskResult(IntentTypeEnum.STOCK_ANALYSIS, "tradingStarter"));
        when(applicationContext.getBean("tradingRequestNode")).thenThrow(new RuntimeException("missing bean"));

        intentRoutingNode.doApply(request, dynamicContext);
        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        assertEquals(generalChatNode, handler);
    }

    @Test
    public void should_pass_intent_routing_config_to_service_when_unified_routing_is_called() throws Exception {
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(buildSingleTaskResult(IntentTypeEnum.PE_RETRIEVAL, "step1AnalyzerNode"));

        intentRoutingNode.doApply(request, dynamicContext);

        ArgumentCaptor<AiAgentClientFlowConfigVO> captor = ArgumentCaptor.forClass(AiAgentClientFlowConfigVO.class);
        verify(intentRoutingService).routeUnified(
                anyString(), org.mockito.ArgumentMatchers.anyList(), captor.capture(), eq("test-session-123"));
        assertEquals("intent-routing-client", captor.getValue().getClientId());
    }

    @Test
    public void should_use_prepared_history_without_loading_conversation_history() throws Exception {
        List<String> preparedHistory = List.of("user: 上一轮", "assistant: 上一轮回答");
        dynamicContext.setValue(RuntimeContextKeys.RECENT_HISTORY_MESSAGES, preparedHistory);
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(buildSingleTaskResult(IntentTypeEnum.PE_RETRIEVAL, "step1AnalyzerNode"));

        intentRoutingNode.doApply(request, dynamicContext);

        ArgumentCaptor<List<String>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(intentRoutingService).routeUnified(
                anyString(), historyCaptor.capture(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123"));
        assertEquals(preparedHistory, historyCaptor.getValue());
        verify(conversationContextProvider, never()).getRoutingContext(anyString());
    }

    @Test
    public void should_use_resolved_analysis_depth_query_for_routing_and_downstream_execution() throws Exception {
        request.setMessage("我要进行完整投资分析");
        List<String> history = List.of(
                "user: 给我分析一下中国平安",
                "assistant: 你需要快速了解，还是进行完整投资分析？");
        dynamicContext.setValue(RuntimeContextKeys.RECENT_HISTORY_MESSAGES, history);
        when(intentRoutingService.routeUnified(
                eq("给我分析一下中国平安；进行完整投资分析"), eq(history),
                any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(MultiIntentRoutingResult.builder()
                        .multiTask(false)
                        .needsClarification(true)
                        .missingInfo(List.of("stockCode"))
                        .clarificationPrompt("请提供股票代码")
                        .taskList(List.of())
                        .build());
        when(applicationContext.getBean("tradingRequestNode")).thenReturn(tradingRequestNode);

        intentRoutingNode.doApply(request, dynamicContext);

        ArgumentCaptor<ExecuteCommandEntity> requestCaptor = ArgumentCaptor.forClass(ExecuteCommandEntity.class);
        verify(tradingRequestNode).apply(requestCaptor.capture(), eq(dynamicContext));
        assertEquals("给我分析一下中国平安；进行完整投资分析", requestCaptor.getValue().getMessage());
        assertEquals("我要进行完整投资分析", request.getMessage());
        assertEquals(IntentTypeEnum.STOCK_ANALYSIS,
                dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY));
    }

    @Test
    public void should_fallback_to_legacy_history_when_prepared_history_type_is_invalid() throws Exception {
        dynamicContext.setValue(RuntimeContextKeys.RECENT_HISTORY_MESSAGES, "bad-history");
        when(conversationContextProvider.getRoutingContext("test-session-123"))
                .thenReturn(RoutingConversationContext.builder().historyMessages(List.of("user: legacy")).build());
        when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123")))
                .thenReturn(buildSingleTaskResult(IntentTypeEnum.PE_RETRIEVAL, "step1AnalyzerNode"));

        intentRoutingNode.doApply(request, dynamicContext);

        ArgumentCaptor<List<String>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(intentRoutingService).routeUnified(
                anyString(), historyCaptor.capture(), any(AiAgentClientFlowConfigVO.class), eq("test-session-123"));
        assertEquals(List.of("user: legacy"), historyCaptor.getValue());
        verify(conversationContextProvider).getRoutingContext("test-session-123");
    }

    private MultiIntentRoutingResult buildSingleTaskResult(IntentTypeEnum intent, String executorNode) {
        Map<String, Object> slots = new HashMap<>();
        slots.put("baseSlot", BaseSlot.builder().topic("主题").sentiment("neutral").build());
        if (intent == IntentTypeEnum.STOCK_ANALYSIS) {
            slots.put("intentSpecificSlots", Map.of(
                    "stockSlot", StockSlot.builder()
                            .stockCode("600519")
                            .stockQueryType("TECHNICAL")
                            .timeRange("近三个月")
                            .exchange("SH")
                            .build()
            ));
        } else {
            slots.put("intentSpecificSlots", Map.of("topic", "主题"));
        }

        return MultiIntentRoutingResult.builder()
                .multiTask(false)
                .needsClarification(false)
                .reasoning("单任务")
                .taskList(List.of(buildSubTask("sub-1", 1, 1, intent, executorNode, slots)))
                .build();
    }

    private SubTask buildSubTask(String taskId, int taskIndex, int totalTasks, IntentTypeEnum intent,
                                 String executorNode, Map<String, Object> slots) {
        return SubTask.builder()
                .taskId(taskId)
                .taskIndex(taskIndex)
                .totalTasks(totalTasks)
                .content("任务内容")
                .intent(intent)
                .executorNode(executorNode)
                .confidence(ConfidenceEnum.HIGH)
                .slots(slots)
                .taskType(0)
                .status(SubTask.SubTaskStatus.PENDING)
                .build();
    }

    private AutoAgentExecuteResultEntity parseSseFrame(Object frame) {
        String data = String.valueOf(frame).replaceFirst("^data: ", "").trim();
        return JSON.parseObject(data, AutoAgentExecuteResultEntity.class);
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
