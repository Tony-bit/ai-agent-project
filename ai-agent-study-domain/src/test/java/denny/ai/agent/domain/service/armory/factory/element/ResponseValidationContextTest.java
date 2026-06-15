package denny.ai.agent.domain.service.armory.factory.element;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ResponseValidationContextTest {

    @Test
    public void shouldReturnNullWhenValidatorIsNotRegistered() {
        assertNull(ResponseValidationContext.currentValidator());
    }

    @Test
    public void shouldCleanupValidatorAfterSuccess() {
        ChatResponseValidator validator = response -> { };

        String result = ResponseValidationContext.withValidator(validator, () -> {
            assertSame(validator, ResponseValidationContext.currentValidator());
            return "ok";
        });

        assertEquals("ok", result);
        assertNull(ResponseValidationContext.currentValidator());
    }

    @Test
    public void shouldCleanupValidatorAfterException() {
        ChatResponseValidator validator = response -> { };

        assertThrows(IllegalStateException.class, () ->
                ResponseValidationContext.withValidator(validator, () -> {
                    throw new IllegalStateException("boom");
                }));

        assertNull(ResponseValidationContext.currentValidator());
    }

    @Test
    public void shouldRestoreOuterValidatorAfterNestedCall() {
        ChatResponseValidator outer = response -> { };
        ChatResponseValidator inner = response -> { };

        ResponseValidationContext.withValidator(outer, () -> {
            assertSame(outer, ResponseValidationContext.currentValidator());
            ResponseValidationContext.withValidator(inner, () -> {
                assertSame(inner, ResponseValidationContext.currentValidator());
                return null;
            });
            assertSame(outer, ResponseValidationContext.currentValidator());
            return null;
        });

        assertNull(ResponseValidationContext.currentValidator());
    }

    @Test
    public void shouldIsolateValidatorsAcrossThreads() throws Exception {
        ChatResponseValidator main = response -> { };
        ChatResponseValidator worker = response -> { };
        AtomicReference<ChatResponseValidator> workerSeen = new AtomicReference<>();

        ResponseValidationContext.withValidator(main, () -> {
            Thread thread = new Thread(() ->
                    ResponseValidationContext.withValidator(worker, () -> {
                        workerSeen.set(ResponseValidationContext.currentValidator());
                        return null;
                    }));
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            assertSame(main, ResponseValidationContext.currentValidator());
            return null;
        });

        assertSame(worker, workerSeen.get());
        assertNull(ResponseValidationContext.currentValidator());
    }
}
