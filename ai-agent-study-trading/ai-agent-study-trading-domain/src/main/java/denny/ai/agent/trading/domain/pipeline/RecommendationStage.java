package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.node.RecommendationNode;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import denny.ai.agent.trading.domain.execution.NodeExecutionResult;
import denny.ai.agent.trading.domain.execution.NodeExecutionScope;
import denny.ai.agent.trading.domain.execution.NodeResultCommitter;
import com.alibaba.fastjson.JSON;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(30)
public class RecommendationStage implements TradingStage {

    private final RecommendationNode recommendationNode;
    private final TradingNodeInvoker nodeInvoker;

    @jakarta.annotation.Resource
    private NodeResultCommitter nodeResultCommitter;

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

        NodeExecutionScope scope = nodeInvoker.newScope(context);
        NodeExecutionResult<TradingContextVO.InvestmentPlanVO> result =
                nodeInvoker.invokeScoped("RecommendationNode", scope,
                        () -> recommendationNode.prepare(
                                context.getTradingContext(), context.getDynamicContext()));
        boolean committed = committer().commit(result, TradingPhase.RECOMMENDATION_DECISION,
                context::getCurrentPhase, context.getTradingContext()::setInvestmentPlan);
        if (!committed) {
            context.sendError("推荐节点执行失败");
            return;
        }
        context.sendSseResult("recommendation", "recommendation_plan",
                JSON.toJSONString(result.value()), false);
        if (!TradingPipelineSseGuard.shouldContinue(context)) {
            return;
        }

        context.transitionTo(TradingPhase.RISK_MANAGEMENT);
        context.sendSseResult("risk", "risk_start", "风控阶段开始", false);
    }

    private NodeResultCommitter committer() {
        return nodeResultCommitter == null ? new NodeResultCommitter() : nodeResultCommitter;
    }
}
