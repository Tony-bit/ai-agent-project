package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.DecomposedTask;
import denny.ai.agent.domain.model.valobj.IntentRoutingResult;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.QueryDecompositionResult;
import denny.ai.agent.domain.model.valobj.RoutingExecutionMetrics;
import denny.ai.agent.domain.model.valobj.RoutingStageMetric;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.model.entity.RoutingConversationContext;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.chatmemory.ConversationContextProvider;
import denny.ai.agent.domain.service.runtime.RuntimeContextKeys;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class TaskRoutingSlotNodeTest {
    @Mock private IntentRoutingService intentRoutingService;
    @Mock private RoutingResultHandler routingResultHandler;
    @Mock private ConversationContextProvider conversationContextProvider;

    private TaskRoutingSlotNode node;
    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext context;

    @Before
    public void setUp() throws Exception {
        node = new TaskRoutingSlotNode();
        set(node, "intentRoutingService", intentRoutingService);
        set(node, "taskGraphValidator", new TaskGraphValidator());
        set(node, "routingResultHandler", routingResultHandler);
        set(node, "conversationContextProvider", conversationContextProvider);
        context = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        HashMap<String, AiAgentClientFlowConfigVO> configs = new HashMap<>();
        configs.put(AiClientTypeEnumVO.INTENT_ROUTING.getCode(),
                AiAgentClientFlowConfigVO.builder().clientId("routing").build());
        context.setAiAgentClientFlowConfigVOMap(configs);
        context.setValue(RoutingResultHandler.METRICS_KEY, RoutingExecutionMetrics.builder()
                .mode(IntentRoutingMode.SPLIT).totalPromptTokens(0).totalCompletionTokens(0).totalTokens(0)
                .estimated(false).stageMetrics(new ArrayList<>(List.of(
                        RoutingStageMetric.builder().stageName("query-decomposition").callIndex(0).build())))
                .build());
        context.setValue(QueryDecompositionNode.ROUTING_STARTED_AT_KEY, System.currentTimeMillis());
        when(conversationContextProvider.getSlotContext(anyString()))
                .thenReturn(RoutingConversationContext.builder().historyMessages(List.of()).build());
    }

    @Test
    public void routesTasksInIndexOrderAndUsesDefaultTaskType() throws Exception {
        DecomposedTask first = decomposed("sub-1", 1, 2);
        DecomposedTask second = decomposed("sub-2", 2, 2);
        QueryDecompositionResult decomposition = QueryDecompositionResult.builder()
                .multiTask(true).reasoning("two").taskList(List.of(second, first)).build();
        context.setValue(QueryDecompositionNode.DECOMPOSITION_RESULT_KEY, decomposition);
        IntentRoutingResult routing = IntentRoutingResult.builder()
                .intent(IntentTypeEnum.GENERAL_CHAT).confidence(ConfidenceEnum.HIGH).intentSpecificSlots(Map.of()).build();
        when(intentRoutingService.routeTaskIntentSlotsWithMetric(any(), any(), anyInt(), any(), any()))
                .thenAnswer(invocation -> new IntentRoutingService.RoutingCallResult<>(routing,
                        RoutingStageMetric.builder().stageName("task-routing-slot")
                                .taskId(invocation.getArgument(1)).callIndex(invocation.getArgument(2))
                                .promptTokens(1).completionTokens(1).totalTokens(2)
                                .estimatedTokens(true).success(true).build()));
        SubTask task1 = subTask(first);
        SubTask task2 = subTask(second);
        when(intentRoutingService.toSubTask(first, routing)).thenReturn(task1);
        when(intentRoutingService.toSubTask(second, routing)).thenReturn(task2);
        MultiIntentRoutingResult finalResult = MultiIntentRoutingResult.builder()
                .multiTask(true).needsClarification(false).taskList(List.of(task1, task2)).build();
        when(intentRoutingService.buildSplitResult(any(), any())).thenReturn(finalResult);

        node.doApply(ExecuteCommandEntity.builder().message("combined").sessionId("s").build(), context);

        InOrder order = inOrder(intentRoutingService);
        order.verify(intentRoutingService).routeTaskIntentSlotsWithMetric(eq("task sub-1"), eq("sub-1"), eq(1), any(), any());
        order.verify(intentRoutingService).routeTaskIntentSlotsWithMetric(eq("task sub-2"), eq("sub-2"), eq(2), any(), any());
        assertEquals(Integer.valueOf(0), task1.getTaskType());
        assertEquals(3, ((RoutingExecutionMetrics) context.getValue(RoutingResultHandler.METRICS_KEY)).getStageMetrics().size());
        verify(routingResultHandler).handle(any(), any(), eq(finalResult));
    }

    @Test
    public void fallsBackToGeneralChatWhenFinalSubTaskGraphIsInvalid() throws Exception {
        DecomposedTask first = decomposed("sub-1", 1, 2);
        DecomposedTask second = decomposed("sub-2", 2, 2);
        QueryDecompositionResult decomposition = QueryDecompositionResult.builder()
                .multiTask(true)
                .reasoning("two")
                .taskList(List.of(first, second))
                .build();
        context.setValue(QueryDecompositionNode.DECOMPOSITION_RESULT_KEY, decomposition);
        IntentRoutingResult routing = IntentRoutingResult.builder()
                .intent(IntentTypeEnum.PE_REASONING)
                .confidence(ConfidenceEnum.HIGH)
                .intentSpecificSlots(Map.of())
                .build();
        when(intentRoutingService.routeTaskIntentSlotsWithMetric(any(), any(), anyInt(), any(), any()))
                .thenAnswer(invocation -> new IntentRoutingService.RoutingCallResult<>(routing,
                        RoutingStageMetric.builder()
                                .stageName("task-routing-slot")
                                .taskId(invocation.getArgument(1))
                                .callIndex(invocation.getArgument(2))
                                .promptTokens(1)
                                .completionTokens(1)
                                .totalTokens(2)
                                .estimatedTokens(false)
                                .success(true)
                                .build()));

        SubTask valid = subTask(first);
        SubTask invalid = subTask(second);
        invalid.setDependsOn(List.of("missing"));
        when(intentRoutingService.toSubTask(first, routing)).thenReturn(valid);
        when(intentRoutingService.toSubTask(second, routing)).thenReturn(invalid);
        when(intentRoutingService.buildSplitResult(any(), any())).thenReturn(MultiIntentRoutingResult.builder()
                .multiTask(true)
                .needsClarification(false)
                .taskList(List.of(valid, invalid))
                .build());
        MultiIntentRoutingResult fallback = MultiIntentRoutingResult.builder()
                .multiTask(false)
                .needsClarification(false)
                .taskList(List.of(SubTask.builder()
                        .taskId("fallback-1")
                        .taskIndex(1)
                        .totalTasks(1)
                        .content("通用对话")
                        .intent(IntentTypeEnum.GENERAL_CHAT)
                        .confidence(ConfidenceEnum.MEDIUM)
                        .executorNode("generalChatNode")
                        .slots(Map.of())
                        .dependsOn(List.of())
                        .taskType(0)
                        .status(SubTask.SubTaskStatus.PENDING)
                        .build()))
                .build();
        when(intentRoutingService.fallbackMultiIntentResult(any())).thenReturn(fallback);

        node.doApply(ExecuteCommandEntity.builder().message("combined").sessionId("s").build(), context);

        ArgumentCaptor<MultiIntentRoutingResult> resultCaptor =
                ArgumentCaptor.forClass(MultiIntentRoutingResult.class);
        verify(routingResultHandler).handle(any(), any(), resultCaptor.capture());
        assertSame(fallback, resultCaptor.getValue());
        assertFalse(resultCaptor.getValue().getMultiTask());
        assertEquals(IntentTypeEnum.GENERAL_CHAT, resultCaptor.getValue().getTaskList().get(0).getIntent());
    }

    @Test
    public void usesPreparedHistoryForEverySlotRoutingCall() throws Exception {
        List<String> preparedHistory = List.of("user: first", "assistant: second");
        context.setValue(RuntimeContextKeys.RECENT_HISTORY_MESSAGES, preparedHistory);
        DecomposedTask first = decomposed("sub-1", 1, 2);
        DecomposedTask second = decomposed("sub-2", 2, 2);
        QueryDecompositionResult decomposition = QueryDecompositionResult.builder()
                .multiTask(true).reasoning("two").taskList(List.of(first, second)).build();
        context.setValue(QueryDecompositionNode.DECOMPOSITION_RESULT_KEY, decomposition);
        IntentRoutingResult routing = IntentRoutingResult.builder()
                .intent(IntentTypeEnum.GENERAL_CHAT).confidence(ConfidenceEnum.HIGH).intentSpecificSlots(Map.of()).build();
        when(intentRoutingService.routeTaskIntentSlotsWithMetric(any(), any(), anyInt(), any(), any()))
                .thenAnswer(invocation -> new IntentRoutingService.RoutingCallResult<>(routing,
                        RoutingStageMetric.builder().stageName("task-routing-slot")
                                .taskId(invocation.getArgument(1)).callIndex(invocation.getArgument(2))
                                .success(true).build()));
        when(intentRoutingService.toSubTask(first, routing)).thenReturn(subTask(first));
        when(intentRoutingService.toSubTask(second, routing)).thenReturn(subTask(second));
        MultiIntentRoutingResult finalResult = MultiIntentRoutingResult.builder()
                .multiTask(true).needsClarification(false).taskList(List.of(subTask(first), subTask(second))).build();
        when(intentRoutingService.buildSplitResult(any(), any())).thenReturn(finalResult);

        node.doApply(ExecuteCommandEntity.builder().message("combined").sessionId("s").build(), context);

        verify(intentRoutingService).routeTaskIntentSlotsWithMetric(eq("task sub-1"), eq("sub-1"), eq(1), eq(preparedHistory), any());
        verify(intentRoutingService).routeTaskIntentSlotsWithMetric(eq("task sub-2"), eq("sub-2"), eq(2), eq(preparedHistory), any());
        verify(conversationContextProvider, never()).getSlotContext(anyString());
    }

    private DecomposedTask decomposed(String id, int index, int total) {
        return DecomposedTask.builder().taskId(id).taskIndex(index).totalTasks(total)
                .content("task " + id).dependsOn(List.of()).build();
    }

    private SubTask subTask(DecomposedTask task) {
        return SubTask.builder().taskId(task.getTaskId()).taskIndex(task.getTaskIndex())
                .totalTasks(task.getTotalTasks()).content(task.getContent()).dependsOn(List.of())
                .intent(IntentTypeEnum.GENERAL_CHAT).confidence(ConfidenceEnum.HIGH)
                .executorNode("generalChatNode").slots(Map.of()).taskType(0)
                .status(SubTask.SubTaskStatus.PENDING).build();
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
