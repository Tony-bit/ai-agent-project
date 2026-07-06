package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingDriver;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.node.FundamentalAnalystNode;
import denny.ai.agent.trading.domain.node.NewsAnalystNode;
import denny.ai.agent.trading.domain.node.SentimentAnalystNode;
import denny.ai.agent.trading.domain.node.TechnicalAnalystNode;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Order(10)
public class AnalystCollectionStage implements TradingStage {

    private static final int NODE_TIMEOUT_SECONDS = 180;

    private final FundamentalAnalystNode fundamentalAnalystNode;
    private final TechnicalAnalystNode technicalAnalystNode;
    private final SentimentAnalystNode sentimentAnalystNode;
    private final NewsAnalystNode newsAnalystNode;
    private final ExecutorService tradingTaskExecutor;

    public AnalystCollectionStage(FundamentalAnalystNode fundamentalAnalystNode,
                                  TechnicalAnalystNode technicalAnalystNode,
                                  SentimentAnalystNode sentimentAnalystNode,
                                  NewsAnalystNode newsAnalystNode,
                                  @Qualifier("tradingTaskExecutor") ExecutorService tradingTaskExecutor) {
        this.fundamentalAnalystNode = fundamentalAnalystNode;
        this.technicalAnalystNode = technicalAnalystNode;
        this.sentimentAnalystNode = sentimentAnalystNode;
        this.newsAnalystNode = newsAnalystNode;
        this.tradingTaskExecutor = tradingTaskExecutor;
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
        List<CompletableFuture<Void>> futures = analysts.stream()
                .map(analyst -> CompletableFuture.runAsync(
                        () -> invokeAnalyst(analyst, context),
                        tradingTaskExecutor))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get((long) NODE_TIMEOUT_SECONDS * Math.max(1, analysts.size()), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new TradingPipelineException("分析师阶段执行异常", e);
        }
        if (!TradingPipelineSseGuard.shouldContinue(context)) {
            return;
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

    private void invokeAnalyst(AnalystTypeEnum analyst, TradingStateContext context) {
        if (!TradingPipelineSseGuard.shouldContinue(context)) {
            return;
        }
        try {
            switch (analyst) {
                case FUNDAMENTAL -> fundamentalAnalystNode.doApply(new ExecuteCommandEntity(), context.getDynamicContext());
                case TECHNICAL -> technicalAnalystNode.doApply(new ExecuteCommandEntity(), context.getDynamicContext());
                case SENTIMENT -> sentimentAnalystNode.doApply(new ExecuteCommandEntity(), context.getDynamicContext());
                case NEWS -> newsAnalystNode.doApply(new ExecuteCommandEntity(), context.getDynamicContext());
            }
        } catch (Exception e) {
            throw new TradingPipelineException("分析师执行异常: " + analyst, e);
        }
    }
}
