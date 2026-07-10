package denny.ai.agent.domain.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Options for loading a conversation memory snapshot.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConversationMemoryOptions {

    public static final int DEFAULT_WINDOW_SIZE = 20;

    @Builder.Default
    private int windowSize = DEFAULT_WINDOW_SIZE;

    @Builder.Default
    private boolean allowRuntimeWindow = true;

    @Builder.Default
    private boolean requireDurable = false;

    public static ConversationMemoryOptions defaults() {
        return ConversationMemoryOptions.builder().build();
    }
}
