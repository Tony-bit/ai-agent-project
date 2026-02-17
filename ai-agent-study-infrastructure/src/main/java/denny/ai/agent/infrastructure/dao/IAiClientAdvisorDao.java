package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiClientAdvisorPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 顾问配置表 DAO
 */
@Mapper
public interface IAiClientAdvisorDao {

    /**
     * 插入顾问配置
     * @param AiClientAdvisorPO 顾问配置对象
     * @return 影响行数
     */
    int insert(AiClientAdvisorPO AiClientAdvisorPO);

    /**
     * 根据ID更新顾问配置
     * @param AiClientAdvisorPO 顾问配置对象
     * @return 影响行数
     */
    int updateById(AiClientAdvisorPO AiClientAdvisorPO);

    /**
     * 根据顾问ID更新顾问配置
     * @param AiClientAdvisorPO 顾问配置对象
     * @return 影响行数
     */
    int updateByAdvisorId(AiClientAdvisorPO AiClientAdvisorPO);

    /**
     * 根据ID删除顾问配置
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据顾问ID删除顾问配置
     * @param advisorId 顾问ID
     * @return 影响行数
     */
    int deleteByAdvisorId(String advisorId);

    /**
     * 根据ID查询顾问配置
     * @param id 主键ID
     * @return 顾问配置对象
     */
    AiClientAdvisorPO queryById(Long id);

    /**
     * 根据顾问ID查询顾问配置
     * @param advisorId 顾问ID
     * @return 顾问配置对象
     */
    AiClientAdvisorPO queryByAdvisorId(String advisorId);

    /**
     * 查询所有顾问配置
     * @return 顾问配置列表
     */
    List<AiClientAdvisorPO> queryAll();

    /**
     * 根据状态查询顾问配置
     * @param status 状态
     * @return 顾问配置列表
     */
    List<AiClientAdvisorPO> queryByStatus(Integer status);

    /**
     * 根据顾问类型查询顾问配置
     * @param advisorType 顾问类型
     * @return 顾问配置列表
     */
    List<AiClientAdvisorPO> queryByAdvisorType(String advisorType);
}

