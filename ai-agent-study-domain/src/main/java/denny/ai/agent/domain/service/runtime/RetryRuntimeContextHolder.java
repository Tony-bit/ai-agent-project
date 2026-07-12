package denny.ai.agent.domain.service.runtime;

import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

public final class RetryRuntimeContextHolder {

    private static final ThreadLocal<Deque<RetryRuntimeContext>> CONTEXTS =
            ThreadLocal.withInitial(ArrayDeque::new);

    private RetryRuntimeContextHolder() {
    }

    public static <T> T withContext(RetryRuntimeContext context, Supplier<T> supplier) {
        try {
            return withContextThrowing(context, supplier::get);
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Unexpected checked exception", error);
        }
    }

    public static <T, E extends Exception> T withContextThrowing(
            RetryRuntimeContext context, ThrowingSupplier<T, E> supplier) throws E {
        if (context == null) {
            return supplier.get();
        }
        Deque<RetryRuntimeContext> stack = CONTEXTS.get();
        stack.push(context);
        try {
            return supplier.get();
        } finally {
            stack.pop();
            if (stack.isEmpty()) {
                CONTEXTS.remove();
            }
        }
    }

    public static RetryRuntimeContext current() {
        Deque<RetryRuntimeContext> stack = CONTEXTS.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T, E extends Exception> {
        T get() throws E;
    }
}
