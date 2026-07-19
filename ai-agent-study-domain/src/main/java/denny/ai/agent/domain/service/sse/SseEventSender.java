package denny.ai.agent.domain.service.sse;

@FunctionalInterface
public interface SseEventSender {

    boolean send(String eventName, Object payload);
}
