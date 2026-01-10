package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiAgentPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI智能体配置表 DAO
 */
public interface IAiAgentDao {

    /**
     * 插入
     */
    int insert(AiAgentPO po);

    /**
     * 更新
     */
    int update(AiAgentPO po);

    /**
     * 根据ID查询
     */
    AiAgentPO queryById(@Param("id") Long id);

    /**
     * 根据agentId查询
     */
    AiAgentPO queryByAgentId(@Param("agentId") String agentId);

    /**
     * 查询列表
     */
    List<AiAgentPO> queryList(AiAgentPO po);

    /**
     * 根据ID删除
     */
    int deleteById(@Param("id") Long id);
}

