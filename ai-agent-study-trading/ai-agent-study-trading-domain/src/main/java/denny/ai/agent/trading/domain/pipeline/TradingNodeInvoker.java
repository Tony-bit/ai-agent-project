package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.config.TradingAgentProperties;
import denny.ai.agent.trading.domain.execution.NodeExecutionResult;
import denny.ai.agent.trading.domain.execution.NodeExecutionScope;
import denny.ai.agent.domain.service.sse.SseEventSink;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.time.Instant;

@Component
public class TradingNodeInvoker {

    private final ExecutorService tradingTaskExecutor;
    private final TradingAgentProperties properties;

    public TradingNodeInvoker(@Qualifier("tradingTaskExecutor") ExecutorService tradingTaskExecutor) {
        this(tradingTaskExecutor, new TradingAgentProperties());
    }

    public TradingNodeInvoker(ExecutorService tradingTaskExecutor,
                              TradingAgentProperties properties) {
        this.tradingTaskExecutor = tradingTaskExecutor;
        this.properties = properties;
        this.properties.validate();
    }

    public <T> T invoke(String nodeName, Callable<T> nodeAction) {
        Future<T> future = tradingTaskExecutor.submit(nodeAction);
        try {
            return future.get(properties.getNodeTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TradingPipelineException("节点执行超时: " + nodeName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new TradingPipelineException("节点执行被中断: " + nodeName, e);
        } catch (Exception e) {
            throw new TradingPipelineException("节点执行异常: " + nodeName, e);
        }
    }

    public NodeExecutionScope newScope(TradingStateContext context) {
        SseEventSink sink = context == null || context.getDynamicContext() == null
                ? null : context.getDynamicContext().getValue("sseEventSink");
        return new NodeExecutionScope(Instant.now().plus(properties.getNodeTimeout()),
                () -> sink != null && !sink.shouldContinue());
    }

    public <T> NodeExecutionResult<T> invokeScoped(String nodeName,
                                                   NodeExecutionScope scope,
                                                   Callable<T> nodeAction) {
        if (scope.isRequestCancelled()) {
            return NodeExecutionResult.cancelled(
                    new TradingPipelineException("节点执行已取消: " + nodeName), scope);
        }
        Future<T> future = tradingTaskExecutor.submit(nodeAction);
        try {
            long remainingMillis = Math.max(1L,
                    java.time.Duration.between(Instant.now(), scope.deadline()).toMillis());
            T value = future.get(remainingMillis, TimeUnit.MILLISECONDS);
            if (scope.isRequestCancelled()) {
                return NodeExecutionResult.cancelled(
                        new TradingPipelineException("节点执行已取消: " + nodeName), scope);
            }
            if (scope.isDeadlineElapsed()) {
                return NodeExecutionResult.timedOut(
                        new TradingPipelineException("节点执行超时: " + nodeName), scope);
            }
            return NodeExecutionResult.success(value, scope);
        } catch (TimeoutException e) {
            scope.markTimedOut();
            future.cancel(true);
            return NodeExecutionResult.timedOut(e, scope);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scope.markCancelled();
            future.cancel(true);
            return NodeExecutionResult.cancelled(e, scope);
        } catch (Exception e) {
            scope.markFailed();
            return NodeExecutionResult.failed(e, scope);
        }
    }

    public <T> T invokeIfOpen(TradingStateContext context, String nodeName, Callable<T> nodeAction) {
        if (!TradingPipelineSseGuard.shouldContinue(context)) {
            return null;
        }
        return invoke(nodeName, () -> {
            if (!TradingPipelineSseGuard.shouldContinue(context)) {
                return null;
            }
            return nodeAction.call();
        });
    }
}
