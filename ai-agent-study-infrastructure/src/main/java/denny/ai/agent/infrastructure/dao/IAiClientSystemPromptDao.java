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
}

