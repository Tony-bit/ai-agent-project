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
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ai agent 客户端对话对象节点
 */
@Slf4j
@Service
public class AiClientNode extends AbstractArmorySupport {

    @Resource
    private ApplicationContext applicationContext;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，客户端{}", JSON.toJSONString(requestParameter));

        List<AiClientVO> aiClientList = dynamicContext.getValue(dataName());
        if (null == aiClientList || aiClientList.isEmpty()) {
            return router(requestParameter, dynamicContext);
        }

        Map<String, AiClientSystemPromptVO> systemPromptMap = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName());

        for (AiClientVO aiClientVO : aiClientList) {
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

            List<Advisor> advisors = new ArrayList<>();
            for (String advisorBeanName : aiClientVO.getAdvisorBeanNameList()) {
                advisors.add(getBean(advisorBeanName));
            }
            advisors.sort(AnnotationAwareOrderComparator.INSTANCE);

            // 从 Spring 容器加载所有 Trading ToolCallbacks
            ToolCallback[] tradingToolCallbacks = loadTradingToolCallbacks();

            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultSystem(defaultSystem.toString())
                    .defaultToolCallbacks(tradingToolCallbacks)
                    .defaultToolCallbacks(new SyncMcpToolCallbackProvider(mcpSyncClients.toArray(new McpSyncClient[]{})))
                    .defaultAdvisors(advisors.toArray(new Advisor[]{}))
                    .build();

            // 统一走 ArmoryObjectRegistry
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

    /**
     * 从 Spring 容器加载所有 Trading 相关的 ToolCallback Bean。
     * 容器中所有 ToolCallback 类型的 Bean（由 TradingToolCallbackProvider 注册）都会被自动加载。
     */
    private ToolCallback[] loadTradingToolCallbacks() {
        try {
            Map<String, ToolCallback> toolCallbackBeans = applicationContext.getBeansOfType(ToolCallback.class);
            if (toolCallbackBeans.isEmpty()) {
                log.info("未找到 Trading ToolCallback Bean，跳过注册");
                return new ToolCallback[0];
            }
            ToolCallback[] callbacks = toolCallbackBeans.values().toArray(new ToolCallback[0]);
            log.info("已加载 {} 个 Trading ToolCallbacks: {}",
                    callbacks.length,
                    java.util.Arrays.stream(callbacks)
                            .map(cb -> cb.getToolDefinition().name())
                            .toList());
            return callbacks;
        } catch (Exception e) {
            log.warn("加载 Trading ToolCallbacks 失败，跳过: {}", e.getMessage());
            return new ToolCallback[0];
        }
    }
}
