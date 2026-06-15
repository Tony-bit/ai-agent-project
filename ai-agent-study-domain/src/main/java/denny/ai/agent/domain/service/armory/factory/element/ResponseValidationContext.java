package denny.ai.agent.domain.service.armory.factory.element;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

public final class ResponseValidationContext {

    private static final ThreadLocal<Deque<ChatResponseValidator>> VALIDATORS =
            ThreadLocal.withInitial(ArrayDeque::new);

    private ResponseValidationContext() {
    }

    public static <T> T withValidator(ChatResponseValidator validator, Supplier<T> supplier) {
        if (validator == null) {
            return supplier.get();
        }
        Deque<ChatResponseValidator> stack = VALIDATORS.get();
        stack.push(validator);
        try {
            return supplier.get();
        } finally {
            stack.pop();
            if (stack.isEmpty()) {
                VALIDATORS.remove();
            }
        }
    }

    public static ChatResponseValidator currentValidator() {
        Deque<ChatResponseValidator> stack = VALIDATORS.get();
        return stack.isEmpty() ? null : stack.peek();
    }
}
