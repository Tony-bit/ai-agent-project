package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiClientRagOrderPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库配置表 DAO
 */
public interface IAiClientRagOrderDao {

    /**
     * 插入
     */
    int insert(AiClientRagOrderPO po);

    /**
     * 更新
     */
    int update(AiClientRagOrderPO po);

    /**
     * 根据ID查询
     */
    AiClientRagOrderPO queryById(@Param("id") Long id);

    /**
     * 根据ragId查询
     */
    AiClientRagOrderPO queryByRagId(@Param("ragId") String ragId);

    /**
     * 查询列表
     */
    List<AiClientRagOrderPO> queryList(AiClientRagOrderPO po);

    /**
     * 根据ID删除
     */
    int deleteById(@Param("id") Long id);
}

