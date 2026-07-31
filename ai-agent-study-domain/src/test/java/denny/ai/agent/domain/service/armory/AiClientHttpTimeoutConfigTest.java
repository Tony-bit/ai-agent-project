package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.service.armory.stream.SseChunkTimeoutFilter;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiClientHttpTimeoutConfigTest {

    @Test
    void should_install_only_layered_timeout_filter_by_default() {
        WebClient.Builder builder = new AiClientHttpTimeoutConfig()
                .aiClientWebClientBuilder(new AiStreamingProperties(), Schedulers.parallel());
        AtomicInteger inspected = new AtomicInteger();

        builder.filters(filters -> {
            inspected.incrementAndGet();
            assertEquals(1, filters.size());
            assertTrue(filters.get(0) instanceof SseChunkTimeoutFilter);
        });

        assertEquals(1, inspected.get());
    }

    @Test
    void should_not_install_layered_or_retry_filter_in_legacy_mode() {
        AiStreamingProperties properties = new AiStreamingProperties();
        properties.setTimeoutMode(AiStreamingProperties.TimeoutMode.LEGACY);

        WebClient.Builder builder = new AiClientHttpTimeoutConfig()
                .aiClientWebClientBuilder(properties, Schedulers.parallel());

        builder.filters(filters -> assertTrue(filters.isEmpty()));
    }
}
