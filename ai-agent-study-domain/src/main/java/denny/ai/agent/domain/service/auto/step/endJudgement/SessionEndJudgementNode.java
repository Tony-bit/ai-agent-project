package denny.ai.agent.domain.service.auto.step.endJudgement;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.chatsession.ISessionEndDetectionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 会话结束判断节点
 * <p>
 * 所有 AI Flow 的最后一个统一节点，负责：
 * 1. 更新会话最近活跃时间（滑动窗口）
 * 2. 正则关键词快速判断
 * 3. 正则未命中时交由 LLM 兜底判断
 * </p>
 *
 * @author denny
 */
@Slf4j
@Service("sessionEndJudgementNode")
public class SessionEndJudgementNode extends AbstractExecuteSupport {

    @Resource
    private ISessionEndDetectionService sessionEndDetectionService;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter,
                             DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        String sessionId = requestParameter.getSessionId();
        String userId = requestParameter.getUserId();
        String lastMessage = requestParameter.getMessage();
        String traceId = dynamicContext.getTraceId();

        log.info("\n🔚 === 会话结束判断 === sessionId={}, lastMessage={}", sessionId, lastMessage);

        // ====== 步骤1：更新滑动窗口（始终执行）======
        try {
            sessionEndDetectionService.recordActivity(userId, sessionId, lastMessage);
        } catch (Exception e) {
            log.warn("滑动窗口记录失败，降级处理: sessionId={}, error={}", sessionId, e.getMessage());
        }

        // ====== 步骤2：正则关键词快速判断 ======
        boolean keywordEnded = sessionEndDetectionService.matchEndKeyword(lastMessage);
        if (keywordEnded) {
            log.info("会话 {} 正则命中结束词，触发批量持久化", sessionId);
            persistMemoryAndCleanup(sessionId, userId);
            return "session ended (keyword matched)";
        }

        // ====== 步骤3：正则未命中，LLM 兜底判断 ======
        try {
            Map<String, Object> spanMetadata = new HashMap<>();
            spanMetadata.put("node", "session_end_judgement");
            spanMetadata.put("sessionId", sessionId);
            String spanId = StringUtils.isNotBlank(traceId)
                    ? observabilityService.startSpan(traceId, "session_end_judgement", spanMetadata)
                    : "";

            AiAgentClientFlowConfigVO config = dynamicContext.getAiAgentClientFlowConfigVOMap()
                    .get(AiClientTypeEnumVO.SESSION_END_JUDGEMENT.getCode());
            if (config == null) {
                log.warn("未找到 SESSION_END_JUDGEMENT 配置，跳过 Mem0 持久化, sessionId={}", sessionId);
                return "session not ended (config missing)";
            }

            String prompt = String.format(config.getStepPrompt(), lastMessage);
            ChatClient chatClient = getChatClientByClientId(config.getClientId(), 0);

            long startAt = System.currentTimeMillis();
            String llmResponse = chatClient.prompt(prompt).call().content();
            long latencyMs = System.currentTimeMillis() - startAt;

            boolean llmEnded = sessionEndDetectionService.parseLlmResponse(llmResponse);
            log.info("会话 {} LLM 判断为 ended={}, response={}, latencyMs={}",
                    sessionId, llmEnded, llmResponse, latencyMs);

            if (llmEnded) {
                persistMemoryAndCleanup(sessionId, userId);
            }

            if (StringUtils.isNotBlank(spanId)) {
                observabilityService.endSpan(spanId, true, null);
            }

            return llmEnded ? "session ended (llm)" : "session not ended (llm)";
        } catch (Exception e) {
            log.warn("会话 {} LLM 调用失败: error={}", sessionId, e.getMessage());
            return "session not ended (llm error)";
        }
    }

    /**
     * 会话结束：持久化到 Mem0 长期记忆，然后清理滑动窗口记录
     */
    private void persistMemoryAndCleanup(String sessionId, String userId) {
        // 1. 持久化会话到 Mem0（通过 service 接口调用 infrastructure 层）
        try {
            sessionEndDetectionService.syncSessionToMemory(userId, sessionId);
            log.info("会话 {} 已同步到 Mem0 长期记忆", sessionId);
        } catch (Exception e) {
            log.warn("会话 {} 同步到 Mem0 失败，不影响后续清理: error={}", sessionId, e.getMessage());
        }

        // 2. 从滑动窗口移除会话记录
        try {
            sessionEndDetectionService.removeActivity(userId, sessionId);
            log.info("会话 {} 已从滑动窗口移除", sessionId);
        } catch (Exception e) {
            log.warn("会话 {} 从滑动窗口移除失败: error={}", sessionId, e.getMessage());
        }
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String>
            get(ExecuteCommandEntity requestParameter,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        // 会话结束判断是最后一个节点，返回 null 表示整个流程结束
        return defaultStrategyHandler;
    }
}
