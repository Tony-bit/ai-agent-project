package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.node.BearResearcherNode;
import denny.ai.agent.trading.domain.node.BullResearcherNode;
import denny.ai.agent.trading.domain.node.ResearchManagerNode;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class InvestmentDebateStage implements TradingStage {

    private final BullResearcherNode bullResearcherNode;
    private final BearResearcherNode bearResearcherNode;
    private final ResearchManagerNode researchManagerNode;
    private final TradingNodeInvoker nodeInvoker;

    public InvestmentDebateStage(BullResearcherNode bullResearcherNode,
                                 BearResearcherNode bearResearcherNode,
                                 ResearchManagerNode researchManagerNode,
                                 TradingNodeInvoker nodeInvoker) {
        this.bullResearcherNode = bullResearcherNode;
        this.bearResearcherNode = bearResearcherNode;
        this.researchManagerNode = researchManagerNode;
        this.nodeInvoker = nodeInvoker;
    }

    @Override
    public String name() {
        return "InvestmentDebateStage";
    }

    @Override
    public TradingPhase expectedPhase() {
        return TradingPhase.INVESTMENT_DEBATE;
    }

    @Override
    public TradingPhase nextPhase() {
        return TradingPhase.RECOMMENDATION_DECISION;
    }

    @Override
    public void execute(TradingStateContext context) {
        TradingContextVO.InvestmentDebateVO debate = context.getTradingContext().getInvestmentDebate();
        if (debate == null) {
            throw new TradingPipelineException("投资辩论上下文为空");
        }

        int maxRounds = Math.max(1, debate.getMaxRounds());
        for (int completedRounds = 0; completedRounds < maxRounds; completedRounds++) {
            if (!TradingPipelineSseGuard.shouldContinue(context)) {
                return;
            }
            debate.setLatestSpeaker("BULL");
            context.setLatestDebateSpeaker("BULL");
            nodeInvoker.invokeIfOpen(context, "BullResearcherNode",
                    () -> bullResearcherNode.doApply(new ExecuteCommandEntity(), context.getDynamicContext()));

            debate.setLatestSpeaker("BEAR");
            context.setLatestDebateSpeaker("BEAR");
            nodeInvoker.invokeIfOpen(context, "BearResearcherNode",
                    () -> bearResearcherNode.doApply(new ExecuteCommandEntity(), context.getDynamicContext()));

            debate.setLatestSpeaker("RESEARCH_MANAGER");
            context.setLatestDebateSpeaker("RESEARCH_MANAGER");
            nodeInvoker.invokeIfOpen(context, "ResearchManagerNode",
                    () -> researchManagerNode.doApply(new ExecuteCommandEntity(), context.getDynamicContext()));

            debate.setCurrentRound(completedRounds + 1);
            if (!debate.isNeedMoreDebate()) {
                break;
            }
        }
        if (!TradingPipelineSseGuard.shouldContinue(context)) {
            return;
        }

        context.transitionTo(TradingPhase.RECOMMENDATION_DECISION);
        context.sendSseResult("debate", "debate_complete", "辩论结束，进入推荐决策", false);
    }
}
