package denny.ai.agent.trading.domain.prompt;

import java.util.List;
import java.util.Set;

public interface TradingPromptRepository {
    List<TradingPromptRecord> findVersionSet(Set<String> promptIds, int promptType, int version);

    List<TradingPromptRecord> findActiveSet(Set<String> promptIds, int promptType);

    void deactivateAll(Set<String> promptIds, int promptType);

    int activateVersion(Set<String> promptIds, int promptType, int version);
}
