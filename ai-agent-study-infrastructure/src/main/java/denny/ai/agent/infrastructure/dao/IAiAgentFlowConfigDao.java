package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiAgentFlowConfigPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 智能体-客户端关联表 DAO
 */
public interface IAiAgentFlowConfigDao {

    /**
     * 插入
     */
    int insert(AiAgentFlowConfigPO po);

    /**
     * 更新
     */
    int update(AiAgentFlowConfigPO po);

    /**
     * 根据ID查询
     */
    AiAgentFlowConfigPO queryById(@Param("id") Long id);

    /**
     * 根据agentId查询列表
     */
    List<AiAgentFlowConfigPO> queryByAgentId(@Param("agentId") Long agentId);

    /**
     * 查询列表
     */
    List<AiAgentFlowConfigPO> queryList(AiAgentFlowConfigPO po);

    /**
     * 根据ID删除
     */
    int deleteById(@Param("id") Long id);
}

