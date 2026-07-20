package denny.ai.agent.infrastructure.service;

import denny.ai.agent.api.vo.ChatMessageVO;
import denny.ai.agent.api.vo.ChatSessionVO;
import denny.ai.agent.api.vo.MessageListResult;
import denny.ai.agent.api.vo.SessionListResult;
import denny.ai.agent.domain.service.chatsession.SessionAccessState;
import denny.ai.agent.infrastructure.dao.IChatMessageDao;
import denny.ai.agent.infrastructure.dao.IChatSessionDao;
import denny.ai.agent.infrastructure.dao.po.ChatMessagePO;
import denny.ai.agent.infrastructure.dao.po.ChatSessionPO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话列表查询服务实现
 *
 * @author denny
 */
@Slf4j
@Service
public class ChatSessionQueryService {

    /**
     * 默认每次加载 10 条
     */
    private static final int DEFAULT_PAGE_SIZE = 10;

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private IChatSessionDao chatSessionDao;

    @Resource
    private IChatMessageDao chatMessageDao;

    @Resource
    private SessionOwnershipService sessionOwnershipService;

    /**
     * 获取用户会话列表（支持游标分页）
     *
     * @param userId     用户ID
     * @param cursorTime 游标时间（首次为 null）
     * @param cursorId   游标ID（首次为 null）
     * @return 会话列表结果
     */
    public SessionListResult getSessionList(String userId, String cursorTime, String cursorId) {
        // ========== 边界校验 ==========

        // 1. userId 不能为空
        if (!StringUtils.hasText(userId)) {
            log.warn("获取会话列表失败: userId 为空");
            return SessionListResult.empty();
        }

        // 2. userId 长度校验（防止注入）
        if (userId.length() > 64) {
            log.warn("获取会话列表失败: userId 长度超限, userId={}", userId);
            return SessionListResult.empty();
        }

        // 3. 游标参数校验
        LocalDateTime cursorDateTime = null;
        Long cursorIdValue = null;

        if (StringUtils.hasText(cursorTime)) {
            try {
                cursorDateTime = LocalDateTime.parse(cursorTime, DATETIME_FORMATTER);
            } catch (Exception e) {
                log.warn("游标时间格式错误: cursorTime={}", cursorTime);
                return SessionListResult.empty();
            }

            if (StringUtils.hasText(cursorId)) {
                try {
                    cursorIdValue = Long.parseLong(cursorId);
                } catch (NumberFormatException e) {
                    log.warn("游标ID格式错误: cursorId={}", cursorId);
                    return SessionListResult.empty();
                }
            } else {
                // 有时间游标但没有ID游标，使用时间游标的尾值
                log.warn("游标参数不完整: 有时间游标但无ID游标");
                return SessionListResult.empty();
            }
        } else if (StringUtils.hasText(cursorId)) {
            // 有ID游标但没有时间游标，不合法
            log.warn("游标参数不完整: 有ID游标但无时间游标");
            return SessionListResult.empty();
        }

        // ========== 执行查询 ==========
        List<ChatSessionPO> sessionList;
        if (cursorDateTime == null) {
            // 首次加载
            sessionList = chatSessionDao.queryFirstPage(userId, DEFAULT_PAGE_SIZE + 1);
        } else {
            // 游标加载
            sessionList = chatSessionDao.queryByCursor(userId, cursorDateTime, cursorIdValue, DEFAULT_PAGE_SIZE + 1);
        }

        // ========== 结果处理 ==========
        return buildSessionResult(sessionList);
    }

    /**
     * 获取会话消息列表（支持游标分页）
     *
     * @param sessionId   会话ID
     * @param cursorIndex 游标索引（首次为 null）
     * @return 消息列表结果
     */
    public MessageListResult getSessionMessages(String sessionId, Integer cursorIndex) {
        // ========== 边界校验 ==========

        // 1. sessionId 不能为空
        if (!StringUtils.hasText(sessionId)) {
            log.warn("获取会话消息失败: sessionId 为空");
            return MessageListResult.empty();
        }

        // 2. sessionId 长度校验
        if (sessionId.length() > 64) {
            log.warn("获取会话消息失败: sessionId 长度超限, sessionId={}", sessionId);
            return MessageListResult.empty();
        }

        // 3. sessionId 格式校验（应为字母数字组合）
        if (!sessionId.matches("^[a-zA-Z0-9_-]+$")) {
            log.warn("获取会话消息失败: sessionId 格式非法, sessionId={}", sessionId);
            return MessageListResult.empty();
        }

        // ========== 执行查询 ==========
        List<ChatMessagePO> messageList;
        if (cursorIndex == null) {
            // 首次加载：获取最新的消息
            messageList = chatMessageDao.queryLatestMessages(sessionId, DEFAULT_PAGE_SIZE + 1);
        } else {
            // 游标加载：获取更早的消息
            // 校验 cursorIndex 合理性（应为正整数）
            if (cursorIndex <= 0) {
                log.warn("游标索引无效: cursorIndex={}", cursorIndex);
                return MessageListResult.empty();
            }
            messageList = chatMessageDao.queryByCursor(sessionId, cursorIndex, DEFAULT_PAGE_SIZE + 1);
        }

        // ========== 结果处理 ==========
        return buildMessageResult(messageList);
    }

    public MessageListResult getSessionMessages(String currentUserId,
                                                String sessionId,
                                                Integer cursorIndex) {
        SessionAccessState accessState = sessionOwnershipService.resolve(currentUserId, sessionId);
        if (accessState == SessionAccessState.UNAVAILABLE) {
            throw new SessionQueryFailure(FailureReason.UNAVAILABLE, "session id unavailable");
        }
        if (accessState == SessionAccessState.AVAILABLE) {
            return MessageListResult.empty();
        }
        return getSessionMessages(sessionId, cursorIndex);
    }

    // ========== 私有方法 ==========

    private SessionListResult buildSessionResult(List<ChatSessionPO> sessionList) {
        boolean hasMore = sessionList.size() > DEFAULT_PAGE_SIZE;
        if (hasMore) {
            sessionList = sessionList.subList(0, DEFAULT_PAGE_SIZE);
        }

        List<ChatSessionVO> voList = sessionList.stream()
                .map(this::convertToSessionVO)
                .collect(Collectors.toList());

        SessionListResult result = new SessionListResult();
        result.setSessions(voList);
        result.setHasMore(hasMore);
        result.setSize(voList.size());

        if (hasMore && !sessionList.isEmpty()) {
            ChatSessionPO lastItem = sessionList.get(sessionList.size() - 1);
            result.setNextCursorTime(lastItem.getCreateTime().format(DATETIME_FORMATTER));
            result.setNextCursorId(lastItem.getId());
        }

        return result;
    }

    private MessageListResult buildMessageResult(List<ChatMessagePO> messageList) {
        // 注意：消息是从大到小返回的（倒序），需要反转成从小到大的正序
        boolean hasMore = messageList.size() > DEFAULT_PAGE_SIZE;
        if (hasMore) {
            messageList = messageList.subList(0, DEFAULT_PAGE_SIZE);
        }

        // 反转列表，使消息按时间正序排列
        Collections.reverse(messageList);

        List<ChatMessageVO> voList = messageList.stream()
                .map(this::convertToMessageVO)
                .collect(Collectors.toList());

        MessageListResult result = new MessageListResult();
        result.setMessages(voList);
        result.setHasMore(hasMore);
        result.setSize(voList.size());

        if (hasMore && !messageList.isEmpty()) {
            // 最旧的消息索引作为下一页游标
            result.setNextCursorIndex(messageList.get(0).getMessageIndex());
        }

        return result;
    }

    private ChatSessionVO convertToSessionVO(ChatSessionPO po) {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setSessionId(po.getSessionId());
        vo.setFirstQuery(po.getFirstQuery());
        vo.setLastResponse(po.getLastResponse());
        vo.setMessageCount(po.getMessageCount());
        vo.setStatus(po.getStatus());
        vo.setCreateTime(po.getCreateTime());
        return vo;
    }

    private ChatMessageVO convertToMessageVO(ChatMessagePO po) {
        ChatMessageVO vo = new ChatMessageVO();
        vo.setMessageIndex(po.getMessageIndex());
        vo.setRole(po.getRole());
        vo.setContent(po.getContent());
        vo.setModel(po.getModel());
        vo.setLatencyMs(po.getLatencyMs());
        vo.setCreateTime(po.getCreateTime());
        return vo;
    }

    public enum FailureReason {
        UNAVAILABLE
    }

    public static class SessionQueryFailure extends RuntimeException {

        private final FailureReason reason;

        public SessionQueryFailure(FailureReason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public FailureReason getReason() {
            return reason;
        }
    }
}
