package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.ChatSessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
