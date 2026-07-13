package denny.ai.agent.domain.service.armory.factory;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Armory 初始化阶段对象注册表，替代动态 BeanDefinition 注册。
 */
@Component
public class ArmoryObjectRegistry {

    public static final String COMPRESSION_CHAT_CLIENT = "compressionChatClient";
    public static final String GLOBAL_COMPRESSION_CLIENT_ID = "globalCompressionClientId";

    private final Map<String, Object> registry = new ConcurrentHashMap<>();

    public void put(String key, Object value) {
        registry.put(key, value);
    }

    public synchronized void registerGlobalCompressionClient(String clientId, Object chatClient) {
        Object existingClientId = registry.get(GLOBAL_COMPRESSION_CLIENT_ID);
        if (existingClientId != null && !existingClientId.equals(clientId)) {
            throw new IllegalStateException("Global compression clientId conflict: existing="
                    + existingClientId + ", requested=" + clientId);
        }
        registry.put(GLOBAL_COMPRESSION_CLIENT_ID, clientId);
        registry.put(COMPRESSION_CHAT_CLIENT, chatClient);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) registry.get(key);
    }

    public boolean contains(String key) {
        return registry.containsKey(key);
    }

    public void remove(String key) {
        registry.remove(key);
    }

    public void clear() {
        registry.clear();
    }
}
