package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.StreamingTimeoutConfig;
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class AiStreamingPropertiesTest {

    @Test
    public void should_use_documented_defaults() {
        AiStreamingProperties properties = new AiStreamingProperties();

        AiStreamingProperties.StreamingTimeouts timeouts = properties.resolve(null);

        assertEquals(Duration.ofSeconds(10), timeouts.connectTimeout());
        assertEquals(Duration.ofSeconds(45), timeouts.firstContentTimeout());
        assertEquals(Duration.ofSeconds(30), timeouts.idleTimeout());
        assertEquals(Duration.ofSeconds(150), timeouts.totalTimeout());
    }

    @Test
    public void should_merge_partial_model_override() {
        AiStreamingProperties properties = new AiStreamingProperties();
        StreamingTimeoutConfig override = StreamingTimeoutConfig.builder()
                .firstContentTimeoutMs(60_000L)
                .build();

        AiStreamingProperties.StreamingTimeouts timeouts = properties.resolve(override);

        assertEquals(Duration.ofSeconds(60), timeouts.firstContentTimeout());
        assertEquals(Duration.ofSeconds(30), timeouts.idleTimeout());
        assertEquals(Duration.ofSeconds(150), timeouts.totalTimeout());
    }

    @Test
    public void should_reject_non_positive_global_value() {
        AiStreamingProperties properties = new AiStreamingProperties();
        properties.setIdleTimeout(Duration.ZERO);

        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    public void should_reject_non_positive_override_value() {
        AiStreamingProperties properties = new AiStreamingProperties();
        StreamingTimeoutConfig override = StreamingTimeoutConfig.builder()
                .totalTimeoutMs(-1L)
                .build();

        assertThrows(IllegalArgumentException.class, () -> properties.resolve(override));
    }
}
