package denny.ai.agent.domain.service.auto.step;

import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import com.alibaba.fastjson.JSON;
import denny.ai.agent.domain.adapter.repository.IAgentRepository;
import denny.ai.agent.domain.adapter.repository.IRagKnowledgeRepository;
import denny.ai.agent.domain.model.entity.AutoAgentExecuteResultEntity;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.enums.AiAgentEnumVO;
import denny.ai.agent.domain.service.armory.factory.ArmoryObjectRegistry;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.chatmemory.ChatMemoryPersistenceService;
import denny.ai.agent.domain.service.observability.ObservabilityService;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * @author denny
 * 2025/7/27 16:48
 */
public abstract class AbstractExecuteSupport extends AbstractMultiThreadStrategyRouter<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> {

    private final Logger log = LoggerFactory.getLogger(AbstractExecuteSupport.class);

    @Resource
    protected ApplicationContext applicationContext;

    @Resource
    protected IAgentRepository repository;

    @Resource
    protected ArmoryObjectRegistry armoryObjectRegistry;

    @Resource
    protected ObservabilityService observabilityService;

    @Resource
    protected IRagKnowledgeRepository ragKnowledgeRepository;

    @Resource
    protected ChatMemoryPersistenceService chatMemoryPersistenceService;

    public static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    public static final String CHAT_MEMORY_RETRIEVE_SIZE_KEY = "chat_memory_response_size";

    @Override
    protected void multiThread(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

    public ChatClient getChatClientByClientId(String clientId, Integer taskType) {
        String key = AiAgentEnumVO.AI_CLIENT.getBeanName(clientId) + "taskType" + taskType;
        ChatClient chatClient = armoryObjectRegistry.get(key);
        if (chatClient == null) {
            throw new RuntimeException("ChatClient 未初始化，key: " + key);
        }
        return chatClient;
    }

    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

    /**
     * 通用的SSE结果发送方法
     * @param dynamicContext 动态上下文
     * @param result 要发送的结果实体
     */
    protected void sendSseResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                AutoAgentExecuteResultEntity result) {
        ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
        if (emitter == null) {
            log.error("【SSE致命错误】emitter为空！type={}, subType={}, sessionId={}",
                    result.getType(), result.getSubType(), result.getSessionId());
            log.error("【SSE致命错误】dynamicContext.dataObjects内容: {}", dynamicContext.getDataObjects());
            return;
        }

        try {
            String sseData = "data: " + JSON.toJSONString(result) + "\n\n";
                emitter.send(sseData);
            log.info("<<< SSE数据发送成功: type={}, subType={}", result.getType(), result.getSubType());
        } catch (Exception e) {
            log.error("【SSE致命错误】发送SSE结果失败：type={}, subType={}, sessionId={}, error={}, exClass={}",
                    result.getType(), result.getSubType(), result.getSessionId(), e.getMessage(), e.getClass().getName(), e);
        }
    }

    /**
     * 持久化单轮对话（Query + Response）到 MySQL + Redis。
     * <p>
     * 在 chatClient.call() 完成后调用。
     * 失败时降级，不影响主流程。
     *
     * @param sessionId 会话ID
     * @param userId    用户ID
     * @param agentId   智能体ID
     * @param clientId  客户端ID
     * @param input     用户输入
     * @param output    模型输出
     * @param model     模型名称
     * @param latencyMs 响应耗时
     * @param traceId   追踪ID
     */
    protected void persistConversation(String sessionId, String userId, String agentId,
                                       String clientId, String input, String output,
                                       String model, long latencyMs, String traceId) {
        if (chatMemoryPersistenceService == null) {
            return;
        }
        if (StringUtils.isBlank(input) || StringUtils.isBlank(output)) {
            return;
        }
        try {
            chatMemoryPersistenceService.persistConversation(
                    sessionId, userId, agentId, clientId,
                    input, output, model, latencyMs, traceId
            );
        } catch (Exception e) {
            log.warn("会话持久化失败，降级处理: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

}
