package denny.ai.agent.domain.service.chatsession;

/**
 * 会话记忆持久化服务接口
 * <p>
 * 将会话内容同步到 Mem0 长期记忆，并在数据库中标记同步状态。
 * </p>
 *
 * @author denny
 */
public interface ISessionMemoryPersistenceService {

    /**
     * 将指定会话同步到 Mem0 长期记忆
     * <p>
     * 具体步骤：
     * 1. 从数据库查询 addMemory=0（未同步）的会话原始记录
     * 2. 构造消息列表，调用 Mem0 持久化
     * 3. 批量更新会话记录的 addMemory=1（已同步）
     * </p>
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     */
    void syncSessionToMemory(String userId, String sessionId);
}
