package denny.ai.agent.trading.domain.prompt;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class TradingPromptActivationService {

    private final TradingPromptRepository repository;
    private final TradingPromptRenderer renderer;

    public TradingPromptActivationService(TradingPromptRepository repository,
                                          TradingPromptRenderer renderer) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    }

    @Transactional(rollbackFor = Exception.class)
    public void activateCompleteVersion(int version) {
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        Set<String> required = TradingPromptSet.REQUIRED_PROMPT_IDS;
        List<TradingPromptRecord> records = repository.findVersionSet(
                required, TradingPromptSet.STEP_PROMPT_TYPE, version);
        validateCompleteSet(records, version);

        repository.deactivateAll(required, TradingPromptSet.STEP_PROMPT_TYPE);
        int activated = repository.activateVersion(
                required, TradingPromptSet.STEP_PROMPT_TYPE, version);
        if (activated != required.size()) {
            throw new IllegalStateException("activated prompt count mismatch: " + activated);
        }
    }

    private void validateCompleteSet(List<TradingPromptRecord> records, int version) {
        if (records == null || records.size() != TradingPromptSet.REQUIRED_PROMPT_IDS.size()) {
            throw new IllegalStateException("trading prompt version set is incomplete");
        }
        Set<String> seen = new HashSet<>();
        for (TradingPromptRecord record : records) {
            if (record == null
                    || record.version() != version
                    || record.promptType() != TradingPromptSet.STEP_PROMPT_TYPE
                    || !TradingPromptSet.REQUIRED_PROMPT_IDS.contains(record.promptId())
                    || !seen.add(record.promptId())) {
                throw new IllegalStateException("trading prompt version set contains invalid records");
            }
            try {
                renderer.validateTemplate(record.promptId(), record.content());
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException("invalid trading prompt template: " + record.promptId(), error);
            }
        }
    }
}
