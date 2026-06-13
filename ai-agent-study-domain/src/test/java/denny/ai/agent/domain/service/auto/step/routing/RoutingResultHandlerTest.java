package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
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
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class RoutingResultHandlerTest {
    @Mock private Step1AnalyzerNode step1AnalyzerNode;
    @Mock private IntelligentInspection intelligentInspection;
    @Mock private GeneralChatNode generalChatNode;
    @Mock private MultiTaskExecutionNode multiTaskExecutionNode;
    @Mock private ApplicationContext applicationContext;

    private RoutingResultHandler handler;
    private ExecuteCommandEntity request;
    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext context;

    @Before
    public void setUp() {
        handler = new RoutingResultHandler(step1AnalyzerNode, intelligentInspection, generalChatNode,
                multiTaskExecutionNode, applicationContext);
        request = ExecuteCommandEntity.builder().message("original").sessionId("session").build();
        context = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
    }

    @Test
    public void writesSingleTaskContextAndDispatches() throws Exception {
        MultiIntentRoutingResult result = result(false, List.of(task("sub-1", 1, 1, IntentTypeEnum.PE_REASONING)));

        handler.handle(request, context, result);

        assertEquals(IntentTypeEnum.PE_REASONING, context.getValue(RoutingResultHandler.RECOGNIZED_INTENT_KEY));
        assertNotNull(context.getValue(RoutingResultHandler.ROUTING_RESULT_KEY));
        verify(step1AnalyzerNode).apply(any(), any());
    }

    @Test
    public void writesMultiTaskContextAndDispatches() throws Exception {
        MultiIntentRoutingResult result = result(true, List.of(
                task("sub-1", 1, 2, IntentTypeEnum.PE_REASONING),
                task("sub-2", 2, 2, IntentTypeEnum.GENERAL_CHAT)));

        handler.handle(request, context, result);

        assertEquals("original", context.getValue(MultiTaskExecutionNode.ORIGINAL_MESSAGE_KEY));
        verify(multiTaskExecutionNode).apply(any(), any());
    }

    @Test
    public void preservesClarificationContract() throws Exception {
        MultiIntentRoutingResult result = MultiIntentRoutingResult.builder()
                .needsClarification(true)
                .missingInfo(List.of("stockCode"))
                .clarificationPrompt("provide stock code")
                .build();

        assertEquals("provide stock code", handler.handle(request, context, result));
        assertEquals(List.of("stockCode"), context.getValue("missingInfo"));
    }

    private MultiIntentRoutingResult result(boolean multiTask, List<SubTask> tasks) {
        return MultiIntentRoutingResult.builder()
                .multiTask(multiTask)
                .needsClarification(false)
                .reasoning("reason")
                .taskList(tasks)
                .build();
    }

    private SubTask task(String id, int index, int total, IntentTypeEnum intent) {
        return SubTask.builder()
                .taskId(id)
                .taskIndex(index)
                .totalTasks(total)
                .content("task")
                .intent(intent)
                .confidence(ConfidenceEnum.HIGH)
                .executorNode("node")
                .slots(Map.of("intentSpecificSlots", Map.of()))
                .dependsOn(List.of())
                .taskType(0)
                .status(SubTask.SubTaskStatus.PENDING)
                .build();
    }
}
