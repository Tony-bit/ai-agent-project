package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiClientPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI客户端配置表 DAO
 */
public interface IAiClientDao {

    /**
     * 插入
     */
    int insert(AiClientPO po);

    /**
     * 更新
     */
    int update(AiClientPO po);

    /**
     * 根据ID查询
     */
    AiClientPO queryById(@Param("id") Long id);

    /**
     * 根据clientId查询
     */
    AiClientPO queryByClientId(@Param("clientId") String clientId);

    /**
     * 查询列表
     */
    List<AiClientPO> queryList(AiClientPO po);

    /**
     * 根据ID删除
     */
    int deleteById(@Param("id") Long id);
}

