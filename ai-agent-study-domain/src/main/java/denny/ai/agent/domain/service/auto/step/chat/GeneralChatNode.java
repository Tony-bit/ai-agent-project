package denny.ai.agent.domain.service.auto.step.chat;

import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;

/**
 * 通用对话节点
 * <p>
 * 处理意图为 GENERAL_CHAT、AMBIGUOUS、UNKNOWN 的请求。
 * AMBIGUOUS 时构建引导澄清的 Prompt。
 * </p>
 *
 * @author denny
 * 2026/5/10
 */
@Slf4j
@Service("generalChatNode")
public class GeneralChatNode extends AbstractExecuteSupport {

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
