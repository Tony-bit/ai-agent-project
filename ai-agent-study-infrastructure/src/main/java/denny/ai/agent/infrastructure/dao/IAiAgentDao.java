package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiAgentPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * AI智能体配置表 DAO
 */
@Mapper
public interface IAiAgentDao {

    /**
     * 插入AI智能体配置
     * @param AiAgentPO AI智能体配置对象
     * @return 影响行数
     */
    int insert(AiAgentPO AiAgentPO);

    /**
     * 根据ID更新AI智能体配置
     * @param AiAgentPO AI智能体配置对象
     * @return 影响行数
     */
    int updateById(AiAgentPO AiAgentPO);

    /**
     * 根据智能体ID更新AI智能体配置
     * @param AiAgentPO AI智能体配置对象
     * @return 影响行数
     */
    int updateByAgentId(AiAgentPO AiAgentPO);

    /**
     * 根据ID删除AI智能体配置
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据智能体ID删除AI智能体配置
     * @param agentId 智能体ID
     * @return 影响行数
     */
    int deleteByAgentId(String agentId);

    /**
     * 根据ID查询AI智能体配置
     * @param id 主键ID
     * @return AI智能体配置对象
     */
    AiAgentPO queryById(Long id);

    /**
     * 根据智能体ID查询AI智能体配置
     * @param agentId 智能体ID
     * @return AI智能体配置对象
     */
    AiAgentPO queryByAgentId(String agentId);

    /**
     * 查询所有启用的AI智能体配置
     * @return AI智能体配置列表
     */
    List<AiAgentPO> queryEnabledAgents();

    /**
     * 根据渠道类型查询AI智能体配置
     * @param channel 渠道类型
     * @return AI智能体配置列表
     */
    List<AiAgentPO> queryByChannel(String channel);

    /**
     * 查询所有AI智能体配置
     * @return AI智能体配置列表
     */
    List<AiAgentPO> queryAll();

}

