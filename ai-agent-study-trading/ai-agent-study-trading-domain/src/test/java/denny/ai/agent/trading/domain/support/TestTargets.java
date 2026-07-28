package denny.ai.agent.trading.domain.support;

import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory.DynamicContext;
import denny.ai.agent.domain.service.sse.SseEventSender;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.prompt.PromptVersion;
import denny.ai.agent.trading.domain.prompt.TradingPromptSet;
import denny.ai.agent.trading.domain.prompt.TradingPromptSnapshot;

import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Collectors;

public final class TestTargets {
    private TestTargets() {
    }

    public static TargetContext forTicker(String ticker) {
        String code = ticker.substring(0, 6);
        char first = code.charAt(0);
        String suffix = first == '0' || first == '1' || first == '2' || first == '3'
                ? ".SZ" : first == '4' || first == '8' || first == '9' ? ".BJ" : ".SH";
        return new TargetContext(UUID.randomUUID().toString(), code + suffix,
                "测试股票", null, LocalDate.of(2026, 7, 22));
    }

    public static TradingPromptSnapshot snapshotFor(TargetContext target) {
        return snapshotFor(target,
                denny.ai.agent.trading.domain.prompt.PromptContractMode.STRICT_V2);
    }

    public static TradingPromptSnapshot snapshotFor(TargetContext target,
            denny.ai.agent.trading.domain.prompt.PromptContractMode mode) {
        int version = mode == denny.ai.agent.trading.domain.prompt.PromptContractMode.STRICT_V2 ? 2 : 3;
        var prompts = TradingPromptSet.REQUIRED_PROMPT_IDS.stream()
                .collect(Collectors.toMap(id -> id,
                        id -> new PromptVersion(id, version, mode,
                                "test-" + id, "0".repeat(64))));
        return new TradingPromptSnapshot(target.runId(), mode, version, prompts);
    }

    public static TradingStateContext stateContext(StockAnalysisRequestVO request,
                                                    DynamicContext dynamicContext,
                                                    SseEventSender sender) {
        TargetContext target = forTicker(request.getTicker());
        return new TradingStateContext(request, dynamicContext, sender,
                target, snapshotFor(target));
    }
}
