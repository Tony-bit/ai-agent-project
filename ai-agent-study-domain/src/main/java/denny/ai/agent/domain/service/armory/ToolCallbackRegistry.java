package denny.ai.agent.domain.service.armory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具回调注册中心
 * <p>
 * 统一管理所有 ToolCallback 的注册，提供去重能力。
 * 解决多次调用 .defaultToolCallbacks() 导致同名工具重复注册的问题。
 * </p>
 *
 * @author denny
 */
@Slf4j
@Component
public class ToolCallbackRegistry {

    private final Map<String, ToolCallbackHolder> registry = new ConcurrentHashMap<>();

    public void registerMcpTools(SyncMcpToolCallbackProvider mcpProvider) {
        if (mcpProvider == null) {
            return;
        }
        ToolCallback[] callbacks = mcpProvider.getToolCallbacks();
        for (ToolCallback callback : callbacks) {
            register(callback, "mcp");
        }
    }

    public void registerFromSpringBeans(Map<String, ToolCallback> beans, Set<String> excludedNames) {
        if (beans == null || beans.isEmpty()) {
            return;
        }
        for (ToolCallback callback : beans.values()) {
            String name = callback.getToolDefinition().name();
            if (excludedNames != null && excludedNames.contains(name)) {
                log.debug("跳过 MCP 已有的工具: {}", name);
                continue;
            }
            register(callback, "spring");
        }
    }

    public void register(ToolCallback callback, String source) {
        if (callback == null) {
            return;
        }
        String name = callback.getToolDefinition().name();
        registry.compute(name, (k, existing) -> {
            if (existing == null) {
                return new ToolCallbackHolder(callback, source);
            }
            log.info("工具 [{}] 已存在 (来源: {}), 跳过注册 (来源: {})", name, existing.source, source);
            return existing;
        });
    }

    public ToolCallback[] getAllToolCallbacks() {
        return registry.values().stream()
                .map(holder -> holder.callback)
                .toArray(ToolCallback[]::new);
    }

    public Set<String> getAllToolNames() {
        return Set.copyOf(registry.keySet());
    }

    public int size() {
        return registry.size();
    }

    public void clear() {
        registry.clear();
    }

    private record ToolCallbackHolder(ToolCallback callback, String source) {}
}
