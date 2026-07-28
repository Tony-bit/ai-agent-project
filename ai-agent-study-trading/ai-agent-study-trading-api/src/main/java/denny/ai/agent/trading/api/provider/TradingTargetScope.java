package denny.ai.agent.trading.api.provider;

import denny.ai.agent.trading.api.vo.TargetContext;

import java.util.Objects;
import java.util.function.Supplier;

public final class TradingTargetScope {

    private static final ThreadLocal<TargetContext> CURRENT = new ThreadLocal<>();

    private TradingTargetScope() {
    }

    public static <T> T call(TargetContext target, Supplier<T> invocation) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(invocation, "invocation");
        TargetContext previous = CURRENT.get();
        CURRENT.set(target);
        try {
            return invocation.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static TargetContext requireTarget() {
        TargetContext target = CURRENT.get();
        if (target == null) {
            throw new IllegalStateException("IDENTITY_BOUNDARY_VIOLATION: trading target scope is missing");
        }
        return target;
    }

    public static TargetContext currentTarget() {
        return CURRENT.get();
    }
}
