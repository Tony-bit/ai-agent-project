package denny.ai.agent.domain.service.armory.business.data.impl;

import denny.ai.agent.domain.adapter.repository.IAgentRepository;
import denny.ai.agent.domain.model.valobj.*;
import denny.ai.agent.domain.model.valobj.enums.AiAgentEnumVO;
import denny.ai.agent.domain.service.armory.business.data.ILoadDataStrategy;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import denny.ai.agent.domain.model.entity.ArmoryCommandEntity;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 客户端串联，加载策略数据
 */
@Slf4j
@Service("aiClientLoadDataStrategy")
public class AiClientLoadDataStrategy implements ILoadDataStrategy {
    public static final String GLOBAL_COMPRESSION_CLIENT_ID = "globalCompressionClientId";
    private static final String COMPRESSION_ASSISTANT = "COMPRESSION_ASSISTANT";

    @Resource
    private IAgentRepository repository;

    @Resource
    protected ThreadPoolExecutor threadPoolExecutor;

    @Override
    public void loadData(ArmoryCommandEntity armoryCommandEntity, DynamicContext dynamicContext) {
        List<AiAgentClientFlowConfigVO> compressionFlows =
                repository.queryActiveFlowConfigsByClientType(COMPRESSION_ASSISTANT);
        Set<String> compressionClientIds = compressionFlows == null ? Set.of()
                : compressionFlows.stream()
                        .map(AiAgentClientFlowConfigVO::getClientId)
                        .filter(clientId -> clientId != null && !clientId.isBlank())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (compressionClientIds.size() != 1) {
            throw new IllegalStateException("Exactly one global compression clientId is required, found="
                    + compressionClientIds);
        }
        String globalCompressionClientId = compressionClientIds.iterator().next();
        dynamicContext.setValue(GLOBAL_COMPRESSION_CLIENT_ID, globalCompressionClientId);

        LinkedHashSet<String> mergedClientIds = new LinkedHashSet<>();
        if (armoryCommandEntity.getCommandIdList() != null) {
            mergedClientIds.addAll(armoryCommandEntity.getCommandIdList());
        }
        mergedClientIds.add(globalCompressionClientId);
        List<String> clientIdList = new ArrayList<>(mergedClientIds);

        CompletableFuture<List<AiClientApiVO>> aiClientApiListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询配置数据(ai_client_api) {}", clientIdList);
            return repository.queryAiClientApiVOListByClientIds(clientIdList);
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientModelVO>> aiClientModelListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询配置数据(ai_client_model) {}", clientIdList);
            return repository.AiClientModelVOByClientIds(clientIdList);
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientToolMcpVO>> aiClientToolMcpListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询配置数据(ai_client_tool_mcp) {}", clientIdList);
            return repository.AiClientToolMcpVOByClientIds(clientIdList);
        }, threadPoolExecutor);

        CompletableFuture<Map<String, AiClientSystemPromptVO>> aiClientSystemPromptListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询配置数据(ai_client_system_prompt) {}", clientIdList);
            return repository.queryAiClientSystemPromptMapByClientIds(clientIdList);
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientAdvisorVO>> aiClientAdvisorListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询配置数据(ai_client_advisor) {}", clientIdList);
            return repository.AiClientAdvisorVOByClientIds(clientIdList);
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientVO>> aiClientListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询配置数据(ai_client) {}", clientIdList);
            return repository.AiClientVOByClientIds(clientIdList);
        }, threadPoolExecutor);

        CompletableFuture.allOf(aiClientApiListFuture).thenRun(() -> {
            dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_API.getDataName(), aiClientApiListFuture.join());
            dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_MODEL.getDataName(), aiClientModelListFuture.join());
            dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName(), aiClientSystemPromptListFuture.join());
            dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getDataName(), aiClientToolMcpListFuture.join());
            dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT_ADVISOR.getDataName(), aiClientAdvisorListFuture.join());
            dynamicContext.setValue(AiAgentEnumVO.AI_CLIENT.getDataName(), aiClientListFuture.join());

        }).join();


    }
}
