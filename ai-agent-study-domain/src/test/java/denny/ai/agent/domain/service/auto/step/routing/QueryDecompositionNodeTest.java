package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.DecomposedTask;
import denny.ai.agent.domain.model.valobj.QueryDecompositionResult;
import denny.ai.agent.domain.model.valobj.RoutingExecutionMetrics;
import denny.ai.agent.domain.model.valobj.RoutingStageMetric;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.model.entity.RoutingConversationContext;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.chatmemory.ConversationContextProvider;
import denny.ai.agent.domain.service.runtime.RuntimeContextKeys;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class QueryDecompositionNodeTest {
    @Mock private IntentRoutingService intentRoutingService;
    @Mock private ConversationContextProvider conversationContextProvider;
    @Mock private TaskRoutingSlotNode taskRoutingSlotNode;

    private QueryDecompositionNode node;
    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext context;

    @Before
    public void setUp() throws Exception {
        node = new QueryDecompositionNode();
        set(node, "intentRoutingService", intentRoutingService);
        set(node, "taskGraphValidator", new TaskGraphValidator());
        set(node, "conversationContextProvider", conversationContextProvider);
        set(node, "taskRoutingSlotNode", taskRoutingSlotNode);
        context = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        HashMap<String, AiAgentClientFlowConfigVO> configs = new HashMap<>();
        configs.put(AiClientTypeEnumVO.INTENT_ROUTING.getCode(),
                AiAgentClientFlowConfigVO.builder().clientId("routing").build());
        context.setAiAgentClientFlowConfigVOMap(configs);
        when(conversationContextProvider.getDecompositionContext(anyString()))
                .thenReturn(RoutingConversationContext.builder().historyMessages(List.of()).build());
    }

    @Test
    public void writesDecompositionAndMetricsThenRoutes() throws Exception {
        QueryDecompositionResult result = QueryDecompositionResult.builder()
                .multiTask(false)
                .reasoning("single")
                .taskList(List.of(DecomposedTask.builder()
                        .taskId("sub-1").taskIndex(1).totalTasks(1).content("task").dependsOn(List.of()).build()))
                .build();
        RoutingStageMetric metric = RoutingStageMetric.builder()
                .stageName("query-decomposition").callIndex(0).promptTokens(1)
                .completionTokens(1).totalTokens(2).estimatedTokens(true).success(true).build();
        when(intentRoutingService.decomposeQueryWithMetric(any(), any(), any()))
                .thenReturn(new IntentRoutingService.RoutingCallResult<>(result, metric));

        node.doApply(ExecuteCommandEntity.builder().message("task").sessionId("s").build(), context);

        assertEquals(result, context.getValue(QueryDecompositionNode.DECOMPOSITION_RESULT_KEY));
        assertNotNull(context.getValue(RoutingResultHandler.METRICS_KEY));
        verify(taskRoutingSlotNode).apply(any(), any());
    }

    @Test
    public void fallsBackToOriginalQueryWhenFirstStageTaskGraphIsInvalid() throws Exception {
        QueryDecompositionResult invalid = QueryDecompositionResult.builder()
                .multiTask(true)
                .reasoning("invalid")
                .taskList(List.of(
                        DecomposedTask.builder()
                                .taskId("sub-1")
                                .taskIndex(1)
                                .totalTasks(2)
                                .content("first")
                                .dependsOn(List.of())
                                .build(),
                        DecomposedTask.builder()
                                .taskId("sub-1")
                                .taskIndex(2)
                                .totalTasks(2)
                                .content("second")
                                .dependsOn(List.of())
                                .build()))
                .build();
        QueryDecompositionResult fallback = QueryDecompositionResult.builder()
                .multiTask(false)
                .reasoning("Task graph validation failed: duplicate taskId: sub-1")
                .taskList(List.of(DecomposedTask.builder()
                        .taskId("fallback-1")
                        .taskIndex(1)
                        .totalTasks(1)
                        .content("task")
                        .dependsOn(List.of())
                        .build()))
                .build();
        RoutingStageMetric metric = RoutingStageMetric.builder()
                .stageName("query-decomposition")
                .callIndex(0)
                .promptTokens(1)
                .completionTokens(1)
                .totalTokens(2)
                .estimatedTokens(false)
                .success(true)
                .build();
        when(intentRoutingService.decomposeQueryWithMetric(any(), any(), any()))
                .thenReturn(new IntentRoutingService.RoutingCallResult<>(invalid, metric));
        when(intentRoutingService.fallbackDecomposition(eq("task"), contains("Task graph validation failed")))
                .thenReturn(fallback);

        node.doApply(ExecuteCommandEntity.builder().message("task").sessionId("s").build(), context);

        assertEquals(fallback, context.getValue(QueryDecompositionNode.DECOMPOSITION_RESULT_KEY));
        RoutingExecutionMetrics metrics = context.getValue(RoutingResultHandler.METRICS_KEY);
        assertFalse(metrics.getStageMetrics().get(0).getSuccess());
        assertEquals("duplicate taskId: sub-1", metrics.getStageMetrics().get(0).getErrorMessage());
        verify(taskRoutingSlotNode).apply(any(), any());
    }

    @Test
    public void usesPreparedHistoryWithoutLoadingConversationHistory() throws Exception {
        List<String> preparedHistory = List.of("user: first", "assistant: second");
        context.setValue(RuntimeContextKeys.RECENT_HISTORY_MESSAGES, preparedHistory);
        QueryDecompositionResult result = QueryDecompositionResult.builder()
                .multiTask(false)
                .reasoning("single")
                .taskList(List.of(DecomposedTask.builder()
                        .taskId("sub-1").taskIndex(1).totalTasks(1).content("task").dependsOn(List.of()).build()))
                .build();
        RoutingStageMetric metric = RoutingStageMetric.builder()
                .stageName("query-decomposition").callIndex(0).success(true).build();
        when(intentRoutingService.decomposeQueryWithMetric(any(), any(), any()))
                .thenReturn(new IntentRoutingService.RoutingCallResult<>(result, metric));

        node.doApply(ExecuteCommandEntity.builder().message("task").sessionId("s").build(), context);

        verify(intentRoutingService).decomposeQueryWithMetric(eq("task"), eq(preparedHistory), any());
        verify(conversationContextProvider, never()).getDecompositionContext(anyString());
    }

    @Test
    public void fallsBackToLegacyHistoryWhenPreparedHistoryKeyIsAbsent() throws Exception {
        QueryDecompositionResult result = QueryDecompositionResult.builder()
                .multiTask(false)
                .reasoning("single")
                .taskList(List.of(DecomposedTask.builder()
                        .taskId("sub-1").taskIndex(1).totalTasks(1).content("task").dependsOn(List.of()).build()))
                .build();
        RoutingStageMetric metric = RoutingStageMetric.builder()
                .stageName("query-decomposition").callIndex(0).success(true).build();
        when(conversationContextProvider.getDecompositionContext("s"))
                .thenReturn(RoutingConversationContext.builder().historyMessages(List.of("user: legacy")).build());
        when(intentRoutingService.decomposeQueryWithMetric(any(), any(), any()))
                .thenReturn(new IntentRoutingService.RoutingCallResult<>(result, metric));

        node.doApply(ExecuteCommandEntity.builder().message("task").sessionId("s").build(), context);

        verify(intentRoutingService).decomposeQueryWithMetric(eq("task"), eq(List.of("user: legacy")), any());
        verify(conversationContextProvider).getDecompositionContext("s");
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
