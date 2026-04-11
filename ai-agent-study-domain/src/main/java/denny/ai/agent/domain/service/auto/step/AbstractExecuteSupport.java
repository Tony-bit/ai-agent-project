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
import denny.ai.agent.domain.service.observability.ObservabilityService;
import jakarta.annotation.Resource;
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

    public static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    public static final String CHAT_MEMORY_RETRIEVE_SIZE_KEY = "chat_memory_response_size";

    @Override
    protected void multiThread(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

    protected ChatClient getChatClientByClientId(String clientId, Integer taskType) {
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
        log.info(">>> [sendSseResult] 调用开始: type={}, subType={}", result.getType(), result.getSubType());
        log.info(">>> [sendSseResult] dynamicContext.dataObjects keys: {}", dynamicContext.getDataObjects().keySet());
        log.info(">>> [sendSseResult] dynamicContext.hashCode: {}", System.identityHashCode(dynamicContext));

        ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
        if (emitter == null) {
            log.error("【SSE致命错误】emitter为空！type={}, subType={}, sessionId={}",
                    result.getType(), result.getSubType(), result.getSessionId());
            log.error("【SSE致命错误】dynamicContext.dataObjects内容: {}", dynamicContext.getDataObjects());
            return;
        }

        try {
            String sseData = "data: " + JSON.toJSONString(result) + "\n\n";
            log.info(">>> 发送SSE数据: type={}, subType={}, sessionId={}, sseData长度={}",
                    result.getType(), result.getSubType(), result.getSessionId(), sseData.length());
            emitter.send(sseData);
            log.info("<<< SSE数据发送成功: type={}, subType={}", result.getType(), result.getSubType());
        } catch (Exception e) {
            log.error("【SSE致命错误】发送SSE结果失败：type={}, subType={}, sessionId={}, error={}, exClass={}",
                    result.getType(), result.getSubType(), result.getSessionId(), e.getMessage(), e.getClass().getName(), e);
        }
    }

}
