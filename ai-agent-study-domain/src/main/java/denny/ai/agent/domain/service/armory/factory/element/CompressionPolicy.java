package denny.ai.agent.domain.service.armory.factory.element;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CompressionPolicy {

    boolean enabled;
    String compressionModelId;
    int proactiveThresholdTokens;
    int maxCompressionAttempts;
    int maxSummaryTokens;
    String promptTemplate;
}
