package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiClientConfigPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI客户端统一关联配置表 DAO
 */
public interface IAiClientConfigDao {

    /**
     * 插入
     */
    int insert(AiClientConfigPO po);

    /**
     * 更新
     */
    int update(AiClientConfigPO po);

    /**
     * 根据ID查询
     */
    AiClientConfigPO queryById(@Param("id") Long id);

    /**
     * 根据sourceId查询列表
     */
    List<AiClientConfigPO> queryBySourceId(@Param("sourceId") String sourceId);

    /**
     * 根据targetId查询列表
     */
    List<AiClientConfigPO> queryByTargetId(@Param("targetId") String targetId);

    /**
     * 查询列表
     */
    List<AiClientConfigPO> queryList(AiClientConfigPO po);

    /**
     * 根据ID删除
     */
    int deleteById(@Param("id") Long id);
}

