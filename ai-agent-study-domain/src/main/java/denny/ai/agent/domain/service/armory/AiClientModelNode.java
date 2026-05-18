package denny.ai.agent.domain.service.armory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.CompressionConfig;
import denny.ai.agent.domain.model.valobj.enums.AiAgentEnumVO;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import denny.ai.agent.domain.model.entity.ArmoryCommandEntity;
import denny.ai.agent.domain.model.valobj.AiClientModelVO;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.service.armory.factory.element.RetryChatModel;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class AiClientModelNode extends AbstractArmorySupport{

    @Resource
    private AiClientAdvisorNode aiClientAdvisorNode;

    @Resource
    private CompressionContextNode compressionContextNode;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，Mode 对话模型{}", JSON.toJSONString(requestParameter));

        List<AiClientModelVO> aiClientModelList = dynamicContext.getValue(getDataName());

        if (aiClientModelList == null || aiClientModelList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client model");
            return router(requestParameter, dynamicContext);
        }

        for (AiClientModelVO modelVO : aiClientModelList) {
            // 获取当前模型关联的 API Bean 独享
            OpenAiApi openAiApi = getBean(AiAgentEnumVO.AI_CLIENT_API.getBeanName(modelVO.getApiId()));
            if (null == openAiApi) {
                throw new RuntimeException("model's api is null");
            }

            // 获取当前模型关联的Tool MCP Bean对象
            List<McpSyncClient> mcpSyncClientList = new ArrayList<>();

            for (String toolMcpId : modelVO.getToolMcpIds()) {
                McpSyncClient mcpSyncClient = getBean(AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(toolMcpId));
                mcpSyncClientList.add(mcpSyncClient);
            }

            // 实例化对话模型
            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(
                            OpenAiChatOptions.builder()
                                    .model(modelVO.getModelName())
                                    .toolCallbacks(new SyncMcpToolCallbackProvider(mcpSyncClientList).getToolCallbacks())
                                    .build()
                    ).build();

            // 应用重试装饰器（含压缩配置）
            ChatModel registeredModel = applyRetryDecorator(chatModel, modelVO.getRetryConfig(),
                    modelVO.getCompressionConfig(), dynamicContext);
            registerBean(getBeanName(modelVO.getModelId()), ChatModel.class, registeredModel);
        }

        return router(requestParameter, dynamicContext);
    }

    private ChatModel applyRetryDecorator(OpenAiChatModel chatModel, RetryConfig retryConfig,
                                          CompressionConfig compressionConfig, DynamicContext dynamicContext) {
        if (retryConfig == null || !retryConfig.isEnabled()) {
            return chatModel;
        }
        log.warn("应用重试装饰器，model={}, maxAttempts={}, interval={}ms, multiplier={}",
                Optional.ofNullable(chatModel.getDefaultOptions())
                        .map(opts -> opts.getModel())
                        .orElse("unknown"),
                retryConfig.getMaxAttempts(),
                retryConfig.getInitialIntervalMs(),
                retryConfig.getMultiplier());
        RetryChatModel retryChatModel = new RetryChatModel(chatModel, retryConfig);
        // 设置压缩配置和动态上下文
        if (compressionConfig != null) {
            retryChatModel.setCompressionConfig(compressionConfig);
            retryChatModel.setDynamicContext(dynamicContext);
        }
        return retryChatModel;
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DynamicContext, String> get(ArmoryCommandEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        // 检查是否需要压缩
        if (dynamicContext.isCompressionRequired()) {
            log.info("检测到压缩需求，路由到 CompressionContextNode");
            return compressionContextNode;
        }
        return aiClientAdvisorNode;
    }

    @Override
    protected String getBeanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(beanId);
    }

    @Override
    protected String getDataName() {
        return AiAgentEnumVO.AI_CLIENT_MODEL.getDataName();
    }

}
