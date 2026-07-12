package denny.ai.agent.domain.service.runtime;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.runtime.TurnRuntimeContext;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;

public interface RuntimeContextAssembler {

    TurnRuntimeContext prepare(ExecuteCommandEntity request,
                               DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext);

    default void afterTurn(ExecuteCommandEntity request,
                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                           TurnRuntimeContext turnContext) {
    }
}
