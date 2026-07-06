package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.node.RecommendationNode;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(30)
public class RecommendationStage implements TradingStage {

    private final RecommendationNode recommendationNode;
    private final TradingNodeInvoker nodeInvoker;

    public RecommendationStage(RecommendationNode recommendationNode, TradingNodeInvoker nodeInvoker) {
        this.recommendationNode = recommendationNode;
        this.nodeInvoker = nodeInvoker;
    }

    @Override
    public String name() {
        return "RecommendationStage";
    }

    @Override
    public TradingPhase expectedPhase() {
        return TradingPhase.RECOMMENDATION_DECISION;
    }

    @Override
    public TradingPhase nextPhase() {
        return TradingPhase.RISK_MANAGEMENT;
    }

    @Override
    public void execute(TradingStateContext context) {
        TradingContextVO.RiskDebateVO riskDebate = new TradingContextVO.RiskDebateVO();
        StockAnalysisRequestVO request = context.getRequest();
        int maxRiskRounds = request != null && request.getMaxRiskRounds() > 0
                ? request.getMaxRiskRounds()
                : 1;
        riskDebate.setMaxRounds(maxRiskRounds);
        context.getTradingContext().setRiskDebate(riskDebate);

        nodeInvoker.invokeIfOpen(context, "RecommendationNode",
                () -> recommendationNode.doApply(new ExecuteCommandEntity(), context.getDynamicContext()));
        if (!TradingPipelineSseGuard.shouldContinue(context)) {
            return;
        }

        context.transitionTo(TradingPhase.RISK_MANAGEMENT);
        context.sendSseResult("risk", "risk_start", "风控阶段开始", false);
    }
}
