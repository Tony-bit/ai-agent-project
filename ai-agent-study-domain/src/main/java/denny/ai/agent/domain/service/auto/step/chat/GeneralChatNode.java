package denny.ai.agent.domain.service.auto.step.chat;

import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.oss.OSSUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;

/**
 * 通用对话节点
 * <p>
 * 处理意图为 GENERAL_CHAT、AMBIGUOUS、UNKNOWN 的请求。
 * AMBIGUOUS 时构建引导澄清的 Prompt。
 * 支持多模态对话（inputType=1 时处理图片输入）。
 * </p>
 *
 * @author denny
 * 2026/5/10
 */
@Slf4j
@Service("generalChatNode")
public class GeneralChatNode extends AbstractExecuteSupport {

    @Resource
    private OSSUploadService ossUploadService;

    private static final String RECOGNIZED_INTENT_KEY = "recognizedIntent";

    private static final String AMBIGUOUS_SYSTEM_PROMPT = """
        您好，我目前还无法准确理解您的意图。请您说得更具体一些，例如：
        - 您是想分析股票或市场行情吗？
        - 您是遇到了什么问题需要推理分析吗？
        - 您是需要查询某些知识或文档吗？
        - 您是想进行系统巡检吗？
        请重新描述您的需求，我会尽力帮助您。
        """;

    private static final String GENERAL_CHAT_SYSTEM_PROMPT = """
        你是一个友好的AI助手，请根据用户的问题提供有帮助的回答。
        """;

    @Override
    protected String doApply(ExecuteCommandEntity request,
                            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        // 判断是否有图片输入
        if (request.getInputType() != null && request.getInputType() == 1 && request.getFile() != null) {
            return doMultimodalApply(request, dynamicContext);
        }
        return doTextApply(request, dynamicContext);
    }

    private String doTextApply(ExecuteCommandEntity request,
                              DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        IntentTypeEnum recognizedIntent = dynamicContext.getValue(RECOGNIZED_INTENT_KEY);

        sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
                .type("system")
                .subType("general_chat_start")
                .content("正在思考...")
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .build());

        String systemPrompt = resolveSystemPrompt(recognizedIntent);

        ChatClient chatClient = getChatClientByClientId("default", 0);

        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(request.getMessage())
                .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, request.getSessionId())
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 1024))
                .call().content();

        sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
                .type("content")
                .subType("general_chat_response")
                .content(response)
                .completed(true)
                .timestamp(System.currentTimeMillis())
                .build());

        dynamicContext.setCompleted(true);
        dynamicContext.setValue("generalChatResponse", response);

        sendCompleteResult(dynamicContext, request.getSessionId());

        log.info("通用对话完成: intent={}, responseLength={}", recognizedIntent, response.length());
        return response;
    }

    private String doMultimodalApply(ExecuteCommandEntity request,
                                      DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 多模态对话开始 ===");

        sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
                .type("system")
                .subType("multimodal_start")
                .content("正在识别图片...")
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .build());

        // Step 1: 上传图片到 OSS，获取 URL
        String ossUrl = ossUploadService.upload(request.getFile());
        if (ossUrl == null || ossUrl.isEmpty()) {
            throw new RuntimeException("图片上传 OSS 失败");
        }
        log.info("图片上传成功，OSS URL: {}", ossUrl);

        // Step 2: 获取 ChatClient（从 dynamicContext 的 flowConfig 获取，或硬编码 clientId）
        Map<String, AiAgentClientFlowConfigVO> flowConfigMap = dynamicContext.getAiAgentClientFlowConfigVOMap();
        String clientId = "multimodal";
        if (flowConfigMap != null && flowConfigMap.containsKey("multimodal")) {
            clientId = flowConfigMap.get("multimodal").getClientId();
        }

        ChatClient chatClient = getChatClientByClientId(clientId, 0);

        // Step 3: 构建用户消息，将图片 URL 包含在消息中
        // Qwen VL 等模型支持在消息中直接引用图片 URL
        String userMessage = request.getMessage() != null ? request.getMessage() : "请描述这张图片的内容";
        String multimodalMessage = userMessage + "\n\n[图片]: " + ossUrl;

        // Step 4: 调用多模态对话
        String response = chatClient.prompt()
                .system(GENERAL_CHAT_SYSTEM_PROMPT)
                .user(multimodalMessage)
                .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, request.getSessionId())
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 0))
                .call().content();

        sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
                .type("content")
                .subType("multimodal_response")
                .content(response)
                .completed(true)
                .timestamp(System.currentTimeMillis())
                .build());

        dynamicContext.setCompleted(true);
        dynamicContext.setValue("generalChatResponse", response);

        sendCompleteResult(dynamicContext, request.getSessionId());

        log.info("多模态对话完成: ossUrl={}, responseLength={}", ossUrl, response.length());
        return response;
    }

    private String resolveSystemPrompt(IntentTypeEnum recognizedIntent) {
        if (recognizedIntent == IntentTypeEnum.AMBIGUOUS) {
            return AMBIGUOUS_SYSTEM_PROMPT;
        }
        return GENERAL_CHAT_SYSTEM_PROMPT;
    }

    private void sendCompleteResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createCompleteResult(sessionId);
        sendSseResult(dynamicContext, result);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity request,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }
}
