package denny.ai.agent.domain.service.armory.stream;

import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.Optional;
import java.util.function.Function;

public final class StreamTimeoutContext {

    private static final Object POLICY_KEY = StreamChunkTimeoutPolicy.class;

    private StreamTimeoutContext() {
    }

    public static Function<Context, Context> withPolicy(StreamChunkTimeoutPolicy policy) {
        return context -> context.put(POLICY_KEY, policy);
    }

    public static Optional<StreamChunkTimeoutPolicy> findPolicy(ContextView context) {
        return context.getOrEmpty(POLICY_KEY);
    }
}
