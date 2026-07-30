package denny.ai.agent.domain.service.armory;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiClientHttpTimeoutConfigTest {

    @Test
    void should_not_install_webclient_retry_filter() {
        WebClient.Builder builder = new AiClientHttpTimeoutConfig()
                .aiClientWebClientBuilder(new AiStreamingProperties());
        AtomicInteger inspected = new AtomicInteger();

        builder.filters(filters -> {
            inspected.incrementAndGet();
            assertTrue(filters.isEmpty());
        });

        assertEquals(1, inspected.get());
    }
}
