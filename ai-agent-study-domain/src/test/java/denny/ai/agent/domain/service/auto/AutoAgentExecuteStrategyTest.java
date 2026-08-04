package denny.ai.agent.domain.service.auto;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.runtime.RuntimeContextAssembler;
import denny.ai.agent.domain.service.observability.ObservabilityService;
import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.model.valobj.runtime.TurnRuntimeContext;
import denny.ai.agent.domain.service.runtime.RetryRuntimeContextHolder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AutoAgentExecuteStrategyTest {

    private AutoAgentExecuteStrategy autoAgentExecuteStrategy;

    @Mock
    private DefaultAutoAgentExecuteStrategyFactory defaultAutoAgentExecuteStrategyFactory;

    @Mock
    private RuntimeContextAssembler runtimeContextAssembler;

    @Mock
    private ObservabilityService observabilityService;

    @Mock
    private StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> strategyHandler;

    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext capturedContext;
    private TurnRuntimeContext turnContext;

    @Before
    public void setUp() throws Exception {
        autoAgentExecuteStrategy = new AutoAgentExecuteStrategy();
        inject("defaultAutoAgentExecuteStrategyFactory", defaultAutoAgentExecuteStrategyFactory);
        inject("runtimeContextAssembler", runtimeContextAssembler);
        inject("observabilityService", observabilityService);

        when(defaultAutoAgentExecuteStrategyFactory.armoryStrategyHandler()).thenReturn(strategyHandler);
        when(observabilityService.startTrace(any(), any(), any())).thenReturn("root-trace-001");
        turnContext = TurnRuntimeContext.builder()
                .sessionId("session-001")
                .traceId("trace-001")
                .build();
        when(runtimeContextAssembler.prepare(any(), any())).thenReturn(turnContext);
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
        assertEquals("root-trace-001", capturedContext.getTraceId());
    }

    @Test
    public void executeStartsAndEndsRootTraceExactlyOnceOnSuccess() throws Exception {
        autoAgentExecuteStrategy.execute(request(), new ResponseBodyEmitter());

        verify(observabilityService, times(1)).startTrace(eq("session-001"), eq("define vector database"), any());
        verify(observabilityService, times(1)).endTrace(eq("root-trace-001"), eq("ok"), any());
    }

    @Test
    public void prepareFailureStillEndsRootTraceExactlyOnce() throws Exception {
        doThrow(new IllegalStateException("prepare failed"))
                .when(runtimeContextAssembler).prepare(any(), any());

        autoAgentExecuteStrategy.execute(request(), new ResponseBodyEmitter());

        verify(observabilityService, times(1)).startTrace(eq("session-001"), eq("define vector database"), any());
        verify(observabilityService, times(1)).endTrace(eq("root-trace-001"), eq(""), any());
        verify(runtimeContextAssembler, never()).afterTurn(any(), any(), any());
    }

    @Test
    public void handlerFailureEndsRootTraceExactlyOnce() throws Exception {
        when(strategyHandler.apply(any(), any())).thenThrow(new RuntimeException("handler failed"));

        autoAgentExecuteStrategy.execute(request(), new ResponseBodyEmitter());

        verify(observabilityService, times(1)).endTrace(eq("root-trace-001"), eq(""), any());
    }

    @Test
    public void bindsRetryContextForHandlerAndClearsBeforeAfterTurn() throws Exception {
        when(strategyHandler.apply(any(), any())).thenAnswer(invocation -> {
            RetryRuntimeContext current = RetryRuntimeContextHolder.current();
            assertNotNull(current);
            assertEquals("session-001", current.getSessionId());
            assertEquals("trace-001", current.getTraceId());
            return "ok";
        });
        doAnswer(invocation -> {
            assertNull(RetryRuntimeContextHolder.current());
            assertSame(turnContext, invocation.getArgument(2));
            return null;
        }).when(runtimeContextAssembler).afterTurn(any(), any(), any());

        autoAgentExecuteStrategy.execute(request(), new ResponseBodyEmitter());

        assertNull(RetryRuntimeContextHolder.current());
        verify(runtimeContextAssembler, times(1)).afterTurn(any(), any(), eq(turnContext));
    }

    @Test
    public void handlerFailureStillClearsContextAndRunsAfterTurnOnce() throws Exception {
        RuntimeException failure = new RuntimeException("handler failed");
        when(strategyHandler.apply(any(), any())).thenAnswer(invocation -> {
            assertNotNull(RetryRuntimeContextHolder.current());
            throw failure;
        });
        doAnswer(invocation -> {
            assertNull(RetryRuntimeContextHolder.current());
            return null;
        }).when(runtimeContextAssembler).afterTurn(any(), any(), any());

        autoAgentExecuteStrategy.execute(request(), new ResponseBodyEmitter());

        assertNull(RetryRuntimeContextHolder.current());
        verify(runtimeContextAssembler, times(1)).afterTurn(any(), any(), eq(turnContext));
    }

    @Test
    public void afterTurnFailureDoesNotReplaceHandlerFailure() throws Exception {
        RuntimeException handlerFailure = new RuntimeException("handler failed");
        RuntimeException cleanupFailure = new RuntimeException("cleanup failed");
        when(strategyHandler.apply(any(), any())).thenThrow(handlerFailure);
        doThrow(cleanupFailure).when(runtimeContextAssembler).afterTurn(any(), any(), any());

        autoAgentExecuteStrategy.execute(request(), new ResponseBodyEmitter());

        assertEquals(1, handlerFailure.getSuppressed().length);
        assertSame(cleanupFailure, handlerFailure.getSuppressed()[0]);
    }

    @Test
    public void outerStrategyCompletesEmitterExactlyOnceOnSuccess() throws Exception {
        CountingEmitter emitter = new CountingEmitter();

        autoAgentExecuteStrategy.execute(request(), emitter);

        assertEquals(1, emitter.completeCount);
    }

    @Test
    public void outerStrategyCompletesEmitterExactlyOnceOnFailure() throws Exception {
        CountingEmitter emitter = new CountingEmitter();
        when(strategyHandler.apply(any(), any())).thenThrow(new RuntimeException("handler failed"));

        autoAgentExecuteStrategy.execute(request(), emitter);

        assertEquals(1, emitter.completeCount);
    }

    private ExecuteCommandEntity request() {
        return ExecuteCommandEntity.builder()
                .message("define vector database")
                .sessionId("session-001")
                .userId("user-001")
                .build();
    }

    private void inject(String fieldName, Object value) throws Exception {
        var field = AutoAgentExecuteStrategy.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(autoAgentExecuteStrategy, value);
    }

    private static class CountingEmitter extends ResponseBodyEmitter {
        private int completeCount;

        @Override
        public synchronized void complete() {
            completeCount++;
        }
    }
}
