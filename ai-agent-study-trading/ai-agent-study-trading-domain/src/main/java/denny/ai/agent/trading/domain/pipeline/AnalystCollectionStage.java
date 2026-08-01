package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import com.alibaba.fastjson.JSON;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingDriver;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.config.TradingAgentProperties;
import denny.ai.agent.trading.domain.node.FundamentalAnalystNode;
import denny.ai.agent.trading.domain.node.NewsAnalystNode;
import denny.ai.agent.trading.domain.node.SentimentAnalystNode;
import denny.ai.agent.trading.domain.node.TechnicalAnalystNode;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import denny.ai.agent.trading.domain.execution.NodeExecutionResult;
import denny.ai.agent.trading.domain.execution.NodeExecutionScope;
import denny.ai.agent.trading.domain.execution.NodeResultCommitter;
import denny.ai.agent.trading.domain.execution.NodeCommitResult;
import denny.ai.agent.trading.domain.guard.DataSanityGuard;
import denny.ai.agent.trading.domain.signal.DecisionSignalShadowService;
import denny.ai.agent.trading.domain.signal.V2DecisionSignalFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
@Order(10)
public class AnalystCollectionStage implements TradingStage {

    private final FundamentalAnalystNode fundamentalAnalystNode;
    private final TechnicalAnalystNode technicalAnalystNode;
    private final SentimentAnalystNode sentimentAnalystNode;
    private final NewsAnalystNode newsAnalystNode;
    private final ExecutorService tradingTaskExecutor;
    private final DataSanityGuard dataSanityGuard;

    @jakarta.annotation.Resource
    private TradingAgentProperties tradingAgentProperties;

    @jakarta.annotation.Resource
    private NodeResultCommitter nodeResultCommitter;

    @jakarta.annotation.Resource
    private DecisionSignalShadowService decisionSignalShadowService;

    public AnalystCollectionStage(FundamentalAnalystNode fundamentalAnalystNode,
                                  TechnicalAnalystNode technicalAnalystNode,
                                  SentimentAnalystNode sentimentAnalystNode,
                                  NewsAnalystNode newsAnalystNode,
                                  @Qualifier("tradingTaskExecutor") ExecutorService tradingTaskExecutor,
                                  DataSanityGuard dataSanityGuard) {
        this.fundamentalAnalystNode = fundamentalAnalystNode;
        this.technicalAnalystNode = technicalAnalystNode;
        this.sentimentAnalystNode = sentimentAnalystNode;
        this.newsAnalystNode = newsAnalystNode;
        this.tradingTaskExecutor = tradingTaskExecutor;
        this.dataSanityGuard = dataSanityGuard;
    }

    @Override
    public String name() {
        return "AnalystCollectionStage";
    }

    @Override
    public TradingPhase expectedPhase() {
        return TradingPhase.INIT;
    }

    @Override
    public TradingPhase nextPhase() {
        return TradingPhase.INVESTMENT_DEBATE;
    }

    @Override
    public void execute(TradingStateContext context) {
        TradingDriver.clear();
        if (!TradingPipelineSseGuard.shouldContinue(context)) {
            return;
        }
        context.sendSseResult("trading", "trading_init", "交易分析开始", false);

        List<AnalystTypeEnum> analysts = context.getSelectedAnalysts();
        ExecutorCompletionService<NodeExecutionResult<?>> completions =
                new ExecutorCompletionService<>(tradingTaskExecutor);
        List<AnalystTask> tasks = analysts.stream()
                .map(analyst -> createTask(analyst, context, completions))
                .toList();

        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(nodeTimeoutMillis());
        int completed = 0;
        try {
            while (completed < tasks.size()) {
                if (!TradingPipelineSseGuard.shouldContinue(context)) {
                    cancelOutstanding(tasks, CancellationReason.CLIENT);
                    return;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    cancelOutstanding(tasks, CancellationReason.TIMEOUT);
                    break;
                }
                Future<NodeExecutionResult<?>> done = completions.poll(
                        Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(50)),
                        TimeUnit.NANOSECONDS);
                if (done != null) {
                    completed++;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancelOutstanding(tasks, CancellationReason.INTERRUPTED);
            return;
        }
        if (!TradingPipelineSseGuard.shouldContinue(context)) {
            return;
        }

        long committedCount = tasks.stream()
                .filter(task -> commitAnalyst(task, context))
                .count();
        if (committedCount == 0) {
            context.sendError("所有分析师均执行失败或超时");
            return;
        }

        context.getTradingContext().setDataWarnings(
                dataSanityGuard.check(context.getTradingContext()));
        var mode = context.getPromptSnapshot().mode();
        var deterministicSignals = shadowService().calculate(context.getTradingContext());
        context.getTradingContext().setOutputMode(mode.name());
        if (mode == denny.ai.agent.trading.domain.prompt.PromptContractMode.RELAXED_V3) {
            context.getTradingContext().setDecisionSignals(deterministicSignals);
            context.getTradingContext().setShadowDecisionSignals(null);
        } else {
            context.getTradingContext().setDecisionSignals(
                    new V2DecisionSignalFactory().fromReports(context.getTradingContext()));
            context.getTradingContext().setShadowDecisionSignals(deterministicSignals);
        }

        StockAnalysisRequestVO request = context.getRequest();
        int maxRounds = request != null && request.getMaxDebateRounds() > 0
                ? request.getMaxDebateRounds()
                : 2;
        TradingContextVO.InvestmentDebateVO debate = TradingContextVO.InvestmentDebateVO.createNew(maxRounds);
        context.getTradingContext().setInvestmentDebate(debate);
        context.transitionTo(TradingPhase.INVESTMENT_DEBATE);
        context.sendSseResult("debate", "debate_start", "辩论阶段开始", false);
    }

    private long nodeTimeoutMillis() {
        TradingAgentProperties effective = tradingAgentProperties == null
                ? new TradingAgentProperties() : tradingAgentProperties;
        effective.validate();
        return effective.getNodeTimeout().toMillis();
    }

    private AnalystTask createTask(AnalystTypeEnum analyst, TradingStateContext context,
                                   ExecutorCompletionService<NodeExecutionResult<?>> completions) {
        NodeExecutionScope scope = new NodeExecutionScope(
                java.time.Instant.now().plusMillis(nodeTimeoutMillis()),
                () -> !TradingPipelineSseGuard.shouldContinue(context));
        Future<NodeExecutionResult<?>> future = completions.submit(
                () -> prepareAnalyst(analyst, context, scope));
        return new AnalystTask(analyst, scope, future);
    }

    private void cancelOutstanding(List<AnalystTask> tasks, CancellationReason reason) {
        for (AnalystTask task : tasks) {
            if (task.future().isDone()) {
                continue;
            }
            if (reason == CancellationReason.TIMEOUT) {
                task.scope().markTimedOut();
            } else {
                task.scope().markCancelled();
            }
            task.future().cancel(true);
        }
    }

    private NodeExecutionResult<?> prepareAnalyst(AnalystTypeEnum analyst,
                                                   TradingStateContext context,
                                                   NodeExecutionScope scope) {
        try {
            Object value = switch (analyst) {
                case FUNDAMENTAL -> fundamentalAnalystNode.prepare(
                        context.getTradingContext(), context.getDynamicContext());
                case TECHNICAL -> technicalAnalystNode.prepare(
                        context.getTradingContext(), context.getDynamicContext());
                case SENTIMENT -> sentimentAnalystNode.prepare(
                        context.getTradingContext(), context.getDynamicContext());
                case NEWS -> newsAnalystNode.prepare(
                        context.getTradingContext(), context.getDynamicContext());
            };
            if (scope.isDeadlineElapsed()) {
                return NodeExecutionResult.timedOut(
                        new TradingPipelineException("分析师执行超时: " + analyst), scope);
            }
            if (scope.isRequestCancelled()) {
                return NodeExecutionResult.cancelled(
                        new TradingPipelineException("分析师执行取消: " + analyst), scope);
            }
            return NodeExecutionResult.success(value, scope);
        } catch (Exception e) {
            return NodeExecutionResult.failed(e, scope);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean commitAnalyst(AnalystTask task, TradingStateContext context) {
        if (!TradingPipelineSseGuard.shouldContinue(context)) {
            return false;
        }
        if (!task.future().isDone() || task.future().isCancelled()) {
            return false;
        }
        NodeExecutionResult<Object> result;
        try {
            result = (NodeExecutionResult<Object>) task.future().get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (CancellationException | ExecutionException failure) {
            return false;
        }
        String nodeName = analystNodeName(task.analyst());
        NodeCommitResult commit = committer().commitValidated(result, TradingPhase.INIT,
                context, nodeName,
                value -> writeAnalystResult(task.analyst(), context.getTradingContext(), value));
        if (commit.validationFailed()) {
            context.sendValidationError(nodeName, commit.validationErrors());
            return false;
        }
        if (commit.committed()) {
            context.sendSseResult("analyst", "analyst_report", JSON.toJSONString(result.value()), false);
        }
        return commit.committed();
    }

    private String analystNodeName(AnalystTypeEnum analyst) {
        return switch (analyst) {
            case FUNDAMENTAL -> "FundamentalAnalystNode";
            case TECHNICAL -> "TechnicalAnalystNode";
            case SENTIMENT -> "SentimentAnalystNode";
            case NEWS -> "NewsAnalystNode";
        };
    }

    private void writeAnalystResult(AnalystTypeEnum analyst,
                                    TradingContextVO tradingContext,
                                    Object value) {
        switch (analyst) {
            case FUNDAMENTAL -> tradingContext.setFundamentalReport(
                    (denny.ai.agent.trading.api.vo.FundamentalReportVO) value);
            case TECHNICAL -> tradingContext.setTechnicalReport(
                    (denny.ai.agent.trading.api.vo.TechnicalReportVO) value);
            case SENTIMENT -> tradingContext.setSentimentReport(
                    (denny.ai.agent.trading.api.vo.SentimentReportVO) value);
            case NEWS -> tradingContext.setNewsReport(
                    (denny.ai.agent.trading.api.vo.NewsReportVO) value);
        }
    }

    private NodeResultCommitter committer() {
        return nodeResultCommitter == null ? new NodeResultCommitter() : nodeResultCommitter;
    }

    private DecisionSignalShadowService shadowService() {
        return decisionSignalShadowService == null
                ? new DecisionSignalShadowService() : decisionSignalShadowService;
    }

    private record AnalystTask(AnalystTypeEnum analyst,
                               NodeExecutionScope scope,
                               Future<NodeExecutionResult<?>> future) {
    }

    private enum CancellationReason {
        TIMEOUT,
        CLIENT,
        INTERRUPTED
    }
}
