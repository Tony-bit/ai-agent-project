package denny.ai.agent.trading.trigger.http;

import java.time.Instant;

record SseOutboundEvent(
        SseOutboundType type,
        String eventName,
        Object payload,
        String requestId,
        String sessionId,
        String analystType,
        long eventId,
        Instant timestamp,
        String comment
) {

    static SseOutboundEvent business(String eventName,
                                     Object payload,
                                     String requestId,
                                     String sessionId,
                                     String analystType,
                                     long eventId) {
        return new SseOutboundEvent(
                SseOutboundType.BUSINESS,
                eventName,
                payload,
                requestId,
                sessionId,
                analystType,
                eventId,
                Instant.now(),
                null
        );
    }

    static SseOutboundEvent heartbeat(String requestId, String sessionId, long eventId) {
        return new SseOutboundEvent(
                SseOutboundType.HEARTBEAT,
                null,
                null,
                requestId,
                sessionId,
                null,
                eventId,
                Instant.now(),
                "heartbeat"
        );
    }

    static SseOutboundEvent complete(String requestId, String sessionId, long eventId) {
        return new SseOutboundEvent(
                SseOutboundType.COMPLETE,
                null,
                null,
                requestId,
                sessionId,
                null,
                eventId,
                Instant.now(),
                null
        );
    }
}
