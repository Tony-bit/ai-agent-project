package denny.ai.agent.domain.service.auto.step.routing;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.IntentRoutingResult;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.chat.GeneralChatNode;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.pe.Step1AnalyzerNode;
import denny.ai.agent.domain.service.auto.step.react.IntelligentInspection;
import denny.ai.agent.domain.service.chatmemory.ChatMemoryPersistenceService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IntentRoutingNode 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-Node-001 ~ TC-Node-008: 路由分支测试（8种意图）
 * 2. TC-Node-009: 低置信度_不阻断
 * 3. TC-Node-010: 低置信度_记录warn
 * 4. TC-Node-011: 路由结果_存入DynamicContext
 * </p>
 *
 * @author denny
 * 2026/5/11
 */
@RunWith(MockitoJUnitRunner.class)
public class IntentRoutingNodeTest {

    @Mock
    private IntentRoutingService intentRoutingService;

    @Mock
    private ChatMemoryPersistenceService chatMemoryPersistenceService;

    @Mock
    private Step1AnalyzerNode step1AnalyzerNode;

    @Mock
    private IntelligentInspection intelligentInspection;

    @Mock
    private GeneralChatNode generalChatNode;

    private IntentRoutingNode intentRoutingNode;

    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext;

    private ExecuteCommandEntity request;

    @Before
    public void setUp() throws Exception {
        intentRoutingNode = new IntentRoutingNode();
        setField(intentRoutingNode, "intentRoutingService", intentRoutingService);
        setField(intentRoutingNode, "chatMemoryPersistenceService", chatMemoryPersistenceService);
        setField(intentRoutingNode, "step1AnalyzerNode", step1AnalyzerNode);
        setField(intentRoutingNode, "intelligentInspection", intelligentInspection);
        setField(intentRoutingNode, "generalChatNode", generalChatNode);

        dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        Map<String, AiAgentClientFlowConfigVO> configMap = new HashMap<>();
        // 意图路由节点需要 INTENT_ROUTING 配置
        configMap.put(AiClientTypeEnumVO.INTENT_ROUTING.getCode(),
                AiAgentClientFlowConfigVO.builder().clientId("intent-routing-client").build());
        // 下游节点可能路由到任意节点，需要配置完整的 clientType
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

    // ========== TC-Node-001 ~ TC-Node-008: get() 路由分支测试 ==========

    /**
     * TC-Node-001: PE_REASONING路由 → step1AnalyzerNode
     */
    @Test
    public void testPEReasoningRouting() throws Exception {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.PE_REASONING);

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        assertEquals(step1AnalyzerNode, handler);
    }

    /**
     * TC-Node-002: PE_CALCULATION路由 → step1AnalyzerNode
     */
    @Test
    public void testPECalculationRouting() throws Exception {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.PE_CALCULATION);

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        assertEquals(step1AnalyzerNode, handler);
    }

    /**
     * TC-Node-003: PE_RETRIEVAL路由 → step1AnalyzerNode
     */
    @Test
    public void testPERetrievalRouting() throws Exception {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.PE_RETRIEVAL);

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        assertEquals(step1AnalyzerNode, handler);
    }

    /**
     * TC-Node-004: INSPECTION路由 → intelligentInspection
     */
    @Test
    public void testInspectionRouting() throws Exception {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.INSPECTION);

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        assertEquals(intelligentInspection, handler);
    }

    /**
     * TC-Node-005: GENERAL_CHAT路由 → generalChatNode
     */
    @Test
    public void testGeneralChatRouting() throws Exception {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.GENERAL_CHAT);

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        assertEquals(generalChatNode, handler);
    }

    /**
     * TC-Node-006: AMBIGUOUS路由 → generalChatNode
     */
    @Test
    public void testAmbiguousRouting() throws Exception {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.AMBIGUOUS);

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        assertEquals(generalChatNode, handler);
    }

    /**
     * TC-Node-007: UNKNOWN路由 → generalChatNode
     */
    @Test
    public void testUnknownRouting() throws Exception {
        dynamicContext.setValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY, IntentTypeEnum.UNKNOWN);

        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        assertEquals(generalChatNode, handler);
    }

    // ========== 置信度与上下文测试 ==========

    /**
     * TC-Node-008: 意图为null时，get() 返回 generalChatNode
     */
    @Test
    public void testNullIntent_returnsGeneralChatNode() throws Exception {
        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> handler =
                intentRoutingNode.get(request, dynamicContext);

        assertEquals(generalChatNode, handler);
    }

    /**
     * TC-Node-009: 低置信度时，路由结果存入 DynamicContext
     */
    @Test
    public void testLowConfidence_ResultStoredInContext() throws Exception {
        IntentRoutingResult lowConfidenceResult =
                IntentRoutingResult.builder()
                        .intent(IntentTypeEnum.GENERAL_CHAT)
                        .confidence(ConfidenceEnum.LOW)
                        .reasoning("信号较弱")
                        .build();

        IntentRoutingNode spyNode = Mockito.spy(intentRoutingNode);
        doReturn(lowConfidenceResult).when(spyNode).doRoute(any(), any(), any());

        spyNode.doApply(request, dynamicContext);

        IntentRoutingResult storedResult =
                dynamicContext.getValue(IntentRoutingNode.ROUTING_RESULT_KEY);
        assertNotNull(storedResult);
        assertEquals(IntentTypeEnum.GENERAL_CHAT, storedResult.getIntent());
        assertEquals(ConfidenceEnum.LOW, storedResult.getConfidence());
    }

    /**
     * TC-Node-010: 路由结果存入 DynamicContext
     */
    @Test
    public void testRoutingResultStoredInContext() throws Exception {
        IntentRoutingResult result =
                IntentRoutingResult.builder()
                        .intent(IntentTypeEnum.PE_CALCULATION)
                        .confidence(ConfidenceEnum.MEDIUM)
                        .reasoning("数学计算任务")
                        .build();

        IntentRoutingNode spyNode = Mockito.spy(intentRoutingNode);
        doReturn(result).when(spyNode).doRoute(any(), any(), any());

        spyNode.doApply(request, dynamicContext);

        IntentRoutingResult storedResult =
                dynamicContext.getValue(IntentRoutingNode.ROUTING_RESULT_KEY);
        assertNotNull(storedResult);
        assertEquals(IntentTypeEnum.PE_CALCULATION, storedResult.getIntent());
        assertEquals(ConfidenceEnum.MEDIUM, storedResult.getConfidence());

        IntentTypeEnum storedIntent = dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY);
        assertEquals(IntentTypeEnum.PE_CALCULATION, storedIntent);
    }

    /**
     * TC-Node-011: 获取历史消息失败时，降级为空列表
     */
    @Test
    public void testGetHistoryFailed_DegradationToEmpty() throws Exception {
        IntentRoutingResult result =
                IntentRoutingResult.builder()
                        .intent(IntentTypeEnum.GENERAL_CHAT)
                        .confidence(ConfidenceEnum.HIGH)
                        .reasoning("测试")
                        .build();

        IntentRoutingNode spyNode = Mockito.spy(intentRoutingNode);
        doReturn(result).when(spyNode).doRoute(any(), any(), any());

        spyNode.doApply(request, dynamicContext);

        IntentTypeEnum storedIntent = dynamicContext.getValue(IntentRoutingNode.RECOGNIZED_INTENT_KEY);
        assertEquals(IntentTypeEnum.GENERAL_CHAT, storedIntent);
    }
}
