package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.trading.domain.config.TradingStateContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class TradingNodeInvoker {

    private static final int NODE_TIMEOUT_SECONDS = 180;

    private final ExecutorService tradingTaskExecutor;

    public TradingNodeInvoker(@Qualifier("tradingTaskExecutor") ExecutorService tradingTaskExecutor) {
        this.tradingTaskExecutor = tradingTaskExecutor;
    }

    public <T> T invoke(String nodeName, Callable<T> nodeAction) {
        Future<T> future = tradingTaskExecutor.submit(nodeAction);
        try {
            return future.get(NODE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
