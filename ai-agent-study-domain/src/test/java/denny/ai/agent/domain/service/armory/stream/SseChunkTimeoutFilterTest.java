package denny.ai.agent.domain.service.armory.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseChunkTimeoutFilterTest {

    @AfterEach
    void resetScheduler() {
        VirtualTimeScheduler.reset();
    }

    @Test
    void should_timeout_exchange_before_headers_and_cancel_it() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        AtomicBoolean cancelled = new AtomicBoolean();
        ExchangeFunction next = request -> Mono.<ClientResponse>never()
                .doOnCancel(() -> cancelled.set(true));

        StepVerifier.create(filter(scheduler).filter(request(), next)
                        .contextWrite(StreamTimeoutContext.withPolicy(policy(scheduler))))
                .then(() -> scheduler.advanceTimeBy(Duration.ofSeconds(45)))
                .expectError(FirstStreamChunkTimeoutException.class)
                .verify();

        assertTrue(cancelled.get());
    }

    @Test
    void should_share_one_deadline_between_headers_and_first_chunk() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        AtomicBoolean bodyCancelled = new AtomicBoolean();
        Flux<DataBuffer> body = Mono.delay(Duration.ofSeconds(30), scheduler)
                .map(ignored -> buffer("late"))
                .flux()
                .doOnCancel(() -> bodyCancelled.set(true));
        ExchangeFunction next = request -> Mono.delay(Duration.ofSeconds(20), scheduler)
                .map(ignored -> response(HttpStatus.OK, body));

        StepVerifier.create(filter(scheduler).filter(request(), next)
                        .flatMapMany(SseChunkTimeoutFilterTest::body)
                        .contextWrite(StreamTimeoutContext.withPolicy(policy(scheduler))))
                .then(() -> scheduler.advanceTimeBy(Duration.ofSeconds(44)))
                .expectNoEvent(Duration.ZERO)
                .then(() -> scheduler.advanceTimeBy(Duration.ofSeconds(1)))
                .expectError(FirstStreamChunkTimeoutException.class)
                .verify();

        assertTrue(bodyCancelled.get());
    }

    @Test
    void should_log_stall_once_then_fail_at_hard_idle_timeout() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        List<StallObserver.StallEvent> stalls = new ArrayList<>();
        AtomicBoolean bodyCancelled = new AtomicBoolean();
        Flux<DataBuffer> source = Flux.concat(Flux.just(buffer("first")), Flux.never())
                .doOnCancel(() -> bodyCancelled.set(true));
        ExchangeFunction next = request -> Mono.just(response(HttpStatus.OK, source));

        StepVerifier.create(new SseChunkTimeoutFilter(scheduler, stalls::add)
                        .filter(request(), next)
                        .flatMapMany(SseChunkTimeoutFilterTest::body)
                        .contextWrite(StreamTimeoutContext.withPolicy(policy(scheduler))))
                .expectNextCount(1)
                .then(() -> scheduler.advanceTimeBy(Duration.ofSeconds(30)))
                .then(() -> assertEquals(1, stalls.size()))
                .then(() -> scheduler.advanceTimeBy(Duration.ofSeconds(59)))
                .then(() -> assertEquals(1, stalls.size()))
                .then(() -> scheduler.advanceTimeBy(Duration.ofSeconds(1)))
                .expectError(StreamChunkIdleTimeoutException.class)
                .verify();

        assertTrue(bodyCancelled.get());
    }

    @Test
    void should_bypass_non_success_response_body_watchdog() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<DataBuffer> body = Flux.<DataBuffer>never()
                .doOnSubscribe(ignored -> subscriptions.incrementAndGet());
        ExchangeFunction next = request -> Mono.just(response(HttpStatus.TOO_MANY_REQUESTS, body));

        StepVerifier.create(filter(scheduler).filter(request(), next)
                        .contextWrite(StreamTimeoutContext.withPolicy(policy(scheduler))))
                .assertNext(value -> assertEquals(HttpStatus.TOO_MANY_REQUESTS, value.statusCode()))
                .verifyComplete();

        scheduler.advanceTimeBy(Duration.ofMinutes(5));
        assertEquals(0, subscriptions.get());
    }

    @Test
    void should_fail_closed_before_exchange_when_policy_is_missing() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        AtomicInteger exchanges = new AtomicInteger();
        ExchangeFunction next = request -> {
            exchanges.incrementAndGet();
            return Mono.just(response(HttpStatus.OK, Flux.empty()));
        };

        StepVerifier.create(filter(scheduler).filter(request(), next))
                .expectError(MissingStreamTimeoutPolicyException.class)
                .verify();

        assertEquals(0, exchanges.get());
    }

    @Test
    void should_subscribe_original_body_only_once() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<DataBuffer> body = Flux.just(buffer("one"), buffer("two"))
                .doOnSubscribe(ignored -> subscriptions.incrementAndGet());
        ExchangeFunction next = request -> Mono.just(response(HttpStatus.OK, body));

        StepVerifier.create(filter(scheduler).filter(request(), next)
                        .flatMapMany(SseChunkTimeoutFilterTest::body)
                        .contextWrite(StreamTimeoutContext.withPolicy(policy(scheduler))))
                .expectNextCount(2)
                .verifyComplete();

        assertEquals(1, subscriptions.get());
    }

    @Test
    void should_preserve_empty_completion_and_upstream_error() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        ExchangeFunction empty = request -> Mono.just(response(HttpStatus.OK, Flux.empty()));

        StepVerifier.create(filter(scheduler).filter(request(), empty)
                        .flatMapMany(SseChunkTimeoutFilterTest::body)
                        .contextWrite(StreamTimeoutContext.withPolicy(policy(scheduler))))
                .verifyComplete();

        RuntimeException failure = new RuntimeException("decode failed");
        ExchangeFunction error = request -> Mono.just(response(HttpStatus.OK, Flux.error(failure)));
        StepVerifier.create(filter(scheduler).filter(request(), error)
                        .flatMapMany(SseChunkTimeoutFilterTest::body)
                        .contextWrite(StreamTimeoutContext.withPolicy(policy(scheduler))))
                .expectErrorMatches(actual -> actual == failure)
                .verify();
    }

    @Test
    void should_cancel_original_body_when_downstream_cancels() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<DataBuffer> source = Flux.concat(Flux.just(buffer("first")), Flux.never())
                .doOnCancel(() -> cancelled.set(true));
        ExchangeFunction next = request -> Mono.just(response(HttpStatus.OK, source));

        StepVerifier.create(filter(scheduler).filter(request(), next)
                        .flatMapMany(SseChunkTimeoutFilterTest::body)
                        .contextWrite(StreamTimeoutContext.withPolicy(policy(scheduler))))
                .expectNextCount(1)
                .thenCancel()
                .verify();

        assertTrue(cancelled.get());
    }

    @Test
    void should_prefer_query_attempt_timeout_when_deadlines_are_equal() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        long startedAt = scheduler.now(TimeUnit.NANOSECONDS);
        StreamChunkTimeoutPolicy equalDeadlines = new StreamChunkTimeoutPolicy(
                Duration.ofSeconds(45), Duration.ofSeconds(30), Duration.ofSeconds(40),
                Duration.ofSeconds(45), startedAt,
                startedAt + Duration.ofSeconds(45).toNanos(), "call-1", "model-1");
        ExchangeFunction next = request -> Mono.never();

        StepVerifier.create(filter(scheduler).filter(request(), next)
                        .contextWrite(StreamTimeoutContext.withPolicy(equalDeadlines)))
                .then(() -> scheduler.advanceTimeBy(Duration.ofSeconds(45)))
                .expectError(LlmQueryAttemptTimeoutException.class)
                .verify();
    }

    private SseChunkTimeoutFilter filter(VirtualTimeScheduler scheduler) {
        return new SseChunkTimeoutFilter(scheduler, ignored -> { });
    }

    private StreamChunkTimeoutPolicy policy(VirtualTimeScheduler scheduler) {
        long startedAt = scheduler.now(TimeUnit.NANOSECONDS);
        return new StreamChunkTimeoutPolicy(Duration.ofSeconds(45), Duration.ofSeconds(30),
                Duration.ofSeconds(90), Duration.ofSeconds(150), startedAt,
                startedAt + Duration.ofSeconds(150).toNanos(), "call-1", "model-1");
    }

    private ClientRequest request() {
        return ClientRequest.create(HttpMethod.GET,
                        URI.create("https://example.test/v1/chat/completions"))
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .build();
    }

    private static ClientResponse response(HttpStatus status, Flux<DataBuffer> body) {
        return ClientResponse.create(status)
                .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                .body(body)
                .build();
    }

    private static Flux<DataBuffer> body(ClientResponse response) {
        return response.body(BodyExtractors.toDataBuffers());
    }

    private static DataBuffer buffer(String value) {
        return DefaultDataBufferFactory.sharedInstance.wrap(value.getBytes());
    }
}
