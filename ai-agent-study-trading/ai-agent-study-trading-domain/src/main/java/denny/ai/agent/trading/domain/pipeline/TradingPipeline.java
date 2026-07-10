package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TradingPipeline {

    private final List<TradingStage> stages;

    public TradingPipeline(List<TradingStage> stages) {
        this.stages = List.copyOf(stages);
    }

    public void execute(TradingStateContext context) {
        for (TradingStage stage : stages) {
            if (context.getCurrentPhase() == TradingPhase.ERROR) {
                break;
            }
            if (!TradingPipelineSseGuard.shouldContinue(context)) {
                break;
            }
            validateBefore(stage, context);
            stage.execute(context);
            if (!TradingPipelineSseGuard.shouldContinue(context)) {
                break;
            }
            validateAfter(stage, context);
        }
    }

    private void validateBefore(TradingStage stage, TradingStateContext context) {
        if (context.getCurrentPhase() != stage.expectedPhase()) {
            throw new TradingPipelineException(
                    "Stage " + stage.name() + " expected " + stage.expectedPhase()
                            + " but was " + context.getCurrentPhase());
        }
    }

    private void validateAfter(TradingStage stage, TradingStateContext context) {
        if (context.getCurrentPhase() == TradingPhase.ERROR) {
            return;
        }
        if (context.getCurrentPhase() != stage.nextPhase()) {
            throw new TradingPipelineException(
                    "Stage " + stage.name() + " must transition to " + stage.nextPhase()
                            + " but was " + context.getCurrentPhase());
        }
    }
}
