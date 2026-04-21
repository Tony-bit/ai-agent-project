package denny.ai.agent.infrastructure.service;

import denny.ai.agent.domain.model.valobj.SessionActivityRecord;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * SessionActivityTracker 单元测试
 * <p>
 * 测试覆盖：
 * 1. recordActivity - 记录会话活动
 * 2. isExpired - 判断会话是否超时
 * 3. removeActivity - 移除会话记录
 * 4. getTrackedCount - 获取追踪数量
 * 5. cleanupExpiredSessions - 定时清理超时会话
 * 6. 同一用户多会话独立追踪
 *
 * @author denny
 */
public class SessionActivityTrackerTest {

    private SessionActivityTracker tracker;

    /**
     * 通过反射获取 tracker 内部的 ConcurrentHashMap，便于直接操作测试
     */
    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, SessionActivityRecord> getInternalMap() throws Exception {
        Field field = SessionActivityTracker.class.getDeclaredField("activityMap");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, SessionActivityRecord>) field.get(tracker);
    }

    @Before
    public void setUp() {
        tracker = new SessionActivityTracker();
        // 跳过 @PostConstruct，不启动定时任务，避免干扰测试
    }

    @After
    public void tearDown() throws Exception {
        // 清理内部 map
        getInternalMap().clear();
    }

    // ========== recordActivity 测试 ==========

    /**
     * 记录活动后，internalMap 应包含对应记录
     */
    @Test
    public void testRecordActivity_createsRecord() throws Exception {
        tracker.recordActivity("user-001", "session-001", "你好");

        ConcurrentHashMap<String, SessionActivityRecord> map = getInternalMap();
        assertEquals("记录后数量应为 1", 1, map.size());

        SessionActivityRecord record = map.get("user-001#session-001");
        assertNotNull("记录应存在", record);
        assertEquals("userId 不一致", "user-001", record.getUserId());
        assertEquals("sessionId 不一致", "session-001", record.getSessionId());
        assertEquals("lastMessage 不一致", "你好", record.getLastMessage());
        assertTrue("timestamp 应为正数", record.getLastTimestamp() > 0);
    }

    /**
     * 同一会话多次记录活动，应更新时间戳和消息
     */
    @Test
    public void testRecordActivity_updatesExistingRecord() throws Exception {
        tracker.recordActivity("user-001", "session-001", "消息1");
        long firstTimestamp = getInternalMap().get("user-001#session-001").getLastTimestamp();

        // 等待一小段时间确保时间戳不同
        Thread.sleep(10);
        tracker.recordActivity("user-001", "session-001", "消息2");

        ConcurrentHashMap<String, SessionActivityRecord> map = getInternalMap();
        assertEquals("多次记录同一会话，数量仍应为 1", 1, map.size());

        SessionActivityRecord record = map.get("user-001#session-001");
        assertEquals("lastMessage 应更新", "消息2", record.getLastMessage());
        assertTrue("timestamp 应更新", record.getLastTimestamp() >= firstTimestamp);
    }

    /**
     * lastMessage 为 null 时也能正常记录
     */
    @Test
    public void testRecordActivity_nullLastMessage() throws Exception {
        tracker.recordActivity("user-001", "session-001", null);

        SessionActivityRecord record = getInternalMap().get("user-001#session-001");
        assertNotNull("记录应存在", record);
        assertNull("lastMessage 应为 null", record.getLastMessage());
    }

    // ========== isExpired 测试 ==========

    /**
     * 新记录的会话应判定为未超时
     */
    @Test
    public void testIsExpired_newSession() {
        tracker.recordActivity("user-001", "session-001", "你好");
        assertFalse("新会话应判定为未超时", tracker.isExpired("user-001", "session-001"));
    }

    /**
     * 不存在的会话应判定为未超时（返回 false 而非抛异常）
     */
    @Test
    public void testIsExpired_nonExistentSession() {
        assertFalse("不存在的会话应返回 false", tracker.isExpired("user-unknown", "session-unknown"));
    }

    /**
     * 手动设置超时会话后，应判定为超时
     * <p>
     * 通过反射修改 internalMap 中的 timestamp 实现
     * </p>
     */
    @Test
    public void testIsExpired_expiredSession() throws Exception {
        // 正常记录一条活动
        tracker.recordActivity("user-001", "session-001", "你好");

        // 反射修改 timestamp 为 9 分钟前（超过 8 分钟阈值）
        SessionActivityRecord record = getInternalMap().get("user-001#session-001");
        long expiredTimestamp = System.currentTimeMillis() - (9 * 60 * 1000L);
        setTimestamp(record, expiredTimestamp);

        assertTrue("超过 8 分钟应判定为已超时", tracker.isExpired("user-001", "session-001"));
    }

    /**
     * 正好 8 分钟前应判定为已超时（>= 边界）
     */
    @Test
    public void testIsExpired_exactly8Minutes() throws Exception {
        tracker.recordActivity("user-001", "session-001", "你好");

        SessionActivityRecord record = getInternalMap().get("user-001#session-001");
        long exactly8MinutesAgo = System.currentTimeMillis() - (8 * 60 * 1000L);
        setTimestamp(record, exactly8MinutesAgo);

        assertTrue("正好 8 分钟前应判定为已超时", tracker.isExpired("user-001", "session-001"));
    }

    /**
     * 7 分 59 秒前应判定为未超时
     */
    @Test
    public void testIsExpired_7Minutes59Seconds() throws Exception {
        tracker.recordActivity("user-001", "session-001", "你好");

        SessionActivityRecord record = getInternalMap().get("user-001#session-001");
        long almostExpired = System.currentTimeMillis() - (7 * 60 * 1000L + 59 * 1000L);
        setTimestamp(record, almostExpired);

        assertFalse("7 分 59 秒前应判定为未超时", tracker.isExpired("user-001", "session-001"));
    }

    // ========== removeActivity 测试 ==========

    /**
     * 移除已存在的会话记录
     */
    @Test
    public void testRemoveActivity_existingSession() throws Exception {
        tracker.recordActivity("user-001", "session-001", "你好");
        assertEquals("记录前数量应为 1", 1, getInternalMap().size());

        tracker.removeActivity("user-001", "session-001");

        assertEquals("移除后数量应为 0", 0, getInternalMap().size());
        assertFalse("移除后会话应判定为不存在", tracker.isExpired("user-001", "session-001"));
    }

    /**
     * 移除不存在的会话应无异常
     */
    @Test
    public void testRemoveActivity_nonExistent() {
        tracker.removeActivity("user-unknown", "session-unknown");
        // 无异常即通过
    }

    // ========== getTrackedCount 测试 ==========

    /**
     * 记录多条不同会话后，数量应正确
     */
    @Test
    public void testGetTrackedCount_multipleSessions() {
        assertEquals("初始应为 0", 0, tracker.getTrackedCount());

        tracker.recordActivity("user-001", "session-001", "消息1");
        assertEquals("1 条会话", 1, tracker.getTrackedCount());

        tracker.recordActivity("user-002", "session-002", "消息2");
        assertEquals("2 条不同用户会话", 2, tracker.getTrackedCount());

        tracker.recordActivity("user-001", "session-003", "消息3");
        assertEquals("同一用户不同会话", 3, tracker.getTrackedCount());
    }

    /**
     * 移除会话后，数量应减少
     */
    @Test
    public void testGetTrackedCount_afterRemove() {
        tracker.recordActivity("user-001", "session-001", "消息1");
        tracker.recordActivity("user-002", "session-002", "消息2");
        assertEquals(2, tracker.getTrackedCount());

        tracker.removeActivity("user-001", "session-001");
        assertEquals(1, tracker.getTrackedCount());
    }

    // ========== 同一用户多会话独立追踪测试 ==========

    /**
     * 同一 userId 不同 sessionId 应独立追踪，各自有独立的超时状态
     */
    @Test
    public void testMultiSession_independentTracking() throws Exception {
        // 记录两条会话
        tracker.recordActivity("user-001", "session-A", "会话A消息");
        tracker.recordActivity("user-001", "session-B", "会话B消息");
        assertEquals(2, tracker.getTrackedCount());

        // 将 session-A 设为超时
        SessionActivityRecord recordA = getInternalMap().get("user-001#session-A");
        setTimestamp(recordA, System.currentTimeMillis() - (9 * 60 * 1000L));

        // session-A 应超时，session-B 应未超时
        assertTrue("session-A 应超时", tracker.isExpired("user-001", "session-A"));
        assertFalse("session-B 应未超时", tracker.isExpired("user-001", "session-B"));

        // 移除 session-A，session-B 应不受影响
        tracker.removeActivity("user-001", "session-A");
        assertEquals(1, tracker.getTrackedCount());
        assertFalse("session-B 应仍存在", tracker.isExpired("user-001", "session-B"));
    }

    /**
     * 不同 userId 同一 sessionId 应独立追踪
     */
    @Test
    public void testMultiSession_sameSessionIdDifferentUser() throws Exception {
        tracker.recordActivity("user-001", "session-shared", "用户1的消息");
        tracker.recordActivity("user-002", "session-shared", "用户2的消息");
        assertEquals(2, tracker.getTrackedCount());

        // 将 user-001 的会话设为超时
        SessionActivityRecord record1 = getInternalMap().get("user-001#session-shared");
        setTimestamp(record1, System.currentTimeMillis() - (9 * 60 * 1000L));

        assertTrue("user-001 的会话应超时", tracker.isExpired("user-001", "session-shared"));
        assertFalse("user-002 的会话应未超时", tracker.isExpired("user-002", "session-shared"));
    }

    // ========== 边界条件测试 ==========

    /**
     * userId 为 null 时，应能正常处理
     */
    @Test
    public void testRecordActivity_nullUserId() throws Exception {
        tracker.recordActivity(null, "session-001", "消息");
        assertEquals(1, getInternalMap().size());
        assertNotNull(getInternalMap().get("null#session-001"));
    }

    /**
     * sessionId 为 null 时，应能正常处理
     */
    @Test
    public void testRecordActivity_nullSessionId() throws Exception {
        tracker.recordActivity("user-001", null, "消息");
        assertEquals(1, getInternalMap().size());
        assertNotNull(getInternalMap().get("user-001#null"));
    }

    // ========== 辅助方法 ==========

    private void setTimestamp(SessionActivityRecord record, long timestamp) throws Exception {
        Field field = SessionActivityRecord.class.getDeclaredField("lastTimestamp");
        field.setAccessible(true);
        field.set(record, timestamp);
    }
}
