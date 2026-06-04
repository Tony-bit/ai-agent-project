package denny.ai.agent.domain.service.auto.step.routing;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.chat.GeneralChatNode;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.pe.Step1AnalyzerNode;
import denny.ai.agent.domain.service.auto.step.react.IntelligentInspection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MultiTaskExecutionNode 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-MTE-001: executorNode=generalChatNode，执行成功
 * 2. TC-MTE-002: executorNode=step1AnalyzerNode，执行成功
 * 3. TC-MTE-003: executorNode=intelligentInspection，执行成功
 * 4. TC-MTE-004: 未知 executorNode，抛出异常
 * 5. TC-MTE-005: TC-MTE-007: buildSummaryPrompt 包含 originalMessage
 * 6. TC-MTE-008: buildSummaryPrompt 包含失败任务的错误信息
 * 7. TC-MTE-009: 任务成功时，result 和 latencyMs 被正确设置
 * 8. TC-MTE-010: 任务失败时，errorMessage 被正确设置
 * 9. TC-MTE-011: 有依赖任务时注入依赖结果
 * 10. TC-MTE-012: 无依赖任务时保持原始内容
 * 11. TC-MTE-013: 汇总后保留已有 generalChatResponse
 * </p>
 *
 * @author denny
 * 2026/05/31
 */
@RunWith(MockitoJUnitRunner.class)
public class MultiTaskExecutionNodeTest {

    private MultiTaskExecutionNode multiTaskExecutionNode;

    @Mock
    private GeneralChatNode generalChatNode;

    @Mock
    private Step1AnalyzerNode step1AnalyzerNode;

    @Mock
    private IntelligentInspection intelligentInspection;

    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext;

    @Before
    public void setUp() throws Exception {
        multiTaskExecutionNode = new MultiTaskExecutionNode();

        Map<String, ExecutorAdapter> executorMap = new HashMap<>();
        executorMap.put("generalChatNode", generalChatNode);
        executorMap.put("step1AnalyzerNode", step1AnalyzerNode);
        executorMap.put("intelligentInspection", intelligentInspection);

        setFieldRecursive(multiTaskExecutionNode, "executorMap", executorMap);

        dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
    }

    private void setFieldRecursive(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field not found: " + fieldName);
    }

    /**
     * TC-MTE-001: executorNode=generalChatNode，执行成功
     */
    @Test
    public void testExecuteSubTask_generalChat_success() throws Exception {
        SubTask subTask = SubTask.builder()
                .taskId("sub-1").taskIndex(1).totalTasks(1)
                .content("什么是PE").intent(IntentTypeEnum.GENERAL_CHAT)
                .executorNode("generalChatNode").confidence(ConfidenceEnum.HIGH)
                .status(SubTask.SubTaskStatus.PENDING).build();

        when(generalChatNode.executeSubTask(any(SubTask.class), any()))
                .thenReturn("PE即市盈率，是衡量公司估值的重要指标。");

        String result = multiTaskExecutionNode.executeSubTask(subTask, dynamicContext);

        assertEquals(SubTask.SubTaskStatus.COMPLETED, subTask.getStatus());
        assertEquals("PE即市盈率，是衡量公司估值的重要指标。", result);
        assertTrue(subTask.getLatencyMs() >= 0);
        verify(generalChatNode, times(1)).executeSubTask(any(SubTask.class), any());
    }

    /**
     * TC-MTE-002: executorNode=step1AnalyzerNode，执行成功
     */
    @Test
    public void testExecuteSubTask_step1Analyzer_success() throws Exception {
        SubTask subTask = SubTask.builder()
                .taskId("sub-2").taskIndex(1).totalTasks(1)
                .content("计算茅台的PE").intent(IntentTypeEnum.PE_CALCULATION)
                .executorNode("step1AnalyzerNode").confidence(ConfidenceEnum.HIGH)
                .status(SubTask.SubTaskStatus.PENDING).build();

        when(step1AnalyzerNode.executeSubTask(any(SubTask.class), any()))
                .thenReturn("茅台当前PE为35倍，处于历史中等水平。");

        String result = multiTaskExecutionNode.executeSubTask(subTask, dynamicContext);

        assertEquals(SubTask.SubTaskStatus.COMPLETED, subTask.getStatus());
        assertEquals("茅台当前PE为35倍，处于历史中等水平。", result);
        assertTrue(subTask.getLatencyMs() >= 0);
        verify(step1AnalyzerNode, times(1)).executeSubTask(any(SubTask.class), any());
    }

    /**
     * TC-MTE-003: executorNode=intelligentInspection，执行成功
     */
    @Test
    public void testExecuteSubTask_intelligentInspection_success() throws Exception {
        SubTask subTask = SubTask.builder()
                .taskId("sub-3").taskIndex(1).totalTasks(1)
                .content("系统巡检").intent(IntentTypeEnum.INSPECTION)
                .executorNode("intelligentInspection").confidence(ConfidenceEnum.HIGH)
                .status(SubTask.SubTaskStatus.PENDING).build();

        when(intelligentInspection.executeSubTask(any(SubTask.class), any()))
                .thenReturn("系统巡检完成，所有指标正常。");

        String result = multiTaskExecutionNode.executeSubTask(subTask, dynamicContext);

        assertEquals(SubTask.SubTaskStatus.COMPLETED, subTask.getStatus());
        assertEquals("系统巡检完成，所有指标正常。", result);
        assertTrue(subTask.getLatencyMs() >= 0);
        verify(intelligentInspection, times(1)).executeSubTask(any(SubTask.class), any());
    }

    /**
     * TC-MTE-004: 未知 executorNode，抛出异常
     */
    @Test
    public void testExecuteSubTask_unknownNode_throwsException() throws Exception {
        SubTask subTask = SubTask.builder()
                .taskId("sub-x").taskIndex(1).totalTasks(1)
                .content("未知任务").executorNode("nonExistentNode")
                .status(SubTask.SubTaskStatus.PENDING).build();

        try {
            multiTaskExecutionNode.executeSubTask(subTask, dynamicContext);
            fail("应该抛出异常");
        } catch (Exception e) {
            assertEquals(SubTask.SubTaskStatus.FAILED, subTask.getStatus());
            assertNotNull(subTask.getErrorMessage());
            assertNotNull(subTask.getLatencyMs());
        }
    }

    /**
     * TC-MTE-007: buildSummaryPrompt 包含 originalMessage
     */
    @Test
    public void testBuildSummaryPrompt_containsOriginalMessage() throws Exception {
        SubTask task = SubTask.builder()
                .taskIndex(1).totalTasks(1).content("分析茅台")
                .status(SubTask.SubTaskStatus.COMPLETED).result("茅台技术面良好").build();

        Method method = MultiTaskExecutionNode.class.getDeclaredMethod(
                "buildSummaryPrompt", String.class, List.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(multiTaskExecutionNode, "分析茅台的走势", List.of(task));

        assertTrue("Prompt 应包含原始消息", prompt.contains("分析茅台的走势"));
        assertTrue("Prompt 应包含任务结果", prompt.contains("茅台技术面良好"));
        assertTrue("Prompt 应包含任务内容", prompt.contains("分析茅台"));
    }

    /**
     * TC-MTE-008: buildSummaryPrompt 包含失败任务的错误信息
     */
    @Test
    public void testBuildSummaryPrompt_containsFailedTaskError() throws Exception {
        SubTask task = SubTask.builder()
                .taskIndex(1).totalTasks(1).content("分析茅台")
                .status(SubTask.SubTaskStatus.FAILED).errorMessage("行情服务不可用").build();

        Method method = MultiTaskExecutionNode.class.getDeclaredMethod(
                "buildSummaryPrompt", String.class, List.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(multiTaskExecutionNode, "分析茅台的走势", List.of(task));

        assertTrue("Prompt 应包含错误信息", prompt.contains("行情服务不可用"));
        assertTrue("Prompt 应包含任务内容", prompt.contains("分析茅台"));
    }

    /**
     * TC-MTE-009: 任务成功时，result 和 latencyMs 被正确设置
     */
    @Test
    public void testExecuteSubTask_resultIsSet() throws Exception {
        SubTask subTask = SubTask.builder()
                .taskId("sub-1").taskIndex(1).totalTasks(1)
                .content("通用对话").intent(IntentTypeEnum.GENERAL_CHAT)
                .executorNode("generalChatNode").status(SubTask.SubTaskStatus.PENDING).build();

        when(generalChatNode.executeSubTask(any(SubTask.class), any())).thenReturn("对话结果");

        multiTaskExecutionNode.executeSubTask(subTask, dynamicContext);

        assertEquals("对话结果", subTask.getResult());
        assertNull(subTask.getErrorMessage());
        assertNotNull(subTask.getLatencyMs());
    }

    /**
     * TC-MTE-010: 任务失败时，errorMessage 被正确设置
     */
    @Test
    public void testExecuteSubTask_errorMessageIsSet() throws Exception {
        SubTask subTask = SubTask.builder()
                .taskId("sub-1").taskIndex(1).totalTasks(1)
                .content("通用对话").executorNode("generalChatNode")
                .status(SubTask.SubTaskStatus.PENDING).build();

        when(generalChatNode.executeSubTask(any(SubTask.class), any()))
                .thenThrow(new RuntimeException("服务异常"));

        try {
            multiTaskExecutionNode.executeSubTask(subTask, dynamicContext);
        } catch (Exception ignored) {
        }

        assertEquals("服务异常", subTask.getErrorMessage());
        assertNull(subTask.getResult());
    }

    /**
     * TC-MTE-011: 有依赖任务时注入依赖结果
     */
    @Test
    public void testBuildExecutionContext_injectsDependencyResult() throws Exception {
        SubTask dependencyTask = SubTask.builder()
                .taskId("sub-1")
                .content("先解释向量数据库")
                .result("向量数据库用于存储和检索向量表示。")
                .status(SubTask.SubTaskStatus.COMPLETED)
                .build();
        dynamicContext.setValue("subTaskResults", Map.of("sub-1", dependencyTask));

        SubTask currentTask = SubTask.builder()
                .taskId("sub-2")
                .content("基于 <$DEPENDENCY$ taskId=\"sub-1\" /> 继续介绍 RAG")
                .executorNode("generalChatNode")
                .dependsOn(List.of("sub-1"))
                .build();

        Method method = MultiTaskExecutionNode.class.getDeclaredMethod(
                "buildExecutionContext", SubTask.class, DefaultAutoAgentExecuteStrategyFactory.DynamicContext.class);
        method.setAccessible(true);

        String executableContent = (String) method.invoke(multiTaskExecutionNode, currentTask, dynamicContext);

        assertTrue(executableContent.contains("向量数据库用于存储和检索向量表示。"));
        assertFalse(executableContent.contains("<$DEPENDENCY$"));
    }

    /**
     * TC-MTE-012: 无依赖任务时保持原始内容
     */
    @Test
    public void testBuildExecutionContext_withoutDependency_keepsOriginalContent() throws Exception {
        SubTask currentTask = SubTask.builder()
                .taskId("sub-3")
                .content("直接介绍 Spring AI")
                .executorNode("generalChatNode")
                .dependsOn(List.of())
                .build();

        Method method = MultiTaskExecutionNode.class.getDeclaredMethod(
                "buildExecutionContext", SubTask.class, DefaultAutoAgentExecuteStrategyFactory.DynamicContext.class);
        method.setAccessible(true);

        String executableContent = (String) method.invoke(multiTaskExecutionNode, currentTask, dynamicContext);

        assertEquals("直接介绍 Spring AI", executableContent);
    }

    /**
     * TC-MTE-013: 汇总后保留已有 generalChatResponse
     */
    @Test
    public void testStoreSummaryResponse_preservesExistingGeneralChatResponse() throws Exception {
        dynamicContext.setValue("generalChatResponse", "子任务节点已经产出的通用回复");

        Method method = MultiTaskExecutionNode.class.getDeclaredMethod(
                "storeSummaryResponse",
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext.class,
                String.class);
        method.setAccessible(true);

        method.invoke(multiTaskExecutionNode, dynamicContext, "最终汇总回复");

        assertEquals("子任务节点已经产出的通用回复", dynamicContext.getValue("generalChatResponse"));
        assertEquals("最终汇总回复", dynamicContext.getValue("multiTaskSummaryResponse"));
    }
}
