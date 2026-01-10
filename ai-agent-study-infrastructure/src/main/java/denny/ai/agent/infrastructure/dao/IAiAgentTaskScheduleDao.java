package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiAgentTaskSchedulePO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 智能体任务调度配置表 DAO
 */
public interface IAiAgentTaskScheduleDao {

    /**
     * 插入
     */
    int insert(AiAgentTaskSchedulePO po);

    /**
     * 更新
     */
    int update(AiAgentTaskSchedulePO po);

    /**
     * 根据ID查询
     */
    AiAgentTaskSchedulePO queryById(@Param("id") Long id);

    /**
     * 根据agentId查询列表
     */
    List<AiAgentTaskSchedulePO> queryByAgentId(@Param("agentId") Long agentId);

    /**
     * 查询列表
     */
    List<AiAgentTaskSchedulePO> queryList(AiAgentTaskSchedulePO po);

    /**
     * 根据ID删除
     */
    int deleteById(@Param("id") Long id);
}

