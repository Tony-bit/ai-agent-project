package denny.ai.agent.trading.domain.prompt;

import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory.DynamicContext;
import denny.ai.agent.trading.domain.execution.StructuredPayloadCodec;
import denny.ai.agent.trading.domain.vo.TradingContextVO;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class TradingRolePromptService {
    private final TradingPromptRenderer renderer;
    private final StructuredPayloadCodec codec;

    public TradingRolePromptService(TradingPromptRenderer renderer, StructuredPayloadCodec codec) {
        this.renderer = renderer;
        this.codec = codec;
    }

    public <T> String render(String promptId,
                             TradingContextVO context,
                             DynamicContext dynamicContext,
                             Map<String, ?> variables,
                             Class<T> payloadType) {
        TradingPromptSnapshot snapshot = dynamicContext.getValue("trading_prompt_snapshot");
        if (snapshot == null) {
            throw new IllegalStateException("trading prompt snapshot is missing");
        }
        Map<String, Object> values = new HashMap<>();
        if (variables != null) {
            values.putAll(variables);
        }
        if (snapshot.mode() == PromptContractMode.STRICT_V2) {
            values.put("outputContract", codec.outputContract(payloadType));
        } else if (java.util.Set.of("6008", "6009", "6013").contains(promptId)) {
            values.put("minimalOutputContract", codec.outputContract(payloadType));
        }
        return renderer.render(snapshot, context.getTargetContext(), promptId, values);
    }
}
