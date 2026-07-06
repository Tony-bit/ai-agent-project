package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.node.AggressiveRiskAnalystNode;
import denny.ai.agent.trading.domain.node.ConservativeRiskAnalystNode;
import denny.ai.agent.trading.domain.node.NeutralRiskAnalystNode;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(40)
public class RiskManagementStage implements TradingStage {

    private final AggressiveRiskAnalystNode aggressiveRiskAnalystNode;
    private final ConservativeRiskAnalystNode conservativeRiskAnalystNode;
    private final NeutralRiskAnalystNode neutralRiskAnalystNode;
    private final TradingNodeInvoker nodeInvoker;

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
        int maxRounds = riskDebate != null ? Math.max(1, riskDebate.getMaxRounds()) : 1;

        for (int round = 0; round < maxRounds; round++) {
            if (!TradingPipelineSseGuard.shouldContinue(context)) {
                return;
            }
            context.setLatestRiskSpeaker("AGGRESSIVE");
            nodeInvoker.invokeIfOpen(context, "AggressiveRiskAnalystNode",
                    () -> aggressiveRiskAnalystNode.doApply(new ExecuteCommandEntity(), context.getDynamicContext()));

            context.setLatestRiskSpeaker("CONSERVATIVE");
            nodeInvoker.invokeIfOpen(context, "ConservativeRiskAnalystNode",
                    () -> conservativeRiskAnalystNode.doApply(new ExecuteCommandEntity(), context.getDynamicContext()));

            context.setLatestRiskSpeaker("NEUTRAL");
            nodeInvoker.invokeIfOpen(context, "NeutralRiskAnalystNode",
                    () -> neutralRiskAnalystNode.doApply(new ExecuteCommandEntity(), context.getDynamicContext()));
        }
        if (!TradingPipelineSseGuard.shouldContinue(context)) {
            return;
        }

        context.transitionTo(TradingPhase.FINAL_REPORT);
        context.sendSseResult("final", "final_report_start", "最终报告生成中", false);
    }
}
