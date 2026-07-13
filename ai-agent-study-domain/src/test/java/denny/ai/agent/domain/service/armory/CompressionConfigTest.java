package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.CompressionConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CompressionConfigTest {

    @Test
    public void should_use_mandatory_defaults() {
        CompressionConfig config = CompressionConfig.builder().build();

        assertEquals(160000, config.getProactiveThresholdTokens());
        assertEquals(3, config.getMaxCompressionAttempts());
        assertEquals(2000, config.getMaxSummaryTokens());
    }

    @Test
    public void should_retain_explicit_parameters() {
        CompressionConfig config = CompressionConfig.builder()
                .proactiveThresholdTokens(100000)
                .maxCompressionAttempts(2)
                .maxSummaryTokens(1000)
                .build();

        assertEquals(100000, config.getProactiveThresholdTokens());
        assertEquals(2, config.getMaxCompressionAttempts());
        assertEquals(1000, config.getMaxSummaryTokens());
    }
}
