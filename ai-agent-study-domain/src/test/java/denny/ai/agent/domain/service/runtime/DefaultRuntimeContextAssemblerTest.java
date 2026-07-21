package denny.ai.agent.domain.service.runtime;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.runtime.SessionRuntimeContext;
import denny.ai.agent.domain.model.valobj.runtime.TurnRuntimeContext;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DefaultRuntimeContextAssemblerTest {

    @Mock
    private AgentRuntimeConfigCache agentRuntimeConfigCache;

    @Mock
    private SessionRuntimeContextManager sessionRuntimeContextManager;

    private DefaultRuntimeContextAssembler assembler;

    @Before
    public void setUp() throws Exception {
        assembler = new DefaultRuntimeContextAssembler();
        set(assembler, "agentRuntimeConfigCache", agentRuntimeConfigCache);
        set(assembler, "sessionRuntimeContextManager", sessionRuntimeContextManager);
    }

    @Test
    public void preparesIntentRoutingContextAndCompatibilityKeys() {
        Map<String, AiAgentClientFlowConfigVO> flowConfig = Map.of("intent",
                AiAgentClientFlowConfigVO.builder().clientId("routing").build());
        SessionRuntimeContext sessionContext = SessionRuntimeContext.builder()
                .sessionId("s1")
                .userId("u1")
                .recentHistoryMessages(List.of("user: hello"))
                .build();
        when(agentRuntimeConfigCache.getIntentRoutingConfig()).thenReturn(flowConfig);
        when(sessionRuntimeContextManager.getOrLoad("s1", "u1")).thenReturn(sessionContext);
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setTraceId("trace-1");
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .sessionId("s1")
                .userId("u1")
                .message("hello")
                .build();

        TurnRuntimeContext turnContext = assembler.prepare(request, dynamicContext);

        assertSame(sessionContext, turnContext.getSessionRuntimeContext());
        assertSame(flowConfig, dynamicContext.getAiAgentClientFlowConfigVOMap());
        assertSame(flowConfig, dynamicContext.getValue(RuntimeContextKeys.FLOW_CONFIG_MAP));
        assertEquals(List.of("user: hello"), dynamicContext.getValue(RuntimeContextKeys.RECENT_HISTORY_MESSAGES));
        assertSame(turnContext, dynamicContext.getValue(RuntimeContextKeys.TURN_CONTEXT));
        assertSame(sessionContext, dynamicContext.getValue(RuntimeContextKeys.SESSION_CONTEXT));
        verify(agentRuntimeConfigCache).getIntentRoutingConfig();
    }

    @Test
    public void preparesExplicitAgentConfigWhenAiAgentIdExists() {
        Map<String, AiAgentClientFlowConfigVO> flowConfig = Map.of("step",
                AiAgentClientFlowConfigVO.builder().clientId("step").build());
        SessionRuntimeContext sessionContext = SessionRuntimeContext.builder()
                .sessionId("s1")
                .userId("u1")
                .build();
        when(agentRuntimeConfigCache.getAgentFlowConfig("123")).thenReturn(flowConfig);
        when(sessionRuntimeContextManager.getOrLoad("s1", "u1")).thenReturn(sessionContext);
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .aiAgentId("123")
                .sessionId("s1")
                .userId("u1")
                .message("run")
                .build();

        assembler.prepare(request, dynamicContext);

        assertSame(flowConfig, dynamicContext.getAiAgentClientFlowConfigVOMap());
        verify(agentRuntimeConfigCache).getAgentFlowConfig("123");
    }

    @Test
    public void refreshesSessionRuntimeContextAfterTurn() {
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .sessionId("s1")
                .userId("u1")
                .build();

        assembler.afterTurn(request, new DefaultAutoAgentExecuteStrategyFactory.DynamicContext(), null);

        verify(sessionRuntimeContextManager).refresh("s1", "u1");
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
