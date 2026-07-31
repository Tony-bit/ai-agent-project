package denny.ai.agent.domain.model.valobj;

import org.junit.Test;

import static org.junit.Assert.assertFalse;

public class AiClientModelVORetryConfigTest {

    @Test
    public void should_default_stream_timeout_retry_to_false() {
        assertFalse(AiClientModelVO.RetryConfig.builder().build().isRetryOnStreamTimeout());
        assertFalse(new AiClientModelVO.RetryConfig().isRetryOnStreamTimeout());
    }
}
