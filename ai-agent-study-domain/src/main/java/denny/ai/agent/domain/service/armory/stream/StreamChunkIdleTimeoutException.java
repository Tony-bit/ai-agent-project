package denny.ai.agent.domain.service.armory.stream;

import java.time.Duration;

public final class StreamChunkIdleTimeoutException extends LlmTimeoutException {

    public StreamChunkIdleTimeoutException(Duration configuredTimeout,
                                           Duration effectiveTimeout,
                                           TimeoutDeadlineOwner deadlineOwner,
                                           Duration elapsed,
                                           long observedChunkCount,
                                           String logicalCallId,
                                           String modelId) {
        super("Timed out waiting for the next SSE body chunk", configuredTimeout,
                effectiveTimeout, deadlineOwner, elapsed, observedChunkCount,
                logicalCallId, modelId);
    }
}
