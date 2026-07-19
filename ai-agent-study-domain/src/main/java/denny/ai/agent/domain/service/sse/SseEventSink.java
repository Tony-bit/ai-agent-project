package denny.ai.agent.domain.service.sse;

import reactor.core.publisher.Mono;

public interface SseEventSink {

    boolean sendBusiness(String eventName, Object payload);

    boolean trySendHeartbeat();

    void complete();

    void markDisconnected(Throwable cause);

    boolean isDisconnected();

    boolean shouldContinue();

    SseSessionState state();

    default Mono<Void> cancellationSignal() {
        return Mono.never();
    }
}
