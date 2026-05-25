package denny.ai.agent.infrastructure.tools;

import denny.ai.agent.domain.model.valobj.MemoryProperties;
import denny.ai.agent.domain.service.episodicmemory.IEpisodicMemoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 情景记忆搜索 Tool 回调实现
 * <p>
 * 将 IEpisodicMemoryService 包装为 ToolCallback，供 Agent 通过 Function Calling 调用。
 * </p>
 *
 * @author denny
 */
@Slf4j
@Component
public class EpisodicMemoryToolCallbacks {

    @Resource
    private IEpisodicMemoryService episodicMemoryService;

    @Resource
    private MemoryProperties memoryProperties;

    /**
     * 搜索情景记忆 Tool
     */
    public ToolCallback searchEpisodicMemoryCallback() {
        return new ToolCallback() {
            private static final String TOOL_NAME = "search_episodic_memory";
            private static final String TOOL_DESCRIPTION = "搜索用户的跨会话情景记忆。当用户询问之前讨论过的话题、历史事件、之前说过的话时，必须调用此工具获取相关记忆。\n注意：只有用户明确在询问历史内容时才调用。";
            
            private final String inputSchema = buildInputSchema();

            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(TOOL_NAME)
                        .description(TOOL_DESCRIPTION)
                        .inputSchema(inputSchema)
                        .build();
            }

            @Override
            public String call(String functionInput) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    Map<String, Object> input = mapper.readValue(functionInput, 
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    
                    String query = (String) input.get("query");
                    String userId = (String) input.get("userId");
                    int limit = memoryProperties.getEpisodicMemoryLimit();

                    if (query == null || query.isBlank()) {
                        return "搜索关键词为空，无法进行情景记忆搜索";
                    }

                    log.info("搜索情景记忆: userId={}, query={}, limit={}", userId, query, limit);

                    String result = episodicMemoryService.searchEpisodicMemories(userId, query, limit);

                    if (result.isEmpty()) {
                        return "未找到相关情景记忆，建议基于当前对话内容回答用户问题";
                    }
                    return result;
                } catch (Exception e) {
                    log.error("Tool[{}] 执行失败: input={}, error={}", TOOL_NAME, functionInput, e.getMessage(), e);
                    return "工具执行失败: " + e.getMessage();
                }
            }

            private String buildInputSchema() {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    java.util.Map<String, java.util.Map<String, String>> properties = new java.util.LinkedHashMap<>();
                    properties.put("query", java.util.Map.of("type", "string", "description", 
                            "搜索关键词，使用用户的原始问题或关键词"));
                    properties.put("userId", java.util.Map.of("type", "string", "description", 
                            "用户ID，从对话上下文中获取"));
                    
                    java.util.Map<String, Object> schema = java.util.Map.of(
                            "type", "object",
                            "properties", properties,
                            "required", java.util.List.of("query", "userId")
                    );
                    return om.writeValueAsString(schema);
                } catch (Exception e) {
                    log.error("构建 inputSchema 失败: {}", e.getMessage());
                    return "{}";
                }
            }
        };
    }
}
