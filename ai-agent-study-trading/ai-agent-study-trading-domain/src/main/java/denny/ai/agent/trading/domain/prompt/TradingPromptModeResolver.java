package denny.ai.agent.trading.domain.prompt;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory.DynamicContext;

public final class TradingPromptModeResolver {

    private TradingPromptModeResolver() {
    }

    public static PromptContractMode requireMode(DynamicContext dynamicContext) {
        TradingPromptSnapshot snapshot = dynamicContext.getValue("trading_prompt_snapshot");
        if (snapshot == null) {
            throw new IllegalStateException("trading prompt snapshot is missing");
        }
        return snapshot.mode();
    }
}
