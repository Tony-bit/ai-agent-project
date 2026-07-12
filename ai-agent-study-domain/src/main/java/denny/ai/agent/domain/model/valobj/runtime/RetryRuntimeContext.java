package denny.ai.agent.domain.model.valobj.runtime;

import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Objects;

@Value
@Builder
public class RetryRuntimeContext {

    String sessionId;
    String traceId;
    boolean compressionCall;

    @Builder.Default
    List<ChatMessageEntity> recentMessages = List.of();

    public static RetryRuntimeContext from(TurnRuntimeContext turn) {
        Objects.requireNonNull(turn, "turn must not be null");
        SessionRuntimeContext session = turn.getSessionRuntimeContext();
        List<ChatMessageEntity> source = session == null ? List.of() : session.getRecentMessages();
        List<ChatMessageEntity> safeMessages = source == null
                ? List.of()
                : List.copyOf(source.stream().filter(Objects::nonNull).toList());
        return RetryRuntimeContext.builder()
                .sessionId(turn.getSessionId())
                .traceId(turn.getTraceId())
                .compressionCall(false)
                .recentMessages(safeMessages)
                .build();
    }

    public RetryRuntimeContext forCompressionCall() {
        return new RetryRuntimeContext(sessionId, traceId, true, recentMessages);
    }
}
