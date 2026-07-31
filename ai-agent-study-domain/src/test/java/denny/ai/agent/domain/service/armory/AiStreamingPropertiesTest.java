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

        assertEquals(AiStreamingProperties.TimeoutMode.LAYERED, timeouts.timeoutMode());
        assertEquals(Duration.ofSeconds(10), timeouts.connectTimeout());
        assertEquals(Duration.ofSeconds(45), timeouts.firstChunkTimeout());
        assertEquals(Duration.ofSeconds(30), timeouts.stallThreshold());
        assertEquals(Duration.ofSeconds(90), timeouts.chunkIdleTimeout());
        assertEquals(Duration.ofSeconds(150), timeouts.queryAttemptTimeout());
    }

    @Test
    public void should_merge_partial_model_override() {
        AiStreamingProperties properties = new AiStreamingProperties();
        StreamingTimeoutConfig override = StreamingTimeoutConfig.builder()
                .firstChunkTimeoutMs(60_000L)
                .build();

        AiStreamingProperties.StreamingTimeouts timeouts = properties.resolve(override);

        assertEquals(Duration.ofSeconds(60), timeouts.firstChunkTimeout());
        assertEquals(Duration.ofSeconds(30), timeouts.stallThreshold());
        assertEquals(Duration.ofSeconds(90), timeouts.chunkIdleTimeout());
        assertEquals(Duration.ofSeconds(150), timeouts.queryAttemptTimeout());
    }

    @Test
    public void should_allow_explicit_legacy_mode() {
        AiStreamingProperties properties = new AiStreamingProperties();
        properties.setTimeoutMode(AiStreamingProperties.TimeoutMode.LEGACY);

        assertEquals(AiStreamingProperties.TimeoutMode.LEGACY,
                properties.resolve(null).timeoutMode());
    }

    @Test
    public void should_prefer_new_model_fields_over_legacy_fields() {
        AiStreamingProperties properties = new AiStreamingProperties();
        properties.setFirstChunkTimeout(Duration.ofSeconds(50));
        properties.setFirstContentTimeout(Duration.ofSeconds(55));
        StreamingTimeoutConfig override = StreamingTimeoutConfig.builder()
                .firstChunkTimeoutMs(60_000L)
                .firstContentTimeoutMs(70_000L)
                .build();

        assertEquals(Duration.ofSeconds(60),
                properties.resolve(override).firstChunkTimeout());
    }

    @Test
    public void should_use_legacy_fields_as_fallback() {
        AiStreamingProperties properties = new AiStreamingProperties();
        properties.setFirstContentTimeout(Duration.ofSeconds(50));
        properties.setIdleTimeout(Duration.ofSeconds(35));
        properties.setTotalTimeout(Duration.ofSeconds(160));

        AiStreamingProperties.StreamingTimeouts timeouts = properties.resolve(null);

        assertEquals(Duration.ofSeconds(50), timeouts.firstChunkTimeout());
        assertEquals(Duration.ofSeconds(35), timeouts.stallThreshold());
        assertEquals(Duration.ofSeconds(160), timeouts.queryAttemptTimeout());
    }

    @Test
    public void should_reject_non_positive_global_value() {
        AiStreamingProperties properties = new AiStreamingProperties();
        properties.setStallThreshold(Duration.ZERO);

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

    @Test
    public void should_reject_invalid_layered_timeout_relationships() {
        AiStreamingProperties properties = new AiStreamingProperties();
        properties.setFirstChunkTimeout(Duration.ofSeconds(10));

        assertThrows(IllegalArgumentException.class, properties::validate);

        properties = new AiStreamingProperties();
        properties.setChunkIdleTimeout(Duration.ofSeconds(30));

        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    public void should_not_apply_layered_relationship_validation_in_legacy_mode() {
        AiStreamingProperties properties = new AiStreamingProperties();
        properties.setTimeoutMode(AiStreamingProperties.TimeoutMode.LEGACY);
        properties.setFirstChunkTimeout(Duration.ofSeconds(5));

        properties.validate();
    }
}
