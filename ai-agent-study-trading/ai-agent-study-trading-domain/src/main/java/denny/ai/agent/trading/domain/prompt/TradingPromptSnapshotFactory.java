package denny.ai.agent.trading.domain.prompt;

import denny.ai.agent.trading.api.vo.TargetContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class TradingPromptSnapshotFactory {

    private final TradingPromptRepository repository;
    private final TradingPromptRenderer renderer;

    public TradingPromptSnapshotFactory(TradingPromptRepository repository,
                                        TradingPromptRenderer renderer) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    }

    public TradingPromptSnapshot create(TargetContext targetContext) {
        Objects.requireNonNull(targetContext, "targetContext must not be null");
        List<TradingPromptRecord> records = repository.findActiveSet(
                TradingPromptSet.REQUIRED_PROMPT_IDS, TradingPromptSet.STEP_PROMPT_TYPE);
        if (records == null || records.size() != TradingPromptSet.REQUIRED_PROMPT_IDS.size()) {
            throw new IllegalStateException("active trading prompt set is incomplete");
        }

        Map<String, PromptVersion> prompts = new LinkedHashMap<>();
        Integer version = null;
        for (TradingPromptRecord record : records) {
            if (record == null || !record.active()
                    || record.promptType() != TradingPromptSet.STEP_PROMPT_TYPE
                    || !TradingPromptSet.REQUIRED_PROMPT_IDS.contains(record.promptId())
                    || prompts.containsKey(record.promptId())) {
                throw new IllegalStateException("active trading prompt set contains invalid records");
            }
            if (version == null) {
                version = record.version();
            } else if (version != record.version()) {
                throw new IllegalStateException("active trading prompt set mixes versions");
            }
            PromptContractMode mode = PromptContractMode.fromVersion(record.version());
            try {
                renderer.validateTemplate(mode, record.promptId(), record.content());
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException("active trading prompt template is invalid: "
                        + record.promptId(), error);
            }
            PromptVersion prompt = new PromptVersion(record.promptId(), record.version(), mode,
                    record.content(), sha256(record.content()));
            prompts.put(record.promptId(), prompt);
        }
        if (version == null) {
            throw new IllegalStateException("active trading prompt set is empty");
        }
        return new TradingPromptSnapshot(targetContext.runId(),
                PromptContractMode.fromVersion(version), version, prompts);
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
