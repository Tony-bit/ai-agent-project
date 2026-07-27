package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.node.AggressiveRiskAnalystNode;
import denny.ai.agent.trading.domain.node.ConservativeRiskAnalystNode;
import denny.ai.agent.trading.domain.node.NeutralRiskAnalystNode;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import denny.ai.agent.trading.domain.execution.NodeExecutionResult;
import denny.ai.agent.trading.domain.execution.NodeExecutionScope;
import denny.ai.agent.trading.domain.execution.NodeResultCommitter;
import denny.ai.agent.trading.domain.execution.NodeCommitResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import denny.ai.agent.trading.api.vo.payload.RiskAssessmentPayload;

@Component
@Order(40)
public class RiskManagementStage implements TradingStage {

    private final AggressiveRiskAnalystNode aggressiveRiskAnalystNode;
    private final ConservativeRiskAnalystNode conservativeRiskAnalystNode;
    private final NeutralRiskAnalystNode neutralRiskAnalystNode;
    private final TradingNodeInvoker nodeInvoker;

    @jakarta.annotation.Resource
    private NodeResultCommitter nodeResultCommitter;

    public RiskManagementStage(AggressiveRiskAnalystNode aggressiveRiskAnalystNode,
                               ConservativeRiskAnalystNode conservativeRiskAnalystNode,
                               NeutralRiskAnalystNode neutralRiskAnalystNode,
                               TradingNodeInvoker nodeInvoker) {
        this.aggressiveRiskAnalystNode = aggressiveRiskAnalystNode;
        this.conservativeRiskAnalystNode = conservativeRiskAnalystNode;
        this.neutralRiskAnalystNode = neutralRiskAnalystNode;
        this.nodeInvoker = nodeInvoker;
    }

    @Override
    public String name() {
        return "RiskManagementStage";
    }

    @Override
    public TradingPhase expectedPhase() {
        return TradingPhase.RISK_MANAGEMENT;
    }

    @Override
    public TradingPhase nextPhase() {
        return TradingPhase.FINAL_REPORT;
    }

    @Override
    public void execute(TradingStateContext context) {
        TradingContextVO.RiskDebateVO riskDebate = context.getTradingContext().getRiskDebate();
        if (riskDebate == null) {
            riskDebate = new TradingContextVO.RiskDebateVO();
            context.getTradingContext().setRiskDebate(riskDebate);
        }
        int maxRounds = riskDebate != null ? Math.max(1, riskDebate.getMaxRounds()) : 1;
        TradingContextVO.RiskDebateVO committedRiskDebate = riskDebate;

        for (int round = 0; round < maxRounds; round++) {
            if (!TradingPipelineSseGuard.shouldContinue(context)) {
                return;
            }
            context.setLatestRiskSpeaker("AGGRESSIVE");
            if (!executeRisk(context, "AggressiveRiskAnalystNode",
                    () -> aggressiveRiskAnalystNode.prepare(
                            context.getTradingContext(), context.getDynamicContext()),
                    opinion -> {
                        if (committedRiskDebate.getAggressiveHistory() == null) {
                            committedRiskDebate.setAggressiveHistory(new ArrayList<>());
                        }
                        committedRiskDebate.getAggressiveHistory().add(opinion);
                    }, "aggressive_opinion")) {
                return;
            }

            context.setLatestRiskSpeaker("CONSERVATIVE");
            if (!executeRisk(context, "ConservativeRiskAnalystNode",
                    () -> conservativeRiskAnalystNode.prepare(
                            context.getTradingContext(), context.getDynamicContext()),
                    opinion -> {
                        if (committedRiskDebate.getConservativeHistory() == null) {
                            committedRiskDebate.setConservativeHistory(new ArrayList<>());
                        }
                        committedRiskDebate.getConservativeHistory().add(opinion);
                    }, "conservative_opinion")) {
                return;
            }

            context.setLatestRiskSpeaker("NEUTRAL");
            if (!executeRisk(context, "NeutralRiskAnalystNode",
                    () -> neutralRiskAnalystNode.prepare(
                            context.getTradingContext(), context.getDynamicContext()),
                    opinion -> {
                        if (committedRiskDebate.getNeutralHistory() == null) {
                            committedRiskDebate.setNeutralHistory(new ArrayList<>());
                        }
                        committedRiskDebate.getNeutralHistory().add(opinion);
                    }, "neutral_opinion")) {
                return;
            }
        }
        if (!TradingPipelineSseGuard.shouldContinue(context)) {
            return;
        }

        context.transitionTo(TradingPhase.FINAL_REPORT);
        context.sendSseResult("final", "final_report_start", "最终报告生成中", false);
    }

    private boolean executeRisk(TradingStateContext context,
                                String nodeName,
                                Callable<RiskAssessmentPayload> prepare,
                                Consumer<RiskAssessmentPayload> contextWriter,
                                String eventSubtype) {
        NodeExecutionScope scope = nodeInvoker.newScope(context);
        NodeExecutionResult<RiskAssessmentPayload> result = nodeInvoker.invokeScoped(nodeName, scope, prepare);
        NodeCommitResult commit = committer().commitValidated(result,
                TradingPhase.RISK_MANAGEMENT, context, nodeName, contextWriter);
        if (!commit.committed()) {
            if (commit.validationFailed()) {
                context.sendValidationError(nodeName, commit.validationErrors());
            } else {
                context.sendError(nodeName + " 执行失败");
            }
            return false;
        }
        context.sendSseResult("risk_debate", eventSubtype,
                com.alibaba.fastjson.JSON.toJSONString(result.value()), false);
        return TradingPipelineSseGuard.shouldContinue(context);
    }

    private NodeResultCommitter committer() {
        return nodeResultCommitter == null ? new NodeResultCommitter() : nodeResultCommitter;
    }
}
