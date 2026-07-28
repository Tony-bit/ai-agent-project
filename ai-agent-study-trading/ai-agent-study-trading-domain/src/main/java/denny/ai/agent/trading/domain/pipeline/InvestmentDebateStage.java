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
import denny.ai.agent.trading.domain.execution.NodeCommitResult;
import denny.ai.agent.trading.api.vo.ResearchManagerResult;
import denny.ai.agent.trading.api.vo.NarrativeNodeResult;
import denny.ai.agent.trading.api.vo.signal.DecisionSignal;
import denny.ai.agent.trading.api.vo.signal.DecisionSignalSource;
import denny.ai.agent.trading.domain.signal.V2DecisionSignalFactory;
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
            boolean bullAvailable = executeBull(context, debate);

            debate.setLatestSpeaker("BEAR");
            context.setLatestDebateSpeaker("BEAR");
            boolean bearAvailable = executeBear(context, debate);

            if (!bullAvailable && !bearAvailable) {
                addWarning(context, "Bull/Bear 本轮均不可用，跳过投资辩论");
                debate.setNeedMoreDebate(false);
                break;
            }

            debate.setLatestSpeaker("RESEARCH_MANAGER");
            context.setLatestDebateSpeaker("RESEARCH_MANAGER");
            if (!executeResearchManager(context, debate)) {
                addWarning(context, "Research Manager 不可用，使用 INSUFFICIENT_DATA");
                debate.setJudgeDecision("INSUFFICIENT_DATA");
                debate.setNeedMoreDebate(false);
                break;
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
        NodeExecutionResult<NarrativeNodeResult> result = nodeInvoker.invokeScoped("BullResearcherNode", scope,
                () -> bullResearcherNode.prepare(context.getTradingContext(), context.getDynamicContext()));
        NodeCommitResult commit = committer().commitValidated(result, TradingPhase.INVESTMENT_DEBATE,
                context, "BullResearcherNode", thesis -> {
                    debate.addBullArgument(thesis);
                    debate.addToHistory("[Round " + debate.getCurrentRound() + " - BULL] "
                            + thesis.rawText());
                });
        if (!commit.committed()) {
            addWarning(context, "BullResearcherNode 执行失败或结果不可用");
            return false;
        }
        context.sendSseResult("debate", "bull_thesis",
                com.alibaba.fastjson.JSON.toJSONString(result.value()), false);
        return TradingPipelineSseGuard.shouldContinue(context);
    }

    private boolean executeBear(TradingStateContext context,
                                TradingContextVO.InvestmentDebateVO debate) {
        NodeExecutionScope scope = nodeInvoker.newScope(context);
        NodeExecutionResult<NarrativeNodeResult> result = nodeInvoker.invokeScoped("BearResearcherNode", scope,
                () -> bearResearcherNode.prepare(context.getTradingContext(), context.getDynamicContext()));
        NodeCommitResult commit = committer().commitValidated(result, TradingPhase.INVESTMENT_DEBATE,
                context, "BearResearcherNode", thesis -> {
                    debate.addBearArgument(thesis);
                    debate.addToHistory("[Round " + debate.getCurrentRound() + " - BEAR] "
                            + thesis.rawText());
                });
        if (!commit.committed()) {
            addWarning(context, "BearResearcherNode 执行失败或结果不可用");
            return false;
        }
        context.sendSseResult("debate", "bear_thesis",
                com.alibaba.fastjson.JSON.toJSONString(result.value()), false);
        return TradingPipelineSseGuard.shouldContinue(context);
    }

    private boolean executeResearchManager(TradingStateContext context,
                                           TradingContextVO.InvestmentDebateVO debate) {
        NodeExecutionScope scope = nodeInvoker.newScope(context);
        NodeExecutionResult<ResearchManagerResult> result =
                nodeInvoker.invokeScoped("ResearchManagerNode", scope,
                        () -> researchManagerNode.prepare(
                                context.getTradingContext(), context.getDynamicContext()));
        NodeCommitResult commit = committer().commitValidated(result, TradingPhase.INVESTMENT_DEBATE,
                context, "ResearchManagerNode", evaluation -> {
                    debate.setOverallScore(evaluation.overallScore());
                    debate.setConclusion(evaluation.reasoning());
                    debate.setJudgeDecision(evaluation.recommendation());
                    debate.setNeedMoreDebate(evaluation.needMoreDebate());
                    var signals = context.getTradingContext().getDecisionSignals();
                    if (signals == null) {
                        signals = new V2DecisionSignalFactory().fromReports(context.getTradingContext());
                    }
                    boolean relaxed = context.getPromptSnapshot().mode()
                            == denny.ai.agent.trading.domain.prompt.PromptContractMode.RELAXED_V3;
                    context.getTradingContext().setDecisionSignals(signals.withDebateOverallScore(
                                    evaluation.overallScore() == null
                                            ? DecisionSignal.unavailable(relaxed
                                                    ? DecisionSignalSource.DERIVED_V3
                                                    : DecisionSignalSource.LLM_V2,
                                                    relaxed ? "research-manager-score-v1" : null,
                                                    "research manager score is unavailable")
                                            : DecisionSignal.available(evaluation.overallScore(),
                                                    relaxed ? DecisionSignalSource.DERIVED_V3
                                                            : DecisionSignalSource.LLM_V2,
                                                    relaxed ? "research-manager-score-v1" : null)));
                });
        if (!commit.committed()) {
            addWarning(context, "ResearchManagerNode 执行失败或结果不可用");
            return false;
        }
        return TradingPipelineSseGuard.shouldContinue(context);
    }

    private NodeResultCommitter committer() {
        return nodeResultCommitter == null ? new NodeResultCommitter() : nodeResultCommitter;
    }

    private void sendCommitError(TradingStateContext context,
                                 NodeCommitResult result,
                                 String nodeName,
                                 String executionMessage) {
        if (result.validationFailed()) {
            context.sendValidationError(nodeName, result.validationErrors());
        } else {
            context.sendError(executionMessage);
        }
    }

    private void addWarning(TradingStateContext context, String warning) {
        java.util.List<String> warnings = context.getTradingContext().getDataWarnings() == null
                ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(context.getTradingContext().getDataWarnings());
        warnings.add(warning);
        context.getTradingContext().setDataWarnings(warnings);
    }
}
