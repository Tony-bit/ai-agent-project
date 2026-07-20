package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.ChatSessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天会话 DAO
 */
@Mapper
public interface IChatSessionDao {

    void insert(ChatSessionPO po);

    void updateLastResponse(@Param("sessionId") String sessionId,
                            @Param("lastResponse") String lastResponse,
                            @Param("messageCountIncrement") int messageCountIncrement);

    ChatSessionPO queryBySessionId(@Param("sessionId") String sessionId);

    /**
     * 首次加载会话列表（按创建时间倒序）
     */
    List<ChatSessionPO> queryFirstPage(@Param("userId") String userId,
                                       @Param("limit") int limit);

    /**
     * 游标分页：基于 createTime + id 的复合游标
     * 查询指定时间之前的所有会话
     */
    List<ChatSessionPO> queryByCursor(@Param("userId") String userId,
                                      @Param("cursorTime") LocalDateTime cursorTime,
                                      @Param("cursorId") Long cursorId,
                                      @Param("limit") int limit);

    /**
     * 查询 addMemory=0 的会话（待同步到记忆）
     */
    List<ChatSessionPO> queryByAddMemoryUnsynced(@Param("userId") String userId,
                                                   @Param("sessionId") String sessionId);

    /**
     * 批量更新 addMemory=1
     */
    void batchUpdateAddMemory(@Param("sessionIdList") List<String> sessionIdList);

    /**
     * 查询会话最近活动时间（update_time）
     *
     * @param sessionId 会话ID
     * @return 最近活动时间，未查到返回 null
     */
    LocalDateTime queryLastActivityTime(@Param("sessionId") String sessionId);

    int deleteByUserIdAndSessionId(@Param("userId") String userId,
                                   @Param("sessionId") String sessionId);
}
