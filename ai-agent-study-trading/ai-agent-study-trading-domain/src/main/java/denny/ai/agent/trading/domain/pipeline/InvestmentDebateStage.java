package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.node.BearResearcherNode;
import denny.ai.agent.trading.domain.node.BullResearcherNode;
import denny.ai.agent.trading.domain.node.ResearchManagerNode;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import denny.ai.agent.trading.domain.execution.NodeExecutionResult;
import denny.ai.agent.trading.domain.execution.NodeExecutionScope;
import denny.ai.agent.trading.domain.execution.NodeResultCommitter;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class InvestmentDebateStage implements TradingStage {

    private final BullResearcherNode bullResearcherNode;
    private final BearResearcherNode bearResearcherNode;
    private final ResearchManagerNode researchManagerNode;
    private final TradingNodeInvoker nodeInvoker;

    @jakarta.annotation.Resource
    private NodeResultCommitter nodeResultCommitter;

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
            if (!executeBull(context, debate)) {
                return;
            }

            debate.setLatestSpeaker("BEAR");
            context.setLatestDebateSpeaker("BEAR");
            if (!executeBear(context, debate)) {
                return;
            }

            debate.setLatestSpeaker("RESEARCH_MANAGER");
            context.setLatestDebateSpeaker("RESEARCH_MANAGER");
            if (!executeResearchManager(context, debate)) {
                return;
            }

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

    private boolean executeBull(TradingStateContext context,
                                TradingContextVO.InvestmentDebateVO debate) {
        NodeExecutionScope scope = nodeInvoker.newScope(context);
        NodeExecutionResult<String> result = nodeInvoker.invokeScoped("BullResearcherNode", scope,
                () -> bullResearcherNode.prepare(context.getTradingContext(), context.getDynamicContext()));
        boolean committed = committer().commit(result, TradingPhase.INVESTMENT_DEBATE,
                context::getCurrentPhase, thesis -> {
                    debate.addBullArgument(thesis);
                    debate.addToHistory("[Round " + debate.getCurrentRound() + " - BULL] " + thesis);
                });
        if (!committed) {
            context.sendError("多头研究员执行失败");
            return false;
        }
        context.sendSseResult("debate", "bull_thesis", result.value(), false);
        return TradingPipelineSseGuard.shouldContinue(context);
    }

    private boolean executeBear(TradingStateContext context,
                                TradingContextVO.InvestmentDebateVO debate) {
        NodeExecutionScope scope = nodeInvoker.newScope(context);
        NodeExecutionResult<String> result = nodeInvoker.invokeScoped("BearResearcherNode", scope,
                () -> bearResearcherNode.prepare(context.getTradingContext(), context.getDynamicContext()));
        boolean committed = committer().commit(result, TradingPhase.INVESTMENT_DEBATE,
                context::getCurrentPhase, thesis -> {
                    debate.addBearArgument(thesis);
                    debate.addToHistory("[Round " + debate.getCurrentRound() + " - BEAR] " + thesis);
                });
        if (!committed) {
            context.sendError("空头研究员执行失败");
            return false;
        }
        context.sendSseResult("debate", "bear_thesis", result.value(), false);
        return TradingPipelineSseGuard.shouldContinue(context);
    }

    private boolean executeResearchManager(TradingStateContext context,
                                           TradingContextVO.InvestmentDebateVO debate) {
        NodeExecutionScope scope = nodeInvoker.newScope(context);
        NodeExecutionResult<ResearchManagerNode.DebateEvaluation> result =
                nodeInvoker.invokeScoped("ResearchManagerNode", scope,
                        () -> researchManagerNode.prepare(
                                context.getTradingContext(), context.getDynamicContext()));
        boolean committed = committer().commit(result, TradingPhase.INVESTMENT_DEBATE,
                context::getCurrentPhase, evaluation -> {
                    debate.setOverallScore(evaluation.overallScore());
                    debate.setConclusion(evaluation.conclusion());
                    debate.setJudgeDecision(evaluation.conclusion());
                    debate.setNeedMoreDebate(evaluation.needMoreDebate());
                });
        if (!committed) {
            context.sendError("研究主管执行失败");
            return false;
        }
        return TradingPipelineSseGuard.shouldContinue(context);
    }

    private NodeResultCommitter committer() {
        return nodeResultCommitter == null ? new NodeResultCommitter() : nodeResultCommitter;
    }
}
