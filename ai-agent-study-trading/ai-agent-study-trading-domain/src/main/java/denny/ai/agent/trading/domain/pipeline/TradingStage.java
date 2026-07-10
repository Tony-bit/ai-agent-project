package denny.ai.agent.trading.domain.pipeline;

import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.config.TradingStateContext;

public interface TradingStage {

    String name();

    TradingPhase expectedPhase();

    TradingPhase nextPhase();

    void execute(TradingStateContext context);
}
