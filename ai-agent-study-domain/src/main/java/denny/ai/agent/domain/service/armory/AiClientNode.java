package denny.ai.agent.domain.service.armory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.entity.ArmoryCommandEntity;
import denny.ai.agent.domain.model.valobj.AiClientSystemPromptVO;
import denny.ai.agent.domain.model.valobj.AiClientVO;
import denny.ai.agent.domain.model.valobj.enums.AiAgentEnumVO;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
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
    private static final String TRADING_SKILL_READ_TOOL_BEAN = "readTradingSkillToolCallback";

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private Environment environment;

    @Resource
    private ToolCallbackRegistry toolCallbackRegistry;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，客户端{}", JSON.toJSONString(requestParameter));

        List<AiClientVO> aiClientList = dynamicContext.getValue(dataName());
        if (null == aiClientList || aiClientList.isEmpty()) {
            return router(requestParameter, dynamicContext);
        }

        Map<String, AiClientSystemPromptVO> systemPromptMap = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName());

        for (AiClientVO aiClientVO : aiClientList) {
            // 每个客户端构建前清空 Registry
            toolCallbackRegistry.clear();

            StringBuilder defaultSystem = new StringBuilder("Ai 智能体 \r\n");
            for (String promptId : aiClientVO.getPromptIdList()) {
                AiClientSystemPromptVO aiClientSystemPromptVO = systemPromptMap.get(promptId);
                defaultSystem.append(aiClientSystemPromptVO.getPromptContent());
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

            // 注册 MCP 工具
            if (mcpToolProvider != null) {
                toolCallbackRegistry.registerMcpTools(mcpToolProvider);
            }

            // 注册 Spring Beans 中的工具
            registerSpringBeansToolCallbacks(mcpToolNames);

            // 注册 Trading Skills 工具
            appendTradingSkillToolCallbacks(aiClientVO.getClientId());

            log.info("ChatClient [{}] 工具注册完成，共 {} 个工具",
                    aiClientVO.getClientId(), toolCallbackRegistry.size());

            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultSystem(defaultSystem.toString())
                    .defaultToolCallbacks(toolCallbackRegistry.getAllToolCallbacks())
                    .defaultAdvisors(advisors.toArray(new Advisor[]{}))
                    .build();

            registerBean(beanName(aiClientVO.getClientId(), aiClientVO.getTaskType()), ChatClient.class, chatClient);
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
        if (environment == null) {
            return List.of();
        }
        return Binder.get(environment)
                .bind(TRADING_SKILLS_ENABLED_CLIENT_IDS, Bindable.listOf(String.class))
                .orElse(List.of())
                .stream()
                .filter(clientId -> clientId != null && !clientId.isBlank())
                .toList();
    }

    private void registerSpringBeansToolCallbacks(Set<String> mcpToolNames) {
        try {
            Map<String, ToolCallback> allBeans = applicationContext.getBeansOfType(ToolCallback.class);
            allBeans.remove(TRADING_SKILL_READ_TOOL_BEAN);

            if (allBeans.isEmpty()) {
                return;
            }

            final Set<String> excludedNames = mcpToolNames != null ? mcpToolNames : Set.of();
            List<ToolCallback> filtered = allBeans.values().stream()
                    .filter(cb -> !excludedNames.contains(cb.getToolDefinition().name()))
                    .toList();

            log.info("从 Spring Beans 加载 {} 个 ToolCallbacks（已排除 {} 个 MCP 工具）",
                    filtered.size(), allBeans.size() - filtered.size());

            for (ToolCallback callback : filtered) {
                toolCallbackRegistry.register(callback, "spring");
            }
        } catch (Exception e) {
            log.warn("加载 ToolCallbacks 失败: {}", e.getMessage());
        }
    }

    private void appendTradingSkillToolCallbacks(String clientId) {
        if (!getTradingSkillsEnabledClientIds().contains(clientId)) {
            return;
        }
        if (!applicationContext.containsBean(TRADING_SKILL_READ_TOOL_BEAN)) {
            return;
        }

        // 将现有工具转换为 lightweight 包装
        List<ToolCallback> wrappedCallbacks = toolCallbackRegistry.getAllToolCallbacks().length > 0
                ? java.util.Arrays.stream(toolCallbackRegistry.getAllToolCallbacks())
                        .map(this::toLightweightTradingToolCallback)
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
        };
    }
}
