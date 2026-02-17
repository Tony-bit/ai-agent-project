package denny.ai.agent.domain.service.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class SchemaAwareMcpToolCallbackProvider {
    private final SyncMcpToolCallbackProvider delegate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SchemaAwareMcpToolCallbackProvider(List<McpSyncClient> mcpSyncClients) {
        this.delegate = new SyncMcpToolCallbackProvider(mcpSyncClients);
    }

    public ToolCallback[] getToolCallbacks() {
        ToolCallback[] callbacks = delegate.getToolCallbacks();
        List<ToolCallback> validCallbacks = new ArrayList<>();

        for (ToolCallback callback : callbacks) {
            try {
                // 检查并修复 schema
                if (isSchemaValid(callback)) {
                    validCallbacks.add(callback);
                } else {
                    log.warn("ToolCallback with invalid schema skipped: {}", callback);
                }
            } catch (Exception e) {
                log.error("Error processing ToolCallback: {}", callback, e);
            }
        }

        return validCallbacks.toArray(new ToolCallback[0]);
    }

    private boolean isSchemaValid(ToolCallback callback) {
        try {
            // 使用反射获取 schema 信息
            java.lang.reflect.Field field = callback.getClass().getDeclaredField("toolDefinition");
            field.setAccessible(true);
            Object toolDefinition = field.get(callback);

            // 获取 schema
            java.lang.reflect.Method getInputSchema = toolDefinition.getClass().getMethod("getInputSchema");
            Object schema = getInputSchema.invoke(toolDefinition);

            if (schema == null) {
                log.warn("Tool has null schema: {}", toolDefinition);
                return false;
            }

            // 检查 type 是否为 null
            java.lang.reflect.Method getType = schema.getClass().getMethod("getType");
            Object type = getType.invoke(schema);

            if (type == null) {
                // 修复 schema
                java.lang.reflect.Method setType = schema.getClass().getMethod("setType", String.class);
                setType.invoke(schema, "object");
                log.info("Fixed null type in schema for tool: {}", toolDefinition);
            }

            return true;
        } catch (Exception e) {
            log.error("Error validating schema for ToolCallback: {}", callback, e);
            return false;
        }
    }
}
