package denny.ai.agent.domain.service.auto.step.chat;

import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.auto.step.routing.ExecutorAdapter;
import denny.ai.agent.domain.service.oss.OSSUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

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
public class GeneralChatNode extends AbstractExecuteSupport implements ExecutorAdapter {

    @Resource
    private OSSUploadService ossUploadService;

    @Autowired(required = false)
    private List<ToolCallback> searchEpisodicMemoryCallbacks;

    private static final String RECOGNIZED_INTENT_KEY = "recognizedIntent";

    private static final String AMBIGUOUS_SYSTEM_PROMPT = """
        您好，我目前还无法准确理解您的意图。请您说得更具体一些，例如：
        - 您是想分析股票或市场行情吗？
        - 您是遇到了什么问题需要推理分析吗？
        - 您是需要查询某些知识或文档吗？
        - 您是想进行系统巡检吗？
        请重新描述您的需求，我会尽力帮助您。
        """;


    @Override
    public String executeSubTask(SubTask subTask,
                               DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("GeneralChatNode 执行子任务: taskId={}, content={}", subTask.getTaskId(), subTask.getContent());

        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .message(subTask.getContent())
                .sessionId(dynamicContext.getValue("sessionId") != null
                        ? dynamicContext.getValue("sessionId").toString() : null)
                .userId(dynamicContext.getValue("userId") != null
                        ? dynamicContext.getValue("userId").toString() : null)
                .build();

        return doTextApply(request, dynamicContext);
    }

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

        String systemPrompt = buildSystemPrompt(recognizedIntent, request.getUserId());

        ChatClient chatClient = getChatClientByClientId("3001", 0);

        var promptBuilder = chatClient.prompt()
                .system(systemPrompt)
                .user(request.getMessage())
                .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, request.getSessionId())
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 1024));

//        if (searchEpisodicMemoryCallbacks != null && !searchEpisodicMemoryCallbacks.isEmpty()) {
//            promptBuilder.toolCallbacks(searchEpisodicMemoryCallbacks.toArray(new ToolCallback[0]));
//            log.info("通用对话已注入情景记忆 Tool, toolCount={}", searchEpisodicMemoryCallbacks.size());
//        }

        String response = streamToEmitter(dynamicContext, promptBuilder, "general_chat_response", request.getSessionId());

        dynamicContext.setCompleted(true);
        dynamicContext.setValue("generalChatResponse", response);

        sendCompleteResult(dynamicContext, request.getSessionId());

        log.info("通用对话完成: intent={}, responseLength={}", recognizedIntent, response.length());
        return response;
    }

    private String doMultimodalApply(ExecuteCommandEntity request,
                                      DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("=== 多模态对话开始 ===");

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

        // Step 4: 调用多模态对话（prompt 已通过 clientId 从数据库加载）
        var promptBuilder = chatClient.prompt()
                .system("")
                .user(multimodalMessage)
                .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, request.getSessionId())
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 0));

        String response = streamToEmitter(dynamicContext, promptBuilder, "multimodal_response", request.getSessionId());

        dynamicContext.setCompleted(true);
        dynamicContext.setValue("generalChatResponse", response);

        sendCompleteResult(dynamicContext, request.getSessionId());

        log.info("多模态对话完成: ossUrl={}, responseLength={}", ossUrl, response.length());
        return response;
    }

    private String buildSystemPrompt(IntentTypeEnum recognizedIntent, String userId) {
        // prompt 已从数据库加载（通过 clientId=3001），这里只追加 userId 上下文
        if (userId != null && !userId.isBlank()) {
            return String.format("[上下文] 当前用户ID: %s", userId);
        }
        return "";
    }

    private void sendCompleteResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String sessionId) {
        AutoAgentExecuteResultEntity result = AutoAgentExecuteResultEntity.createCompleteResult(sessionId);
        sendSseResult(dynamicContext, result);
    }

    /**
     * 流式输出到 SSE
     * <p>
     * 使用 subscribe() 实时发送每一块内容，实现真流式输出。
     * 如果 emitter 为空，则降级为同步调用。
     * </p>
     *
     * @param dynamicContext 动态上下文
     * @param promptBuilder  ChatClient 请求构建器
     * @param subType        SSE 子类型
     * @param sessionId      会话ID
     * @return 完整响应内容
     */
    private String streamToEmitter(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                   ChatClient.ChatClientRequestSpec promptBuilder,
                                   String subType, String sessionId) {
        ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");

        // 降级：emitter 为空时使用同步调用
        if (emitter == null) {
            log.warn("emitter 为空，降级为同步调用");
            return promptBuilder.call().content();
        }

        // 发送开始事件
        sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
                .type("system")
                .subType(subType + "_start")
                .content("开始生成...")
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .build());

        // 使用 StringBuilder 收集完整响应
        StringBuilder fullContent = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);

        // 真流式：subscribe 实时发送
        promptBuilder.stream().content()
                .subscribe(
                        // onNext: 每收到一块立即发送
                        chunk -> {
                            fullContent.append(chunk);
                            sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
                                    .type("content")
                                    .subType(subType)
                                    .content(chunk)
                                    .completed(false)
                                    .timestamp(System.currentTimeMillis())
                                    .build());
                        },
                        // onError: 异常处理
                        error -> {
                            log.error("流式输出异常: subType={}, error={}", subType, error.getMessage(), error);
                            sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
                                    .type("error")
                                    .subType(subType)
                                    .content("流式输出异常: " + error.getMessage())
                                    .completed(true)
                                    .timestamp(System.currentTimeMillis())
                                    .build());
                            latch.countDown();
                        },
                        // onComplete: 完成
                        () -> {
                            sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
                                    .type("content")
                                    .subType(subType)
                                    .content("")
                                    .completed(true)
                                    .timestamp(System.currentTimeMillis())
                                    .build());
                            latch.countDown();
                        }
                );

        try {
            latch.await();  // 等待流完成
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("流式输出等待被中断: subType={}", subType);
        }

        return fullContent.toString();
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity request,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }
}
