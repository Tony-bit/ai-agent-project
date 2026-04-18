package denny.ai.agent.infrastructure.service;

import com.alibaba.cloud.ai.memory.mem0.core.Mem0ServiceClient;
import com.alibaba.cloud.ai.memory.mem0.model.Mem0ServerRequest;
import denny.ai.agent.infrastructure.dao.IChatSessionDao;
import denny.ai.agent.infrastructure.dao.po.ChatSessionPO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 会话记忆同步服务
 * 将 ai_chat_session 中 addMemory=0 的会话批量同步到 Mem0 长期记忆
 *
 * @author denny
 */
@Slf4j
@Service
public class ChatSessionMemorySyncService {

    @Resource
    private IChatSessionDao chatSessionDao;

    @Resource
    private Mem0ServiceClient mem0ServiceClient;

    /**
     * 将指定会话的会话内容同步到 Mem0 长期记忆
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void syncSessionToMemory(String userId, String sessionId) {
        // 1. 查询 addMemory=0 的会话
        List<ChatSessionPO> unsyncedSessions = chatSessionDao.queryByAddMemoryUnsynced(userId, sessionId);
        if (CollectionUtils.isEmpty(unsyncedSessions)) {
            log.info("没有需要同步到记忆的会话, userId={}, sessionId={}", userId, sessionId);
            return;
        }

        // 2. 构造批量 messages（每条记录拆分为 user + assistant 两条）
        List<Mem0ServerRequest.Message> messages = unsyncedSessions.stream()
                .flatMap(session -> {
                    Mem0ServerRequest.Message userMsg = new Mem0ServerRequest.Message("user", session.getFirstQuery());
                    Mem0ServerRequest.Message assistantMsg = new Mem0ServerRequest.Message("assistant", session.getLastResponse());
                    return Stream.of(userMsg, assistantMsg);
                })
                .collect(Collectors.toList());

        // 取第一条的 agentId 作为本次写入的 agentId（业务上同一 session 的 agentId 一致）
        String agentId = unsyncedSessions.get(0).getAgentId();

        mem0ServiceClient.addMemory(
                Mem0ServerRequest.MemoryCreate.builder()
                        .userId(userId)
                        .agentId(agentId)
                        .runId(sessionId)
                        .messages(messages)
                        .build()
        );
        log.info("批量同步会话到记忆完成, sessionId={}, 原始记录数={}, messages数={}",
                sessionId, unsyncedSessions.size(), messages.size());

        // 3. 批量更新 addMemory=1
        List<String> syncedIds = unsyncedSessions.stream()
                .map(ChatSessionPO::getSessionId)
                .collect(Collectors.toList());
        chatSessionDao.batchUpdateAddMemory(syncedIds);
        log.info("批量更新 addMemory=1 完成, 数量={}", syncedIds.size());
    }
}
