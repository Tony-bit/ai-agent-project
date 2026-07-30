package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetryChatModelAtomicAttemptTest {

    @Test
    void should_not_emit_attempt_responses_before_completion() {
        ChatModel delegate = mock(ChatModel.class);
        Prompt prompt = prompt();
        TestPublisher<ChatResponse> publisher = TestPublisher.create();
        ChatResponse first = response("first");
        ChatResponse second = response("second");
        when(delegate.stream(prompt)).thenReturn(publisher.flux());

        StepVerifier.create(model(delegate, 2, 0).stream(prompt))
                .expectSubscription()
                .then(() -> publisher.next(first, second))
                .expectNoEvent(Duration.ofMillis(10))
                .then(publisher::complete)
                .expectNext(first, second)
                .verifyComplete();
    }

    @Test
    void should_discard_failed_attempt_and_emit_only_successful_attempt() {
        ChatModel delegate = mock(ChatModel.class);
        Prompt prompt = prompt();
        ChatResponse discarded = response("discarded");
        ChatResponse success = response("success");
        when(delegate.stream(prompt))
                .thenReturn(Flux.concat(Flux.just(discarded), Flux.error(reset())))
                .thenReturn(Flux.just(success));

        StepVerifier.create(model(delegate, 2, 0).stream(prompt))
                .expectNext(success)
                .verifyComplete();

        verify(delegate, times(2)).stream(prompt);
    }

    @Test
    void should_discard_all_partial_results_and_propagate_last_error_when_exhausted() {
        ChatModel delegate = mock(ChatModel.class);
        Prompt prompt = prompt();
        RuntimeException firstError = reset();
        RuntimeException finalError = reset();
        when(delegate.stream(prompt))
                .thenReturn(Flux.concat(Flux.just(response("discard-1")), Flux.error(firstError)))
                .thenReturn(Flux.concat(Flux.just(response("discard-2")), Flux.error(finalError)));

        StepVerifier.create(model(delegate, 2, 0).stream(prompt))
                .expectErrorMatches(error -> error == finalError)
                .verify();
    }

    @Test
    void should_accept_normal_completion_without_done_marker_inspection() {
        ChatModel delegate = mock(ChatModel.class);
        Prompt prompt = prompt();
        ChatResponse response = response("complete");
        when(delegate.stream(prompt)).thenReturn(Flux.just(response));

        StepVerifier.create(model(delegate, 2, 0).stream(prompt))
                .expectNext(response)
                .verifyComplete();

        verify(delegate, times(1)).stream(prompt);
    }

    @Test
    void should_finish_previous_attempt_before_subscribing_next_attempt() {
        ChatModel delegate = mock(ChatModel.class);
        Prompt prompt = prompt();
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicBoolean secondStartedAfterCleanup = new AtomicBoolean();
        ChatResponse success = response("success");
        when(delegate.stream(prompt)).thenAnswer(invocation -> Flux.defer(() -> {
            int attempt = subscriptions.incrementAndGet();
            if (attempt == 2) {
                secondStartedAfterCleanup.set(active.get() == 0);
            }
            active.incrementAndGet();
            Flux<ChatResponse> source = attempt == 1
                    ? Flux.concat(Flux.just(response("discarded")), Flux.error(reset()))
                    : Flux.just(success);
            return source.doFinally(signal -> active.decrementAndGet());
        }));

        StepVerifier.create(model(delegate, 2, 0).stream(prompt))
                .expectNext(success)
                .verifyComplete();

        assertEquals(2, subscriptions.get());
        assertEquals(0, active.get());
        assertTrue(secondStartedAfterCleanup.get());
    }

    @Test
    void should_cancel_active_attempt_once_without_retry() {
        ChatModel delegate = mock(ChatModel.class);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.<ChatResponse>never()
                .doOnSubscribe(ignored -> active.incrementAndGet())
                .doOnCancel(cancelled::incrementAndGet)
                .doFinally(signal -> active.decrementAndGet()));

        StepVerifier.create(model(delegate, 2, 0).stream(prompt()))
                .thenCancel()
                .verify();

        assertEquals(1, cancelled.get());
        assertEquals(0, active.get());
        verify(delegate, times(1)).stream(any(Prompt.class));
    }

    @Test
    void should_cancel_backoff_without_starting_next_attempt() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        try {
            ChatModel delegate = mock(ChatModel.class);
            AtomicInteger subscriptions = new AtomicInteger();
            AtomicInteger active = new AtomicInteger();
            when(delegate.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.defer(() -> {
                subscriptions.incrementAndGet();
                active.incrementAndGet();
                return Flux.<ChatResponse>error(reset())
                        .doFinally(signal -> active.decrementAndGet());
            }));

            Disposable subscription = model(delegate, 3, 1000).stream(prompt()).subscribe();
            assertEquals(1, subscriptions.get());
            assertEquals(0, active.get());

            subscription.dispose();
            scheduler.advanceTimeBy(Duration.ofMinutes(5));

            assertEquals(1, subscriptions.get());
            assertEquals(0, active.get());
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    void should_isolate_attempt_state_between_concurrent_subscriptions() {
        ChatModel delegate = mock(ChatModel.class);
        AtomicInteger calls = new AtomicInteger();
        ConcurrentMap<Prompt, AtomicInteger> callsByPrompt = new ConcurrentHashMap<>();
        when(delegate.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt currentPrompt = invocation.getArgument(0);
            calls.incrementAndGet();
            int promptCall = callsByPrompt.computeIfAbsent(
                    currentPrompt, ignored -> new AtomicInteger()).incrementAndGet();
            return promptCall == 1
                    ? Flux.error(reset())
                    : Flux.just(response("success-" + currentPrompt.getContents()));
        });
        RetryChatModel model = model(delegate, 2, 0);
        Prompt firstPrompt = new Prompt(new UserMessage("first"));
        Prompt secondPrompt = new Prompt(new UserMessage("second"));

        List<String> results = Flux.merge(model.stream(firstPrompt), model.stream(secondPrompt))
                .map(value -> value.getResult().getOutput().getText())
                .collectList()
                .block();

        assertEquals(2, results.size());
        assertEquals(4, calls.get());
        assertEquals(2, callsByPrompt.get(firstPrompt).get());
        assertEquals(2, callsByPrompt.get(secondPrompt).get());
    }

    @Test
    void should_complete_empty_success_without_retry() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.empty());

        StepVerifier.create(model(delegate, 3, 0).stream(prompt()))
                .verifyComplete();

        verify(delegate, times(1)).stream(any(Prompt.class));
    }

    @Test
    void should_apply_stream_retry_attempt_limits() {
        ChatModel disabledDelegate = mock(ChatModel.class);
        when(disabledDelegate.stream(any(Prompt.class))).thenReturn(Flux.error(reset()));
        StepVerifier.create(model(disabledDelegate, false, 5, 0).stream(prompt()))
                .expectError()
                .verify();
        verify(disabledDelegate, times(1)).stream(any(Prompt.class));

        ChatModel singleDelegate = mock(ChatModel.class);
        when(singleDelegate.stream(any(Prompt.class))).thenReturn(Flux.error(reset()));
        StepVerifier.create(model(singleDelegate, true, 1, 0).stream(prompt()))
                .expectError()
                .verify();
        verify(singleDelegate, times(1)).stream(any(Prompt.class));

        ChatModel cappedDelegate = mock(ChatModel.class);
        when(cappedDelegate.stream(any(Prompt.class))).thenReturn(Flux.error(reset()));
        StepVerifier.create(model(cappedDelegate, true, 100, 0).stream(prompt()))
                .expectError()
                .verify();
        verify(cappedDelegate, times(10)).stream(any(Prompt.class));
    }

    private RetryChatModel model(ChatModel delegate, int maxAttempts, long intervalMs) {
        return model(delegate, true, maxAttempts, intervalMs);
    }

    private RetryChatModel model(ChatModel delegate, boolean enabled,
                                 int maxAttempts, long intervalMs) {
        RetryConfig retryConfig = RetryConfig.builder()
                .enabled(enabled)
                .maxAttempts(maxAttempts)
                .initialIntervalMs(intervalMs)
                .maxIntervalMs(intervalMs)
                .build();
        return new RetryChatModel(delegate, retryConfig);
    }

    private Prompt prompt() {
        return new Prompt(new UserMessage("question"));
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private RuntimeException reset() {
        return new RuntimeException("connection reset by peer");
    }
}
