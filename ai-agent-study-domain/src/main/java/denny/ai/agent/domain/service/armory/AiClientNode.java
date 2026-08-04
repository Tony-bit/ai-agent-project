package denny.ai.agent.domain.service.armory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.cloud.ai.graph.skills.SpringAiSkillAdvisor;
import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.entity.ArmoryCommandEntity;
import denny.ai.agent.domain.model.valobj.AiClientSystemPromptVO;
import denny.ai.agent.domain.model.valobj.AiClientVO;
import denny.ai.agent.domain.model.valobj.enums.AiAgentEnumVO;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import denny.ai.agent.domain.service.armory.business.data.impl.AiClientLoadDataStrategy;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ai agent 客户端对话对象节点
 */
@Slf4j
@Service
public class AiClientNode extends AbstractArmorySupport {

    private static final String TRADING_SKILLS_ENABLED_CLIENT_IDS =
            "spring.ai.trading.skills.enabled-client-ids";
    private static final String TRADING_TOOLS_ALLOWED_BY_CLIENT =
            "spring.ai.trading.tools.allowed-by-client";
    private static final String TRADING_SKILL_READ_TOOL_BEAN = "readTradingSkillToolCallback";
    private static final Set<String> TRADING_TOOL_NAMES = Set.of(
            "get_stock_info",
            "get_historical_bars",
            "get_technical_indicators",
            "get_fundamental_data",
            "get_sentiment",
            "get_stock_news",
            "search_stock_by_name");
    private static final Set<String> MANAGED_TRADING_TOOL_NAMES;

    static {
        Set<String> names = new LinkedHashSet<>(TRADING_TOOL_NAMES);
        names.add("read_skill");
        MANAGED_TRADING_TOOL_NAMES = Collections.unmodifiableSet(names);
    }

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private Environment environment;

    @Resource
    private ToolCallbackRegistry toolCallbackRegistry;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，客户端{}", JSON.toJSONString(requestParameter));
        validateTradingToolAllowlist();

        List<AiClientVO> aiClientList = dynamicContext.getValue(dataName());
        if (null == aiClientList || aiClientList.isEmpty()) {
            return router(requestParameter, dynamicContext);
        }

        Map<String, AiClientSystemPromptVO> systemPromptMap = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName());
        String globalCompressionClientId = dynamicContext.getValue(
                AiClientLoadDataStrategy.GLOBAL_COMPRESSION_CLIENT_ID);
        List<AiClientVO> compressionClients = aiClientList.stream()
                .filter(client -> globalCompressionClientId != null
                        && globalCompressionClientId.equals(client.getClientId())
                        && Integer.valueOf(1).equals(client.getTaskType()))
                .toList();
        if (compressionClients.size() != 1) {
            throw new IllegalStateException("Compression ChatClient match must be exactly one, clientId="
                    + globalCompressionClientId + ", taskType=1, matches=" + compressionClients.size());
        }

        for (AiClientVO aiClientVO : aiClientList) {
            // 每个客户端构建前清空 Registry
            toolCallbackRegistry.clear();

            boolean compressionClient = globalCompressionClientId.equals(aiClientVO.getClientId())
                    && Integer.valueOf(1).equals(aiClientVO.getTaskType());

            StringBuilder defaultSystem = new StringBuilder("Ai 智能体 \r\n");
            boolean hasCompressionPrompt = false;
            for (String promptId : aiClientVO.getPromptIdList()) {
                AiClientSystemPromptVO aiClientSystemPromptVO = systemPromptMap == null
                        ? null : systemPromptMap.get(promptId);
                if (aiClientSystemPromptVO != null && aiClientSystemPromptVO.getPromptContent() != null) {
                    defaultSystem.append(aiClientSystemPromptVO.getPromptContent());
                    if ("7001".equals(promptId)
                            && !aiClientSystemPromptVO.getPromptContent().isBlank()) {
                        hasCompressionPrompt = true;
                    }
                }
            }
            if (compressionClient && !hasCompressionPrompt) {
                defaultSystem.append(AiClientModelNode.DEFAULT_COMPRESSION_PROMPT_TEMPLATE);
            }

            ChatModel chatModel = getBean(aiClientVO.getModelBeanName());

            List<McpSyncClient> mcpSyncClients = new ArrayList<>();
            for (String mcpBeanName : aiClientVO.getMcpBeanNameList()) {
                mcpSyncClients.add(getBean(mcpBeanName));
            }

            SyncMcpToolCallbackProvider mcpToolProvider = null;
            Set<String> mcpToolNames = Set.of();
            if (!mcpSyncClients.isEmpty()) {
                mcpToolProvider = new SyncMcpToolCallbackProvider(
                        mcpSyncClients.toArray(new McpSyncClient[0]));
                mcpToolNames = java.util.Arrays.stream(mcpToolProvider.getToolCallbacks())
                        .map(cb -> cb.getToolDefinition().name())
                        .collect(Collectors.toCollection(java.util.HashSet::new));
            }

            List<Advisor> advisors = new ArrayList<>();
            for (String advisorBeanName : aiClientVO.getAdvisorBeanNameList()) {
                advisors.add(getBean(advisorBeanName));
            }
            advisors.sort(AnnotationAwareOrderComparator.INSTANCE);
            boolean tradingSkillsEnabled = isTradingSkillsEnabled(aiClientVO.getClientId(), advisors);

            // 注册 MCP 工具
            if (mcpToolProvider != null) {
                toolCallbackRegistry.registerMcpTools(mcpToolProvider);
            }

            // 注册 Spring Beans 中的工具
            registerSpringBeansToolCallbacks(
                    mcpToolNames, aiClientVO.getClientId(), tradingSkillsEnabled);

            // 注册 Trading Skills 工具
            appendTradingSkillToolCallbacks(aiClientVO.getClientId(), tradingSkillsEnabled);

            log.info("ChatClient [{}] 工具注册完成，共 {} 个工具: {}",
                    aiClientVO.getClientId(), toolCallbackRegistry.size(),
                    toolCallbackRegistry.getAllToolNames());

            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultSystem(defaultSystem.toString())
                    .defaultToolCallbacks(toolCallbackRegistry.getAllToolCallbacks())
                    .defaultAdvisors(advisors.toArray(new Advisor[]{}))
                    .build();

            registerBean(beanName(aiClientVO.getClientId(), aiClientVO.getTaskType()), ChatClient.class, chatClient);
            if (compressionClient) {
                armoryObjectRegistry.registerGlobalCompressionClient(globalCompressionClientId, chatClient);
            }
        }

        if (!armoryObjectRegistry.contains(ArmoryObjectRegistry.COMPRESSION_CHAT_CLIENT)) {
            throw new IllegalStateException("Compression ChatClient alias was not registered: "
                    + ArmoryObjectRegistry.COMPRESSION_CHAT_CLIENT);
        }

        return router(requestParameter, dynamicContext);
    }

    public StrategyHandler<ArmoryCommandEntity, DynamicContext, String> get(ArmoryCommandEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    protected String beanName(String id, Integer taskType) {
        return AiAgentEnumVO.AI_CLIENT.getBeanName(id) + "taskType" + taskType;
    }

    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT.getDataName();
    }

    List<String> getTradingSkillsEnabledClientIds() {
        return getConfiguredClientIds(TRADING_SKILLS_ENABLED_CLIENT_IDS);
    }

    boolean isTradingSkillsEnabled(String clientId, List<Advisor> advisors) {
        boolean advisorEnabled = advisors != null && advisors.stream()
                .anyMatch(SpringAiSkillAdvisor.class::isInstance);
        boolean configured = getTradingSkillsEnabledClientIds().contains(clientId);
        boolean enabled = advisorEnabled || configured;
        log.info("ChatClient [{}] Trading Skills capability: enabled={}, advisorEnabled={}, configured={}",
                clientId, enabled, advisorEnabled, configured);
        return enabled;
    }

    private List<String> getConfiguredClientIds(String propertyName) {
        if (environment == null) {
            return List.of();
        }
        return Binder.get(environment)
                .bind(propertyName, Bindable.listOf(String.class))
                .orElse(List.of())
                .stream()
                .filter(clientId -> clientId != null && !clientId.isBlank())
                .toList();
    }

    Map<String, Set<String>> getAllowedTradingToolsByClient() {
        if (environment == null) {
            return Map.of();
        }
        Map<String, String[]> configured = Binder.get(environment)
                .bind(TRADING_TOOLS_ALLOWED_BY_CLIENT, Bindable.mapOf(String.class, String[].class))
                .orElse(Map.of());
        Map<String, Set<String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : configured.entrySet()) {
            Set<String> names = new LinkedHashSet<>();
            if (entry.getValue() != null) {
                for (String name : entry.getValue()) {
                    if (name != null && !name.isBlank()) {
                        names.add(name.trim());
                    }
                }
            }
            normalized.put(entry.getKey(), Collections.unmodifiableSet(names));
        }
        return Collections.unmodifiableMap(normalized);
    }

    void validateTradingToolAllowlist() {
        for (Map.Entry<String, Set<String>> entry : getAllowedTradingToolsByClient().entrySet()) {
            Set<String> unknown = new LinkedHashSet<>(entry.getValue());
            unknown.removeAll(MANAGED_TRADING_TOOL_NAMES);
            if (!unknown.isEmpty()) {
                throw new IllegalStateException("Unknown Trading Tool names for clientId="
                        + entry.getKey() + ": " + unknown);
            }
        }
    }

    private void registerSpringBeansToolCallbacks(Set<String> mcpToolNames,
                                                  String clientId,
                                                  boolean tradingSkillsEnabled) {
        try {
            Map<String, ToolCallback> allBeans = applicationContext.getBeansOfType(ToolCallback.class);
            allBeans.remove(TRADING_SKILL_READ_TOOL_BEAN);

            if (allBeans.isEmpty()) {
                return;
            }

            final Set<String> excludedNames = mcpToolNames != null ? mcpToolNames : Set.of();
            List<ToolCallback> filtered = allBeans.values().stream()
                    .filter(cb -> !excludedNames.contains(cb.getToolDefinition().name()))
                    .filter(cb -> shouldRegisterSpringToolCallback(
                            clientId, cb, tradingSkillsEnabled))
                    .toList();

            log.info("从 Spring Beans 为 clientId={} 加载 {} 个 ToolCallbacks（已排除 {} 个重复或未授权工具）",
                    clientId, filtered.size(), allBeans.size() - filtered.size());

            for (ToolCallback callback : filtered) {
                toolCallbackRegistry.register(callback, "spring");
            }
        } catch (Exception e) {
            log.warn("加载 ToolCallbacks 失败: {}", e.getMessage());
        }
    }

    boolean shouldRegisterSpringToolCallback(String clientId,
                                             ToolCallback callback,
                                             boolean tradingSkillsEnabled) {
        String toolName = callback.getToolDefinition().name();
        return !TRADING_TOOL_NAMES.contains(toolName)
                || getAllowedTradingToolsByClient().getOrDefault(clientId, Set.of()).contains(toolName);
    }

    private void appendTradingSkillToolCallbacks(String clientId, boolean tradingSkillsEnabled) {
        Set<String> allowedTools = getAllowedTradingToolsByClient().getOrDefault(clientId, Set.of());
        if (!allowedTools.contains("read_skill")) {
            return;
        }
        if (!applicationContext.containsBean(TRADING_SKILL_READ_TOOL_BEAN)) {
            throw new IllegalStateException("Trading Tool read_skill is allowed but bean is unavailable");
        }

        // 将现有工具转换为 lightweight 包装
        List<ToolCallback> wrappedCallbacks = toolCallbackRegistry.getAllToolCallbacks().length > 0
                ? java.util.Arrays.stream(toolCallbackRegistry.getAllToolCallbacks())
                        .filter(callback -> !TRADING_TOOL_NAMES.contains(callback.getToolDefinition().name())
                                || allowedTools.contains(callback.getToolDefinition().name()))
                        .map(callback -> TRADING_TOOL_NAMES.contains(
                                callback.getToolDefinition().name())
                                ? toLightweightTradingToolCallback(callback)
                                : callback)
                        .collect(Collectors.toList())
                : new ArrayList<>();

        // 清除并重新注册
        toolCallbackRegistry.clear();
        for (ToolCallback callback : wrappedCallbacks) {
            toolCallbackRegistry.register(callback, "trading-skill-wrapper");
        }

        // 添加 read_skill 工具
        ToolCallback readSkillCallback = applicationContext.getBean(TRADING_SKILL_READ_TOOL_BEAN, ToolCallback.class);
        toolCallbackRegistry.register(readSkillCallback, "trading-skill");
        log.info("Trading Skills 工具已注册到 clientId: {}", clientId);
    }

    private ToolCallback toLightweightTradingToolCallback(ToolCallback delegate) {
        ToolDefinition original = delegate.getToolDefinition();
        String skillName = original.name().replace('_', '-');
        ToolDefinition lightweightDefinition = ToolDefinition.builder()
                .name(original.name())
                .description("Execute trading skill `" + skillName
                        + "`. Call `read_skill` with `skill_name` `" + skillName
                        + "` for full usage guidance before calling this tool.")
                .inputSchema(original.inputSchema())
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return lightweightDefinition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String functionInput) {
                return delegate.call(functionInput);
            }

            @Override
            public String call(String functionInput, ToolContext toolContext) {
                return delegate.call(functionInput, toolContext);
            }
        };
    }
}
