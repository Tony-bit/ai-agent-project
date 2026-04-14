package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.ChatMessagePO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 聊天消息 DAO
 */
@Mapper
public interface IChatMessageDao {

    void insert(ChatMessagePO po);

    List<ChatMessagePO> queryBySessionId(String sessionId);
}
