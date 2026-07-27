package denny.ai.agent.trading.domain.prompt;

import java.util.LinkedHashSet;
import java.util.Set;

public final class TradingPromptSet {
    public static final int STEP_PROMPT_TYPE = 2;
    public static final Set<String> REQUIRED_PROMPT_IDS = Set.copyOf(new LinkedHashSet<>(Set.of(
            "6002", "6003", "6004", "6005", "6006", "6007",
            "6008", "6009", "6010", "6011", "6012", "6013")));

    private TradingPromptSet() {
    }
}
