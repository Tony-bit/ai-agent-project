package denny.ai.agent.domain.service.armory.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StallObserverTest {

    @AfterEach
    void resetScheduler() {
        VirtualTimeScheduler.reset();
    }

    @Test
    void should_log_each_chunk_gap_at_most_once() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        List<StallObserver.StallEvent> events = new ArrayList<>();
        StallObserver observer = new StallObserver(Duration.ofSeconds(30), scheduler,
                "call-1", "model-1", events::add);

        observer.onChunk();
        scheduler.advanceTimeBy(Duration.ofSeconds(29));
        assertTrue(events.isEmpty());

        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        assertEquals(1, events.size());
        assertEquals(1, events.get(0).observedChunkCount());

        scheduler.advanceTimeBy(Duration.ofSeconds(59));
        assertEquals(1, events.size());

        observer.onChunk();
        scheduler.advanceTimeBy(Duration.ofSeconds(30));
        assertEquals(2, events.size());
        assertEquals(2, events.get(1).observedChunkCount());
    }

    @Test
    void should_invalidate_previous_generation_when_new_chunk_arrives() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        List<StallObserver.StallEvent> events = new ArrayList<>();
        StallObserver observer = new StallObserver(Duration.ofSeconds(30), scheduler,
                "call-1", "model-1", events::add);

        observer.onChunk();
        scheduler.advanceTimeBy(Duration.ofSeconds(20));
        observer.onChunk();
        scheduler.advanceTimeBy(Duration.ofSeconds(10));
        assertTrue(events.isEmpty());

        scheduler.advanceTimeBy(Duration.ofSeconds(20));
        assertEquals(1, events.size());
        assertEquals(2, events.get(0).observedChunkCount());
    }

    @Test
    void should_dispose_task_on_termination() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        List<StallObserver.StallEvent> events = new ArrayList<>();
        StallObserver observer = new StallObserver(Duration.ofSeconds(30), scheduler,
                "call-1", "model-1", events::add);

        observer.onChunk();
        observer.terminate();
        scheduler.advanceTimeBy(Duration.ofMinutes(5));

        assertTrue(events.isEmpty());
        assertTrue(observer.isTerminated());
        assertEquals(1, observer.observedChunkCount());
    }
}
