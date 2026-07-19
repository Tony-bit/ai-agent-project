package denny.ai.agent.infrastructure.adapter.repository;

import denny.ai.agent.domain.model.valobj.AiClientModelVO;
import denny.ai.agent.infrastructure.dao.IAiClientConfigDao;
import denny.ai.agent.infrastructure.dao.IAiClientModelDao;
import denny.ai.agent.infrastructure.dao.po.AiClientConfigPO;
import denny.ai.agent.infrastructure.dao.po.AiClientModelPO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AgentRepositoryCompressionConfigTest {

    @InjectMocks
    private AgentRepository repository;

    @Mock
    private IAiClientConfigDao clientConfigDao;

    @Mock
    private IAiClientModelDao clientModelDao;

    @Test
    public void should_apply_defaults_when_ext_param_is_empty() {
        AiClientModelVO model = load(null);

        assertNull(model.getRetryConfig());
        assertDefaults(model.getCompressionConfig());
    }

    @Test
    public void should_parse_composite_runtime_config() {
        AiClientModelVO model = load("""
                {"retryConfig":{"enabled":true,"maxAttempts":2},
                 "compressionConfig":{"proactiveThresholdTokens":4096,
                 "maxCompressionAttempts":2,"maxSummaryTokens":1000},
                 "streamingTimeout":{"firstContentTimeoutMs":60000}}
                """);

        assertTrue(model.getRetryConfig().isEnabled());
        assertEquals(2, model.getRetryConfig().getMaxAttempts());
        assertEquals(4096, model.getCompressionConfig().getProactiveThresholdTokens());
        assertEquals(2, model.getCompressionConfig().getMaxCompressionAttempts());
        assertEquals(1000, model.getCompressionConfig().getMaxSummaryTokens());
        assertEquals(Long.valueOf(60000), model.getStreamingTimeoutConfig().getFirstContentTimeoutMs());
    }

    @Test
    public void should_parse_legacy_retry_and_apply_compression_defaults() {
        AiClientModelVO model = load("{\"enabled\":true,\"maxAttempts\":2}");

        assertTrue(model.getRetryConfig().isEnabled());
        assertEquals(2, model.getRetryConfig().getMaxAttempts());
        assertDefaults(model.getCompressionConfig());
    }

    @Test
    public void should_fallback_to_defaults_when_json_is_malformed() {
        AiClientModelVO model = load("{not-json");

        assertNull(model.getRetryConfig());
        assertDefaults(model.getCompressionConfig());
    }

    @Test(expected = IllegalArgumentException.class)
    public void should_reject_explicit_invalid_compression_values() {
        load("{\"compressionConfig\":{\"proactiveThresholdTokens\":1024,"
                + "\"maxCompressionAttempts\":3,\"maxSummaryTokens\":1}}");
    }

    @Test(expected = IllegalArgumentException.class)
    public void should_reject_explicit_invalid_streaming_timeout() {
        load("{\"streamingTimeout\":{\"idleTimeoutMs\":0}}");
    }

    private AiClientModelVO load(String extParam) {
        AiClientConfigPO relation = new AiClientConfigPO();
        relation.setTargetType("model");
        relation.setTargetId("model-1");
        relation.setStatus(1);
        when(clientConfigDao.queryBySourceTypeAndId("client", "client-1"))
                .thenReturn(List.of(relation));
        when(clientConfigDao.queryBySourceTypeAndId("model", "model-1"))
                .thenReturn(List.of());

        AiClientModelPO model = new AiClientModelPO();
        model.setModelId("model-1");
        model.setApiId("api-1");
        model.setModelName("test-model");
        model.setModelType("openai");
        model.setStatus(1);
        model.setExtParam(extParam);
        when(clientModelDao.queryByModelId("model-1")).thenReturn(model);

        return repository.AiClientModelVOByClientIds(List.of("client-1")).get(0);
    }

    private void assertDefaults(AiClientModelVO.CompressionConfig config) {
        assertNotNull(config);
        assertEquals(160000, config.getProactiveThresholdTokens());
        assertEquals(3, config.getMaxCompressionAttempts());
        assertEquals(2000, config.getMaxSummaryTokens());
    }
}
