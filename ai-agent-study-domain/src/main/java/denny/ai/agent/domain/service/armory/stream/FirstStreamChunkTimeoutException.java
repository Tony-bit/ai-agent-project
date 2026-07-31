package denny.ai.agent.domain.service.armory.stream;

import java.time.Duration;

public final class FirstStreamChunkTimeoutException extends LlmTimeoutException {

    public FirstStreamChunkTimeoutException(Duration configuredTimeout,
                                            Duration effectiveTimeout,
                                            TimeoutDeadlineOwner deadlineOwner,
                                            Duration elapsed,
                                            long observedChunkCount,
                                            String logicalCallId,
                                            String modelId) {
        super("Timed out waiting for the first SSE body chunk", configuredTimeout,
                effectiveTimeout, deadlineOwner, elapsed, observedChunkCount,
                logicalCallId, modelId);
    }
}
