package denny.ai.agent.domain.service.armory.stream;

import java.time.Duration;

public final class LlmQueryAttemptTimeoutException extends LlmTimeoutException {

    public LlmQueryAttemptTimeoutException(Duration configuredTimeout,
                                           Duration effectiveTimeout,
                                           TimeoutDeadlineOwner deadlineOwner,
                                           Duration elapsed,
                                           long observedChunkCount,
                                           String logicalCallId,
                                           String modelId) {
        super("LLM query attempt exceeded its absolute deadline", configuredTimeout,
                effectiveTimeout, deadlineOwner, elapsed, observedChunkCount,
                logicalCallId, modelId);
    }
}
