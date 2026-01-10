package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiClientSystemPromptPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 系统提示词配置表 DAO
 */
public interface IAiClientSystemPromptDao {

    /**
     * 插入
     */
    int insert(AiClientSystemPromptPO po);

    /**
     * 更新
     */
    int update(AiClientSystemPromptPO po);

    /**
     * 根据ID查询
     */
    AiClientSystemPromptPO queryById(@Param("id") Long id);

    /**
     * 根据promptId查询
     */
    AiClientSystemPromptPO queryByPromptId(@Param("promptId") String promptId);

    /**
     * 查询列表
     */
    List<AiClientSystemPromptPO> queryList(AiClientSystemPromptPO po);

    /**
     * 根据ID删除
     */
    int deleteById(@Param("id") Long id);
}

