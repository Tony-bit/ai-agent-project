package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiClientSystemPromptPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 系统提示词配置表 DAO
 */
@Mapper
public interface IAiClientSystemPromptDao {

    /**
     * 插入系统提示词配置
     */
    void insert(AiClientSystemPromptPO AiClientSystemPromptPO);

    /**
     * 根据ID更新系统提示词配置
     */
    int updateById(AiClientSystemPromptPO AiClientSystemPromptPO);

    /**
     * 根据提示词ID更新系统提示词配置
     */
    int updateByPromptId(AiClientSystemPromptPO AiClientSystemPromptPO);

    /**
     * 根据ID删除系统提示词配置
     */
    int deleteById(Long id);

    /**
     * 根据提示词ID删除系统提示词配置
     */
    int deleteByPromptId(String promptId);

    /**
     * 根据ID查询系统提示词配置
     */
    AiClientSystemPromptPO queryById(Long id);

    /**
     * 根据提示词ID查询系统提示词配置
     */
    AiClientSystemPromptPO queryByPromptId(String promptId);

    /**
     * 查询启用的系统提示词配置
     */
    List<AiClientSystemPromptPO> queryEnabledPrompts();

    /**
     * 根据提示词名称查询系统提示词配置
     */
    List<AiClientSystemPromptPO> queryByPromptName(String promptName);

    /**
     * 查询所有系统提示词配置
     */
    List<AiClientSystemPromptPO> queryAll();

    /**
     * 按 promptId + promptType + status=1 查当前生效版本（精确匹配，不会混返 SYSTEM/STEP）
     */
    AiClientSystemPromptPO queryActiveByPromptIdAndType(
            @Param("promptId") String promptId,
            @Param("promptType") Integer promptType);

    /**
     * 查所有历史版本（按 version 倒序）
     */
    List<AiClientSystemPromptPO> queryVersionHistory(
            @Param("promptId") String promptId,
            @Param("promptType") Integer promptType);

    /**
     * 原子激活版本（CASE WHEN 单条 SQL，无并发风险）
     */
    void activateVersion(@Param("id") Long id,
                         @Param("promptId") String promptId,
                         @Param("promptType") Integer promptType);

    /**
     * 批量查询指定 promptType 的所有生效记录（解决 N+1 问题）
     */
    List<AiClientSystemPromptPO> queryActivePromptsByPromptType(@Param("promptType") Integer promptType);

    List<AiClientSystemPromptPO> queryVersionSet(
            @Param("promptIds") java.util.Set<String> promptIds,
            @Param("promptType") Integer promptType,
            @Param("version") Integer version);

    List<AiClientSystemPromptPO> queryActiveSet(
            @Param("promptIds") java.util.Set<String> promptIds,
            @Param("promptType") Integer promptType);

    int deactivatePromptSet(
            @Param("promptIds") java.util.Set<String> promptIds,
            @Param("promptType") Integer promptType);

    int activatePromptSetVersion(
            @Param("promptIds") java.util.Set<String> promptIds,
            @Param("promptType") Integer promptType,
            @Param("version") Integer version);
}
