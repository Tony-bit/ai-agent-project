package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.trading.domain.config.TradingAgentProperties;
import denny.ai.agent.trading.domain.execution.NodeExecutionResult;
import denny.ai.agent.trading.domain.execution.NodeExecutionScope;
import denny.ai.agent.trading.domain.execution.NodeExecutionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingNodeInvokerTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void should_expose_single_configured_constructor_for_spring() {
        Constructor<?>[] candidates = Arrays.stream(TradingNodeInvoker.class.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .toArray(Constructor<?>[]::new);

        assertNotNull(candidates);
        assertEquals(1, candidates.length);
        assertArrayEquals(
                new Class<?>[]{ExecutorService.class, TradingAgentProperties.class},
                candidates[0].getParameterTypes());
        Qualifier executorQualifier = candidates[0].getParameters()[0].getAnnotation(Qualifier.class);
        assertNotNull(executorQualifier);
        assertEquals("tradingTaskExecutor", executorQualifier.value());
    }

    @Test
    void should_mark_scope_timed_out_before_interrupting_future() {
        TradingAgentProperties properties = new TradingAgentProperties();
        properties.setNodeTimeout(Duration.ofMillis(50));
        TradingNodeInvoker invoker = new TradingNodeInvoker(executor, properties);
        NodeExecutionScope scope = new NodeExecutionScope(
                Instant.now().plusMillis(50), () -> false);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        NodeExecutionResult<String> result = invoker.invokeScoped("slow", scope, () -> {
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            }
            return "late";
        });

        assertEquals(NodeExecutionState.TIMED_OUT, scope.state());
        assertFalse(result.isSuccess());
        long deadline = System.currentTimeMillis() + 1_000L;
        while (!interrupted.get() && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertTrue(interrupted.get());
    }

    @Test
    void should_return_success_with_running_scope_before_commit() {
        TradingAgentProperties properties = new TradingAgentProperties();
        TradingNodeInvoker invoker = new TradingNodeInvoker(executor, properties);
        NodeExecutionScope scope = new NodeExecutionScope(
                Instant.now().plusSeconds(1), () -> false);

        NodeExecutionResult<String> result = invoker.invokeScoped("fast", scope, () -> "ok");

        assertTrue(result.isSuccess());
        assertEquals(NodeExecutionState.RUNNING, scope.state());
    }
}
