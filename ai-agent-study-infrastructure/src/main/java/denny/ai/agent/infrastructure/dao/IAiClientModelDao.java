package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiClientModelPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 聊天模型配置表 DAO
 */
public interface IAiClientModelDao {

    /**
     * 插入
     */
    int insert(AiClientModelPO po);

    /**
     * 更新
     */
    int update(AiClientModelPO po);

    /**
     * 根据ID查询
     */
    AiClientModelPO queryById(@Param("id") Long id);

    /**
     * 根据modelId查询
     */
    AiClientModelPO queryByModelId(@Param("modelId") String modelId);

    /**
     * 查询列表
     */
    List<AiClientModelPO> queryList(AiClientModelPO po);

    /**
     * 根据ID删除
     */
    int deleteById(@Param("id") Long id);
}

