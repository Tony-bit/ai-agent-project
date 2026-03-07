package denny.ai.agent.domain.service.armory.factory;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Armory 初始化阶段对象注册表，替代动态 BeanDefinition 注册。
 */
@Component
public class ArmoryObjectRegistry {

    private final Map<String, Object> registry = new ConcurrentHashMap<>();

    public void put(String key, Object value) {
        registry.put(key, value);
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
