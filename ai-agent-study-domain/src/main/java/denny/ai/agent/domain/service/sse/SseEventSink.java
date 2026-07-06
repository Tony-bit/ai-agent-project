package denny.ai.agent.domain.service.sse;

public interface SseEventSink {

    boolean sendBusiness(String eventName, Object payload);

    boolean trySendHeartbeat();

    void complete();

    void markDisconnected(Throwable cause);

    boolean isDisconnected();

    boolean shouldContinue();

    SseSessionState state();
}
