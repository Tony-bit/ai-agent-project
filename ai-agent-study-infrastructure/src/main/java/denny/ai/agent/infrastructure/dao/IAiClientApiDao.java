package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiClientApiPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * OpenAI API配置表 DAO
 */
public interface IAiClientApiDao {

    /**
     * 插入
     */
    int insert(AiClientApiPO po);

    /**
     * 更新
     */
    int update(AiClientApiPO po);

    /**
     * 根据ID查询
     */
    AiClientApiPO queryById(@Param("id") Long id);

    /**
     * 根据apiId查询
     */
    AiClientApiPO queryByApiId(@Param("apiId") String apiId);

    /**
     * 查询列表
     */
    List<AiClientApiPO> queryList(AiClientApiPO po);

    /**
     * 根据ID删除
     */
    int deleteById(@Param("id") Long id);
}

