package denny.ai.agent.domain.service.auto;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.runtime.RuntimeContextAssembler;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AutoAgentExecuteStrategyTest {

    private AutoAgentExecuteStrategy autoAgentExecuteStrategy;

    @Mock
    private DefaultAutoAgentExecuteStrategyFactory defaultAutoAgentExecuteStrategyFactory;

    @Mock
    private RuntimeContextAssembler runtimeContextAssembler;

    @Mock
    private StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> strategyHandler;

    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext capturedContext;

    @Before
    public void setUp() throws Exception {
        autoAgentExecuteStrategy = new AutoAgentExecuteStrategy();
        inject("defaultAutoAgentExecuteStrategyFactory", defaultAutoAgentExecuteStrategyFactory);
        inject("runtimeContextAssembler", runtimeContextAssembler);

        when(defaultAutoAgentExecuteStrategyFactory.armoryStrategyHandler()).thenReturn(strategyHandler);
        when(strategyHandler.apply(any(), any())).thenAnswer(invocation -> {
            capturedContext = invocation.getArgument(1);
            return "ok";
        });
    }

    @Test
    public void testExecute_ShouldPropagateSessionIdAndUserIdIntoDynamicContext() throws Exception {
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .message("define vector database")
                .sessionId("session-001")
                .userId("user-001")
                .build();

        autoAgentExecuteStrategy.execute(request, new ResponseBodyEmitter());

        assertNotNull(capturedContext);
        assertEquals("session-001", capturedContext.getValue("sessionId"));
        assertEquals("user-001", capturedContext.getValue("userId"));
    }

    @Test
    public void testExecute_ShouldPrepareRuntimeContextBeforeHandlerChain() throws Exception {
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .message("define vector database")
                .sessionId("session-001")
                .userId("user-001")
                .build();

        autoAgentExecuteStrategy.execute(request, new ResponseBodyEmitter());

        org.mockito.Mockito.verify(runtimeContextAssembler).prepare(org.mockito.Mockito.eq(request), any());
        assertNotNull(capturedContext.getTraceId());
    }

    private void inject(String fieldName, Object value) throws Exception {
        var field = AutoAgentExecuteStrategy.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(autoAgentExecuteStrategy, value);
    }
}
