package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AiClientToolMcpPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MCP客户端配置表 DAO
 */
public interface IAiClientToolMcpDao {

    /**
     * 插入
     */
    int insert(AiClientToolMcpPO po);

    /**
     * 更新
     */
    int update(AiClientToolMcpPO po);

    /**
     * 根据ID查询
     */
    AiClientToolMcpPO queryById(@Param("id") Long id);

    /**
     * 根据mcpId查询
     */
    AiClientToolMcpPO queryByMcpId(@Param("mcpId") String mcpId);

    /**
     * 查询列表
     */
    List<AiClientToolMcpPO> queryList(AiClientToolMcpPO po);

    /**
     * 根据ID删除
     */
    int deleteById(@Param("id") Long id);
}

