package denny.ai.agent.domain.service.runtime;

import denny.ai.agent.domain.adapter.repository.IAgentRepository;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentRuntimeConfigCache {

    private static final String INTENT_ROUTING_ALL_KEY = "intent-routing-all";
    private static final String AGENT_FLOW_CONFIG_PREFIX = "agent-flow-config:";

    @Resource
    private IAgentRepository repository;

    @Value("${agent.runtime.flow-config-cache.ttl-ms:300000}")
    private long ttlMs = 300_000L;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public Map<String, AiAgentClientFlowConfigVO> getIntentRoutingConfig() {
        return getOrLoad(INTENT_ROUTING_ALL_KEY, repository::queryAllFlowConfigForIntentRouting);
    }

    public Map<String, AiAgentClientFlowConfigVO> getAgentFlowConfig(String aiAgentId) {
        return getOrLoad(AGENT_FLOW_CONFIG_PREFIX + aiAgentId,
                () -> repository.queryAiAgentClientFlowConfig(aiAgentId));
    }

    public void clear() {
        cache.clear();
    }

    public void clearAgent(String aiAgentId) {
        cache.remove(AGENT_FLOW_CONFIG_PREFIX + aiAgentId);
    }

    private Map<String, AiAgentClientFlowConfigVO> getOrLoad(String key, ConfigLoader loader) {
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired(now, ttlMs)) {
            return entry.value();
        }
        Map<String, AiAgentClientFlowConfigVO> value = loader.load();
        Map<String, AiAgentClientFlowConfigVO> safeValue = value == null ? Map.of() : value;
        cache.put(key, new CacheEntry(safeValue, now));
        return safeValue;
    }

    @FunctionalInterface
    private interface ConfigLoader {
        Map<String, AiAgentClientFlowConfigVO> load();
    }

    private record CacheEntry(Map<String, AiAgentClientFlowConfigVO> value, long loadedAt) {
        boolean isExpired(long now, long ttlMs) {
            return ttlMs <= 0 || now - loadedAt >= ttlMs;
        }
    }
}
