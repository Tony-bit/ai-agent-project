package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiClientAdvisorPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 顾问配置表 DAO
 */
public interface IAiClientAdvisorDao {

    /**
     * 插入
     */
    int insert(AiClientAdvisorPO po);

    /**
     * 更新
     */
    int update(AiClientAdvisorPO po);

    /**
     * 根据ID查询
     */
    AiClientAdvisorPO queryById(@Param("id") Long id);

    /**
     * 根据advisorId查询
     */
    AiClientAdvisorPO queryByAdvisorId(@Param("advisorId") String advisorId);

    /**
     * 查询列表
     */
    List<AiClientAdvisorPO> queryList(AiClientAdvisorPO po);

    /**
     * 根据ID删除
     */
    int deleteById(@Param("id") Long id);
}

