package denny.ai.agent.domain.service.runtime;

import denny.ai.agent.domain.adapter.repository.IAgentRepository;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AgentRuntimeConfigCacheTest {

    @Mock
    private IAgentRepository repository;

    private AgentRuntimeConfigCache cache;

    @Before
    public void setUp() throws Exception {
        cache = new AgentRuntimeConfigCache();
        set(cache, "repository", repository);
        set(cache, "ttlMs", 300_000L);
    }

    @Test
    public void cachesIntentRoutingConfigWithinTtl() {
        Map<String, AiAgentClientFlowConfigVO> configs = Map.of("intent",
                AiAgentClientFlowConfigVO.builder().clientId("routing").build());
        when(repository.queryAllFlowConfigForIntentRouting()).thenReturn(configs);

        assertEquals(configs, cache.getIntentRoutingConfig());
        assertEquals(configs, cache.getIntentRoutingConfig());

        verify(repository, times(1)).queryAllFlowConfigForIntentRouting();
    }

    @Test
    public void clearAgentForcesAgentConfigReload() {
        Map<String, AiAgentClientFlowConfigVO> configs = Map.of("step",
                AiAgentClientFlowConfigVO.builder().clientId("step").build());
        when(repository.queryAiAgentClientFlowConfig("123")).thenReturn(configs);

        cache.getAgentFlowConfig("123");
        cache.clearAgent("123");
        cache.getAgentFlowConfig("123");

        verify(repository, times(2)).queryAiAgentClientFlowConfig("123");
    }

    @Test
    public void reloadsIntentRoutingConfigAfterTtlExpires() throws Exception {
        set(cache, "ttlMs", 1L);
        when(repository.queryAllFlowConfigForIntentRouting())
                .thenReturn(Map.of("intent", AiAgentClientFlowConfigVO.builder().clientId("v1").build()))
                .thenReturn(Map.of("intent", AiAgentClientFlowConfigVO.builder().clientId("v2").build()));

        assertEquals("v1", cache.getIntentRoutingConfig().get("intent").getClientId());
        Thread.sleep(5L);
        assertEquals("v2", cache.getIntentRoutingConfig().get("intent").getClientId());

        verify(repository, times(2)).queryAllFlowConfigForIntentRouting();
    }

    @Test
    public void doesNotCacheFailedFlowConfigLoad() {
        when(repository.queryAiAgentClientFlowConfig("123"))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(Map.of("step", AiAgentClientFlowConfigVO.builder().clientId("step").build()));

        assertThrows(RuntimeException.class, () -> cache.getAgentFlowConfig("123"));
        assertEquals("step", cache.getAgentFlowConfig("123").get("step").getClientId());

        verify(repository, times(2)).queryAiAgentClientFlowConfig("123");
    }

    @Test
    public void clearMissingAgentKeyIsIdempotent() {
        cache.clearAgent("missing");
        cache.clear();

        assertEquals(Map.of(), cache.getIntentRoutingConfig());
        verify(repository, times(1)).queryAllFlowConfigForIntentRouting();
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
