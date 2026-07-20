package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.ChatMessagePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 聊天消息 DAO
 */
@Mapper
public interface IChatMessageDao {

    void insert(ChatMessagePO po);

    List<ChatMessagePO> queryBySessionId(String sessionId);

    /**
     * 首次加载消息列表（按消息索引倒序，获取最新的 N 条）
     */
    List<ChatMessagePO> queryLatestMessages(@Param("sessionId") String sessionId,
                                           @Param("limit") int limit);

    /**
     * 游标分页：基于 messageIndex 的游标
     * 查询指定索引之前的所有消息（更早的消息）
     */
    List<ChatMessagePO> queryByCursor(@Param("sessionId") String sessionId,
                                     @Param("cursorIndex") int cursorIndex,
                                     @Param("limit") int limit);

    int deleteBySessionId(@Param("sessionId") String sessionId);
}
