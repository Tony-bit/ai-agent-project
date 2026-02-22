package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiClientConfigPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI客户端统一关联配置表 DAO
 */
@Mapper
public interface IAiClientConfigDao {

    /**
     * 插入AI客户端配置
     * @param AiClientConfigPO AI客户端配置对象
     * @return 影响行数
     */
    int insert(AiClientConfigPO AiClientConfigPO);

    /**
     * 根据ID更新AI客户端配置
     * @param AiClientConfigPO AI客户端配置对象
     * @return 影响行数
     */
    int updateById(AiClientConfigPO AiClientConfigPO);

    /**
     * 根据源ID更新AI客户端配置
     * @param AiClientConfigPO AI客户端配置对象
     * @return 影响行数
     */
    int updateBySourceId(AiClientConfigPO AiClientConfigPO);

    /**
     * 根据ID删除AI客户端配置
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据源ID删除AI客户端配置
     * @param sourceId 源ID
     * @return 影响行数
     */
    int deleteBySourceId(String sourceId);

    /**
     * 根据ID查询AI客户端配置
     * @param id 主键ID
     * @return AI客户端配置对象
     */
    AiClientConfigPO queryById(Long id);

    /**
     * 根据源ID查询AI客户端配置
     * @param sourceId 源ID
     * @return AI客户端配置对象列表
     */
    List<AiClientConfigPO> queryBySourceId(@Param("sourceId") String sourceId, @Param("inputType") Integer inputType);

    /**
     * 根据目标ID查询AI客户端配置
     * @param targetId 目标ID
     * @return AI客户端配置对象列表
     */
    List<AiClientConfigPO> queryByTargetId(@Param("targetId") String targetId);

    /**
     * 根据源类型和源ID查询AI客户端配置
     * @param sourceType 源类型
     * @param sourceId 源ID
     * @return AI客户端配置对象列表
     */
    List<AiClientConfigPO> queryBySourceTypeAndId(@Param("sourceType") String sourceType, @Param("sourceId") String sourceId);

    /**
     * 根据目标类型和目标ID查询AI客户端配置
     * @param targetType 目标类型
     * @param targetId 目标ID
     * @return AI客户端配置对象列表
     */
    List<AiClientConfigPO> queryByTargetTypeAndId(@Param("targetType") String targetType, @Param("targetId") String targetId);

    /**
     * 查询启用状态的AI客户端配置
     * @return AI客户端配置对象列表
     */
    List<AiClientConfigPO> queryEnabledConfigs();

    /**
     * 查询所有AI客户端配置
     * @return AI客户端配置对象列表
     */
    List<AiClientConfigPO> queryAll();

}

