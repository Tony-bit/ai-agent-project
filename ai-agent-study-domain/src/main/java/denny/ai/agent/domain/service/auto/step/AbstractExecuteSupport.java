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
import denny.ai.agent.domain.service.sse.SseEventSink;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;

/**
 * @author denny
 * 2025/7/27 16:48
 */
public abstract class AbstractExecuteSupport extends AbstractMultiThreadStrategyRouter<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> {

    private final Logger log = LoggerFactory.getLogger(AbstractExecuteSupport.class);

    protected static final String SSE_DISCONNECTED_KEY = "sseDisconnected";
    protected static final String SSE_SEND_LOCK_KEY = "sseSendLock";
    protected static final String SSE_EVENT_SINK_KEY = "sseEventSink";

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

    @Resource
    private denny.ai.agent.domain.service.persona.IUserPersonaCacheService userPersonaCacheService;

    @Resource
    private denny.ai.agent.domain.model.valobj.MemoryProperties memoryProperties;

    @Resource
    private StreamingChatResponseCollector streamingChatResponseCollector;

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

    protected String collectStreamingResponse(ChatClient.ChatClientRequestSpec requestSpec,
                                              String operationName,
                                              SseEventSink sseEventSink) {
        StreamingChatResponseCollector collector = streamingChatResponseCollector == null
                ? new StreamingChatResponseCollector() : streamingChatResponseCollector;
        return collector.collect(requestSpec.stream().content(), operationName,
                sseEventSink == null ? null : sseEventSink.cancellationSignal());
    }

    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

    protected boolean shouldContinueSse(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (dynamicContext == null) {
            return true;
        }
        SseEventSink sink = dynamicContext.getValue(SSE_EVENT_SINK_KEY);
        if (sink != null) {
            return sink.shouldContinue();
        }
        return !isSseDisconnected(dynamicContext);
    }

    protected SseEventSink getSseEventSink(
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        return dynamicContext == null ? null : dynamicContext.getValue(SSE_EVENT_SINK_KEY);
    }

    /**
     * 通用的SSE结果发送方法
     * @param dynamicContext 动态上下文
     * @param result 要发送的结果实体
     */
    protected boolean sendSseResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                    AutoAgentExecuteResultEntity result) {
        if (dynamicContext == null) {
            return false;
        }
        SseEventSink sink = dynamicContext.getValue(SSE_EVENT_SINK_KEY);
        if (sink != null) {
            if (!sink.shouldContinue()) {
                log.debug("SSE sink 已关闭，跳过发送: type={}, subType={}, state={}",
                        result.getType(), result.getSubType(), sink.state());
                return false;
            }
            boolean accepted = sink.sendBusiness(result.getType(), result);
            if (!accepted) {
                log.debug("SSE sink 拒绝业务事件: type={}, subType={}, state={}",
                        result.getType(), result.getSubType(), sink.state());
            }
            return accepted;
        }

        if (isSseDisconnected(dynamicContext)) {
            log.debug("SSE连接已断开，跳过发送: type={}, subType={}",
                    result.getType(), result.getSubType());
            return false;
        }

        ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
        if (emitter == null) {
            log.error("【SSE致命错误】emitter为空！type={}, subType={}, sessionId={}",
                    result.getType(), result.getSubType(), result.getSessionId());
            log.error("【SSE致命错误】dynamicContext.dataObjects内容: {}", dynamicContext.getDataObjects());
            return false;
        }

        try {
            String sseData = "data: " + JSON.toJSONString(result) + "\n\n";
            Object sendLock = dynamicContext.getValue(SSE_SEND_LOCK_KEY);
            boolean sent;
            if (sendLock == null) {
                emitter.send(sseData);
                sent = true;
            } else if (sendLock instanceof Lock lock) {
                lock.lock();
                try {
                    if (!isSseDisconnected(dynamicContext)) {
                        emitter.send(sseData);
                        sent = true;
                    } else {
                        sent = false;
                    }
                } finally {
                    lock.unlock();
                }
            } else {
                synchronized (sendLock) {
                    if (!isSseDisconnected(dynamicContext)) {
                        emitter.send(sseData);
                        sent = true;
                    } else {
                        sent = false;
                    }
                }
            }
            if (!sent) {
                return false;
            }
            log.debug("<<< SSE数据发送成功: type={}, subType={}", result.getType(), result.getSubType());
            return true;
        } catch (Exception e) {
            if (isClientDisconnect(e)) {
                markSseDisconnected(dynamicContext);
                log.warn("SSE连接已断开，停止继续推送: type={}, subType={}, sessionId={}, error={}, exClass={}",
                        result.getType(), result.getSubType(), result.getSessionId(), e.getMessage(), e.getClass().getName());
                return false;
            }
            log.error("【SSE发送错误】发送SSE结果失败：type={}, subType={}, sessionId={}, error={}, exClass={}",
                    result.getType(), result.getSubType(), result.getSessionId(), e.getMessage(), e.getClass().getName(), e);
            return false;
        }
    }

    private boolean isSseDisconnected(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        SseEventSink sink = dynamicContext.getValue(SSE_EVENT_SINK_KEY);
        if (sink != null) {
            return !sink.shouldContinue();
        }
        Object disconnected = dynamicContext.getValue(SSE_DISCONNECTED_KEY);
        if (disconnected instanceof AtomicBoolean flag) {
            return flag.get();
        }
        return Boolean.TRUE.equals(disconnected);
    }

    private void markSseDisconnected(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        SseEventSink sink = dynamicContext.getValue(SSE_EVENT_SINK_KEY);
        if (sink != null) {
            sink.markDisconnected(null);
            return;
        }
        Object disconnected = dynamicContext.getValue(SSE_DISCONNECTED_KEY);
        if (disconnected instanceof AtomicBoolean flag) {
            flag.set(true);
        } else {
            dynamicContext.setValue(SSE_DISCONNECTED_KEY, true);
        }
    }

    private boolean isClientDisconnect(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if (className.contains("AsyncRequestNotUsableException")
                    || className.contains("ClientAbortException")
                    || current instanceof IllegalStateException && message != null && message.contains("ResponseBodyEmitter has already completed")
                    || current instanceof IOException
                    || containsAny(message,
                            "ServletOutputStream failed to flush",
                            "Broken pipe",
                            "Connection reset",
                            "你的主机中的软件中止了一个已建立的连接")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsAny(String value, String... patterns) {
        if (value == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (value.contains(pattern)) {
                return true;
            }
        }
        return false;
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

    /**
     * 将跨会话用户画像（persona）注入到 DynamicContext。
     * <p>
     * 从 Mem0/Redis 获取用户画像并写入 dynamicContext.setValue("persona", memories)。
     * 幂等：若 persona 已存在则跳过；配置关闭时跳过；异常时降级为空字符串。
     *
     * @param dynamicContext 动态上下文，非空
     * @param request       执行命令实体，从中取 userId，非空
     */
    protected void injectPersonaContext(
            denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
            denny.ai.agent.domain.model.entity.ExecuteCommandEntity request) {
        if (StringUtils.isBlank(request.getUserId())) {
            log.debug("userId 为空，跳过画像注入");
            return;
        }
        if (memoryProperties == null) {
            log.error("MemoryProperties 未注入，跳过画像注入, userId={}", request.getUserId());
            return;
        }
        if (!memoryProperties.isInjectPersona()) {
            return;
        }
        if (dynamicContext.getValue("persona") != null) {
            return;
        }
        if (userPersonaCacheService == null) {
            log.warn("IUserPersonaCacheService 未注入，跳过画像注入, userId={}", request.getUserId());
            return;
        }
        try {
            String memories = userPersonaCacheService.getUserPersona(request.getUserId());
            dynamicContext.setValue("persona", memories);
            log.info("已注入用户画像到上下文, userId={}, hasPersona={}",
                    request.getUserId(), !memories.isEmpty());
        } catch (Exception e) {
            log.warn("用户画像检索失败，降级处理: userId={}, error={}",
                    request.getUserId(), e.getMessage());
            dynamicContext.setValue("persona", "");
        }
    }

}
