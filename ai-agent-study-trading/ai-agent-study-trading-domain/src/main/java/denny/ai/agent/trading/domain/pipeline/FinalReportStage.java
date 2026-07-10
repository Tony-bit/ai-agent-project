package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.node.PortfolioManagerNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(50)
public class FinalReportStage implements TradingStage {

    private final PortfolioManagerNode portfolioManagerNode;
    private final TradingNodeInvoker nodeInvoker;

    public FinalReportStage(PortfolioManagerNode portfolioManagerNode, TradingNodeInvoker nodeInvoker) {
        this.portfolioManagerNode = portfolioManagerNode;
        this.nodeInvoker = nodeInvoker;
    }

    @Override
    public String name() {
        return "FinalReportStage";
    }

    @Override
    public TradingPhase expectedPhase() {
        return TradingPhase.FINAL_REPORT;
    }

    @Override
    public TradingPhase nextPhase() {
        return TradingPhase.FINAL_REPORT;
    }

    @Override
    public void execute(TradingStateContext context) {
        nodeInvoker.invokeIfOpen(context, "PortfolioManagerNode",
                () -> portfolioManagerNode.doApply(new ExecuteCommandEntity(), context.getDynamicContext()));
        if (!TradingPipelineSseGuard.shouldContinue(context)) {
            return;
        }
        context.transitionTo(TradingPhase.FINAL_REPORT);
    }
}
