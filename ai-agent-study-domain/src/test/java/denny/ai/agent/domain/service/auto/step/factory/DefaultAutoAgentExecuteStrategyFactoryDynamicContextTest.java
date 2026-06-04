package denny.ai.agent.domain.service.auto.step.factory;

import denny.ai.agent.domain.model.valobj.SubTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAutoAgentExecuteStrategyFactoryDynamicContextTest {

    @Test
    void shouldClearOnlyMultiTaskContextKeys() {
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext.builder().build();

        dynamicContext.setValue("taskList", "tasks");
        dynamicContext.setValue("originalMessage", "hello");
        dynamicContext.setValue("generalChatResponse", "summary");

        dynamicContext.clearMultiTaskContext();

        assertNull(dynamicContext.getValue("taskList"));
        assertNull(dynamicContext.getValue("originalMessage"));
        assertEquals("summary", dynamicContext.getValue("generalChatResponse"));
    }

    @Test
    void shouldStoreAndClearSubTaskResults() {
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext.builder().build();

        SubTask subTask = SubTask.builder().taskId("sub-1").content("task").build();
        dynamicContext.putSubTaskResult("sub-1", subTask);

        assertEquals(subTask, dynamicContext.getSubTaskResult("sub-1"));
        assertFalse(dynamicContext.getAllSubTaskResults().isEmpty());

        dynamicContext.clearSubTaskResults();

        assertNull(dynamicContext.getSubTaskResult("sub-1"));
        assertTrue(dynamicContext.getAllSubTaskResults().isEmpty());
    }
}
