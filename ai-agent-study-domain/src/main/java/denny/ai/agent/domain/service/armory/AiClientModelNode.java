package denny.ai.agent.domain.service.armory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.CompressionConfig;
import denny.ai.agent.domain.model.valobj.AiClientSystemPromptVO;
import denny.ai.agent.domain.model.valobj.enums.AiAgentEnumVO;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import denny.ai.agent.domain.model.entity.ArmoryCommandEntity;
import denny.ai.agent.domain.model.valobj.AiClientModelVO;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.model.valobj.AiClientModelVO.StreamingTimeoutConfig;
import denny.ai.agent.domain.service.armory.factory.element.RetryChatModel;
import denny.ai.agent.domain.service.armory.factory.element.CompressionPolicy;
import denny.ai.agent.domain.service.armory.factory.element.AiErrorCodeExtractor;
import denny.ai.agent.domain.service.compression.PromptCompressionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Optional;
import java.util.Map;

@Service
@Slf4j
public class AiClientModelNode extends AbstractArmorySupport{

    static final String DEFAULT_COMPRESSION_PROMPT_TEMPLATE = """
            你是上下文压缩助手。请保留任务目标、关键事实、约束、已完成工作、未决事项和必要标识符。
            输出必须使用以下协议：
            <分析>仅用于内部整理，不应进入最终摘要</分析>
            <摘要>可供后续模型继续执行任务的精炼上下文</摘要>
            """;

    @Resource
    private AiClientAdvisorNode aiClientAdvisorNode;

    @Resource
    private PromptCompressionService promptCompressionService;

    @Resource
    private AiStreamingProperties aiStreamingProperties;

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

            // 实例化对话模型
            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(
                            OpenAiChatOptions.builder()
                                    .model(modelVO.getModelName())
                                    .build()
                    ).build();

            // 应用重试装饰器（含压缩配置）
            Map<String, AiClientSystemPromptVO> systemPromptMap = dynamicContext.getValue(
                    AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName());
            CompressionPolicy compressionPolicy = toCompressionPolicy(
                    modelVO.getCompressionConfig(), systemPromptMap);
            ChatModel registeredModel = applyRetryDecorator(chatModel, modelVO.getRetryConfig(),
                    compressionPolicy, modelVO.getStreamingTimeoutConfig());
            registerBean(getBeanName(modelVO.getModelId()), ChatModel.class, registeredModel);
        }

        return router(requestParameter, dynamicContext);
    }

    public ChatModel applyRetryDecorator(ChatModel chatModel, RetryConfig retryConfig,
                                  CompressionPolicy compressionPolicy) {
        return applyRetryDecorator(chatModel, retryConfig, compressionPolicy, null);
    }

    public ChatModel applyRetryDecorator(ChatModel chatModel, RetryConfig retryConfig,
                                  CompressionPolicy compressionPolicy,
                                  StreamingTimeoutConfig streamingTimeoutConfig) {
        boolean retryEnabled = retryConfig != null && retryConfig.isEnabled();
        RetryConfig effectiveRetryConfig = retryEnabled
                ? retryConfig
                : RetryConfig.builder().enabled(false).maxAttempts(1).build();
        log.warn("应用重试装饰器，model={}, maxAttempts={}, interval={}ms, multiplier={}",
                chatModel instanceof OpenAiChatModel openAi
                        ? Optional.ofNullable(openAi.getDefaultOptions()).map(opts -> opts.getModel()).orElse("unknown")
                        : "unknown",
                effectiveRetryConfig.getMaxAttempts(),
                effectiveRetryConfig.getInitialIntervalMs(),
                effectiveRetryConfig.getMultiplier());
        AiStreamingProperties properties = aiStreamingProperties == null
                ? new AiStreamingProperties() : aiStreamingProperties;
        return new RetryChatModel(chatModel, effectiveRetryConfig, compressionPolicy,
                promptCompressionService, new AiErrorCodeExtractor(),
                properties.resolve(streamingTimeoutConfig));
    }

    private CompressionPolicy toCompressionPolicy(CompressionConfig config,
                                                   Map<String, AiClientSystemPromptVO> systemPromptMap) {
        CompressionConfig effectiveConfig = config == null ? CompressionConfig.builder().build() : config;
        AiClientSystemPromptVO prompt = systemPromptMap == null ? null : systemPromptMap.get("7001");
        return CompressionPolicy.builder()
                .proactiveThresholdTokens(effectiveConfig.getProactiveThresholdTokens())
                .maxCompressionAttempts(effectiveConfig.getMaxCompressionAttempts())
                .maxSummaryTokens(effectiveConfig.getMaxSummaryTokens())
                .promptTemplate(prompt == null || prompt.getPromptContent() == null
                        || prompt.getPromptContent().isBlank()
                        ? DEFAULT_COMPRESSION_PROMPT_TEMPLATE : prompt.getPromptContent())
                .build();
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DynamicContext, String> get(ArmoryCommandEntity requestParameter, DynamicContext dynamicContext) throws Exception {
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
