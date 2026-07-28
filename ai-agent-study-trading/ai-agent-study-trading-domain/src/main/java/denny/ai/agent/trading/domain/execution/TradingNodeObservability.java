package denny.ai.agent.trading.domain.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.prompt.PromptVersion;
import denny.ai.agent.trading.domain.validation.TradingValidationError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import denny.ai.agent.trading.api.metrics.TradingRolloutMonitor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
public class TradingNodeObservability {

    private static final Map<String, String> PROMPT_IDS = Map.ofEntries(
            Map.entry("FundamentalAnalystNode", "6002"),
            Map.entry("TechnicalAnalystNode", "6003"),
            Map.entry("SentimentAnalystNode", "6004"),
            Map.entry("NewsAnalystNode", "6005"),
            Map.entry("BullResearcherNode", "6006"),
            Map.entry("BearResearcherNode", "6007"),
            Map.entry("ResearchManagerNode", "6008"),
            Map.entry("PortfolioManagerNode", "6009"),
            Map.entry("NeutralRiskAnalystNode", "6010"),
            Map.entry("ConservativeRiskAnalystNode", "6011"),
            Map.entry("AggressiveRiskAnalystNode", "6012"),
            Map.entry("RecommendationNode", "6013"));

    private final ObjectMapper objectMapper;
    private final TradingRolloutMonitor rolloutMonitor;

    public TradingNodeObservability(ObjectMapper objectMapper) {
        this(objectMapper, new TradingRolloutMonitor());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TradingNodeObservability(ObjectMapper objectMapper,
                                    TradingRolloutMonitor rolloutMonitor) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.rolloutMonitor = Objects.requireNonNull(rolloutMonitor, "rolloutMonitor");
    }

    public NodeObservation observe(TradingStateContext context,
                                   String nodeName,
                                   String validationStatus,
                                   List<TradingValidationError> errors,
                                   long latencyMs) {
        String promptId = PROMPT_IDS.get(nodeName);
        PromptVersion prompt = promptId == null ? null : context.getPromptSnapshot().require(promptId);
        NodeObservation observation = new NodeObservation(
                context.getTargetContext().runId(), context.getTargetContext().targetId(), promptId, nodeName,
                prompt == null ? null : prompt.version(),
                prompt == null ? null : prompt.contentHash(),
                inputHash(context), context.getPromptSnapshot().mode().name(), validationStatus,
                errors == null ? List.of() : errors.stream().map(error -> error.code().name()).distinct().toList(),
                latencyMs);
        log.info("trading_node_observation={}", toJson(observation));
        rolloutMonitor.recordNode(validationStatus);
        return observation;
    }

    private String inputHash(TradingStateContext context) {
        return sha256(toJson(context.getTradingContext()));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize trading observation", error);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public record NodeObservation(
            String runId,
            String targetId,
            String clientId,
            String nodeName,
            Integer promptVersion,
            String promptHash,
            String inputSnapshotHash,
            String outputSchemaVersion,
            String validationStatus,
            List<String> validationErrors,
            long latencyMs
    ) {
    }
}
