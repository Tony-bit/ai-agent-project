package denny.ai.agent.domain.adapter.repository;

import denny.ai.agent.domain.model.valobj.*;

import java.util.List;
import java.util.Map;

public interface IAgentRepository {
    List<AiClientApiVO> queryAiClientApiVOListByClientIds(List<String> clientIdList);

    List<AiClientModelVO> AiClientModelVOByClientIds(List<String> clientIdList);

    List<AiClientToolMcpVO> AiClientToolMcpVOByClientIds(List<String> clientIdList);

    List<AiClientSystemPromptVO> AiClientSystemPromptVOByClientIds(List<String> clientIdList);

    Map<String, AiClientSystemPromptVO> queryAiClientSystemPromptMapByClientIds(List<String> clientIdList);

    List<AiClientAdvisorVO> AiClientAdvisorVOByClientIds(List<String> clientIdList);

    List<AiClientVO> AiClientVOByClientIds(List<String> clientIdList);

    List<AiClientApiVO> queryAiClientApiVOListByModelIds(List<String> modelIdList);

    List<AiClientModelVO> AiClientModelVOByModelIds(List<String> modelIdList);

    Map<String, AiAgentClientFlowConfigVO> queryAiAgentClientFlowConfig(String aiAgentId);

    /**
     * 查询所有客户端配置用于意图路由场景。
     * 按 client_type 分组，每种类型取 sequence 最小的记录，
     * 意图路由下游节点各自按 client_type 取用。
     * @return 按 clientType 聚合的配置 Map
     */
    Map<String, AiAgentClientFlowConfigVO> queryAllFlowConfigForIntentRouting();

}
