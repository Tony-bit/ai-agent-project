package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.node.PortfolioManagerNode;
import denny.ai.agent.trading.domain.execution.NodeExecutionResult;
import denny.ai.agent.trading.domain.execution.NodeExecutionScope;
import denny.ai.agent.trading.domain.execution.NodeResultCommitter;
import denny.ai.agent.trading.domain.execution.NodeCommitResult;
import denny.ai.agent.trading.domain.model.valobj.TradingResultVO;
import denny.ai.agent.trading.domain.service.TradingResultExportService;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import com.alibaba.fastjson.JSON;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(50)
public class FinalReportStage implements TradingStage {

    private final PortfolioManagerNode portfolioManagerNode;
    private final TradingNodeInvoker nodeInvoker;

    @jakarta.annotation.Resource
    private NodeResultCommitter nodeResultCommitter;

    @jakarta.annotation.Resource
    private TradingResultExportService tradingResultExportService;

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
        NodeExecutionScope scope = nodeInvoker.newScope(context);
        NodeExecutionResult<TradingContextVO.FinalTradeDecisionVO> result =
                nodeInvoker.invokeScoped("PortfolioManagerNode", scope,
                        () -> portfolioManagerNode.prepare(
                                context.getTradingContext(), context.getDynamicContext()));
        NodeCommitResult commit = committer().commitValidated(result, TradingPhase.FINAL_REPORT,
                context, "PortfolioManagerNode", context.getTradingContext()::setFinalDecision);
        if (!commit.committed()) {
            if (commit.validationFailed()) {
                context.sendValidationError("PortfolioManagerNode", commit.validationErrors());
            } else {
                context.sendError("组合经理执行失败");
            }
            return;
        }
        context.sendSseResult("final", "final_decision", JSON.toJSONString(result.value()), false);
        context.getDynamicContext().setValue("tradingFinalDecision", JSON.toJSONString(result.value()));
        if (tradingResultExportService != null) {
            tradingResultExportService.export(TradingResultVO.from(context.getTradingContext()));
        }
        if (!TradingPipelineSseGuard.shouldContinue(context)) {
            return;
        }
        context.transitionTo(TradingPhase.FINAL_REPORT);
    }

    private NodeResultCommitter committer() {
        return nodeResultCommitter == null ? new NodeResultCommitter() : nodeResultCommitter;
    }
}
