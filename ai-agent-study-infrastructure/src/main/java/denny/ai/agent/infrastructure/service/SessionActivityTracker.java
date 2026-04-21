package denny.ai.agent.infrastructure.service;

import denny.ai.agent.domain.model.valobj.SessionActivityRecord;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话活动追踪器（滑动窗口实现）
 * <p>
 * 使用 ConcurrentHashMap 记录会话活动时间，key = userId#sessionId
 * 定时任务每 2 分钟遍历所有记录，清理超过 8 分钟无活动的会话
 * </p>
 *
 * @author denny
 */
@Slf4j
@Component
public class SessionActivityTracker {

    /**
     * 活动时间记录
     * key: "userId#sessionId"
     */
    private final ConcurrentHashMap<String, SessionActivityRecord> activityMap = new ConcurrentHashMap<>();

    /**
     * 定时任务执行器
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SessionActivityTracker-Scheduler");
        t.setDaemon(true);
        return t;
    });

    /**
     * 超时阈值：8 分钟
     */
    private static final long TIMEOUT_MILLIS = 8 * 60 * 1000L;

    /**
     * 清理间隔：2 分钟
     */
    private static final long CLEANUP_INTERVAL_MILLIS = 2 * 60 * 1000L;

    @PostConstruct
    public void init() {
        scheduler.scheduleAtFixedRate(this::cleanupExpiredSessions, CLEANUP_INTERVAL_MILLIS, CLEANUP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        log.info("SessionActivityTracker 初始化完成，超时阈值={}ms，清理间隔={}ms", TIMEOUT_MILLIS, CLEANUP_INTERVAL_MILLIS);
    }

    /**
     * 记录会话活动（每次用户发消息时调用）
     *
     * @param userId      用户ID
     * @param sessionId  会话ID
     * @param lastMessage 最后一条用户消息
     */
    public void recordActivity(String userId, String sessionId, String lastMessage) {
        String compositeKey = buildKey(userId, sessionId);
        long now = System.currentTimeMillis();

        SessionActivityRecord record = SessionActivityRecord.builder()
                .userId(userId)
                .sessionId(sessionId)
                .lastMessage(lastMessage)
                .lastTimestamp(now)
                .build();

        activityMap.put(compositeKey, record);
        log.debug("记录会话活动: compositeKey={}", compositeKey);
    }

    /**
     * 判断会话是否已超时（超过 8 分钟无活动）
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return true = 已超时，false = 仍在活动时间窗口内或不存在
     */
    public boolean isExpired(String userId, String sessionId) {
        String compositeKey = buildKey(userId, sessionId);
        SessionActivityRecord record = activityMap.get(compositeKey);

        if (record == null) {
            log.debug("会话不存在于追踪器: compositeKey={}", compositeKey);
            return false;
        }

        long elapsed = System.currentTimeMillis() - record.getLastTimestamp();
        boolean expired = elapsed >= TIMEOUT_MILLIS;

        log.debug("检查会话是否超时: compositeKey={}, elapsed={}ms, expired={}", compositeKey, elapsed, expired);
        return expired;
    }

    /**
     * 移除会话活动记录
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     */
    public void removeActivity(String userId, String sessionId) {
        String compositeKey = buildKey(userId, sessionId);
        SessionActivityRecord removed = activityMap.remove(compositeKey);
        if (removed != null) {
            log.debug("移除会话活动记录: compositeKey={}", compositeKey);
        }
    }

    /**
     * 获取当前追踪的会话数量
     */
    public int getTrackedCount() {
        return activityMap.size();
    }

    /**
     * 定时清理超时会话
     */
    private void cleanupExpiredSessions() {
        try {
            long now = System.currentTimeMillis();
            long expireTime = now - TIMEOUT_MILLIS;
            int cleanedCount = 0;

            Iterator<SessionActivityRecord> iterator = activityMap.values().iterator();
            while (iterator.hasNext()) {
                SessionActivityRecord record = iterator.next();
                if (record.getLastTimestamp() <= expireTime) {
                    iterator.remove();
                    cleanedCount++;
                    log.debug("清理超时会话: compositeKey={}, lastTimestamp={}",
                            buildKey(record.getUserId(), record.getSessionId()), record.getLastTimestamp());
                }
            }

            if (cleanedCount > 0) {
                log.info("清理超时会话完成，共清理 {} 个，剩余 {} 个", cleanedCount, activityMap.size());
            }
        } catch (Exception e) {
            log.error("清理超时会话异常", e);
        }
    }

    private String buildKey(String userId, String sessionId) {
        return userId + "#" + sessionId;
    }
}
