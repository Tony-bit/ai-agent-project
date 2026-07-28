package denny.ai.agent.trading.domain.support;

import denny.ai.agent.trading.domain.prompt.PromptContractMode;
import denny.ai.agent.trading.domain.prompt.TradingPromptRecord;

import java.util.List;
import java.util.Map;

public final class TradingPromptFixtures {

    public static final Map<String, String> STRICT_V2 = Map.ofEntries(
            Map.entry("6002", "{{targetContext}}\n{{stockData}}\n{{outputContract}}"),
            Map.entry("6003", "{{targetContext}}\n{{stockData}}\n{{outputContract}}"),
            Map.entry("6004", "{{targetContext}}\n{{stockData}}\n{{outputContract}}"),
            Map.entry("6005", "{{targetContext}}\n{{stockData}}\n{{outputContract}}"),
            Map.entry("6006", "{{targetContext}}\n{{analystReports}}\n{{debateHistory}}\n{{outputContract}}"),
            Map.entry("6007", "{{targetContext}}\n{{analystReports}}\n{{debateHistory}}\n{{outputContract}}"),
            Map.entry("6008", "{{targetContext}}\n{{analystReports}}\n{{debateHistory}}\n{{validationStatus}}\n{{currentRound}}\n{{outputContract}}"),
            Map.entry("6009", "{{targetContext}}\n{{analystReports}}\n{{debateHistory}}\n{{riskReports}}\n{{validationStatus}}\n{{outputContract}}"),
            Map.entry("6010", "{{targetContext}}\n{{investmentPlan}}\n{{riskReports}}\n{{outputContract}}"),
            Map.entry("6011", "{{targetContext}}\n{{investmentPlan}}\n{{riskReports}}\n{{outputContract}}"),
            Map.entry("6012", "{{targetContext}}\n{{investmentPlan}}\n{{riskReports}}\n{{outputContract}}"),
            Map.entry("6013", "{{targetContext}}\n{{analystReports}}\n{{debateHistory}}\n{{validationStatus}}\n{{outputContract}}"));

    public static final Map<String, String> RELAXED_V3 = Map.ofEntries(
            Map.entry("6002", "{{targetContext}}\n{{stockData}}"),
            Map.entry("6003", "{{targetContext}}\n{{stockData}}"),
            Map.entry("6004", "{{targetContext}}\n{{stockData}}"),
            Map.entry("6005", "{{targetContext}}\n{{stockData}}"),
            Map.entry("6006", "{{targetContext}}\n{{analystReports}}\n{{debateHistory}}"),
            Map.entry("6007", "{{targetContext}}\n{{analystReports}}\n{{debateHistory}}"),
            Map.entry("6008", "{{targetContext}}\n{{analystReports}}\n{{debateHistory}}\n{{validationStatus}}\n{{currentRound}}\n{{minimalOutputContract}}"),
            Map.entry("6009", "{{targetContext}}\n{{analystReports}}\n{{debateHistory}}\n{{riskReports}}\n{{validationStatus}}\n{{minimalOutputContract}}"),
            Map.entry("6010", "{{targetContext}}\n{{investmentPlan}}\n{{riskReports}}"),
            Map.entry("6011", "{{targetContext}}\n{{investmentPlan}}\n{{riskReports}}"),
            Map.entry("6012", "{{targetContext}}\n{{investmentPlan}}\n{{riskReports}}"),
            Map.entry("6013", "{{targetContext}}\n{{analystReports}}\n{{debateHistory}}\n{{validationStatus}}\n{{minimalOutputContract}}"));

    private TradingPromptFixtures() {
    }

    public static List<TradingPromptRecord> records(PromptContractMode mode, boolean active) {
        int version = mode == PromptContractMode.STRICT_V2 ? 2 : 3;
        Map<String, String> templates = mode == PromptContractMode.STRICT_V2 ? STRICT_V2 : RELAXED_V3;
        return templates.entrySet().stream()
                .map(entry -> new TradingPromptRecord(Long.valueOf(entry.getKey()), entry.getKey(),
                        2, version, entry.getValue(), active))
                .toList();
    }
}
