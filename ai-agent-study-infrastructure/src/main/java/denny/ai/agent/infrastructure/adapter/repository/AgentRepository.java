package denny.ai.agent.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.domain.adapter.repository.IAgentRepository;
import denny.ai.agent.domain.model.valobj.*;
import denny.ai.agent.domain.model.valobj.enums.AiAgentEnumVO;
import denny.ai.agent.infrastructure.dao.*;
import denny.ai.agent.infrastructure.dao.po.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * AiAgent 仓储服务
 *
 * @author denny
 * 2025/6/28 18:09
 */
@Slf4j
@Repository
public class AgentRepository implements IAgentRepository {

    @Resource
    private IAiAgentDao aiAgentDao;

    @Resource
    private IAiAgentFlowConfigDao aiAgentFlowConfigDao;

    @Resource
    private IAiAgentTaskScheduleDao aiAgentTaskScheduleDao;

    @Resource
    private IAiClientAdvisorDao aiClientAdvisorDao;

    @Resource
    private IAiClientApiDao aiClientApiDao;

    @Resource
    private IAiClientConfigDao aiClientConfigDao;

    @Resource
    private IAiClientDao aiClientDao;

    @Resource
    private IAiClientModelDao aiClientModelDao;

    @Resource
    private IAiClientRagOrderDao aiClientRagOrderDao;

    @Resource
    private IAiClientSystemPromptDao aiClientSystemPromptDao;

    @Resource
    private IAiClientToolMcpDao aiClientToolMcpDao;

    @Override
    public List<AiClientApiVO> queryAiClientApiVOListByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientApiVO> result = new ArrayList<>();

        for (String clientId : clientIdList) {
            // 1. 通过clientId查询关联的modelId
            List<AiClientConfigPO> configs = aiClientConfigDao.queryBySourceTypeAndId(AiAgentEnumVO.AI_CLIENT.getCode(), clientId);

            for (AiClientConfigPO config : configs) {
                if (AiAgentEnumVO.AI_CLIENT_MODEL.getCode().equals(config.getTargetType()) && config.getStatus() == 1) {
                    String modelId = config.getTargetId();

                    // 2. 通过modelId查询模型配置，获取apiId
                    AiClientModelPO model = aiClientModelDao.queryByModelId(modelId);
                    if (model != null && model.getStatus() == 1) {
                        String apiId = model.getApiId();

                        // 3. 通过apiId查询API配置信息
                        AiClientApiPO apiConfig = aiClientApiDao.queryByApiId(apiId);
                        if (apiConfig != null && apiConfig.getStatus() == 1) {
                            // 4. 转换为VO对象
                            AiClientApiVO apiVO = AiClientApiVO.builder()
                                    .apiId(apiConfig.getApiId())
                                    .baseUrl(apiConfig.getBaseUrl())
                                    .apiKey(apiConfig.getApiKey())
                                    .completionsPath(apiConfig.getCompletionsPath())
                                    .embeddingsPath(apiConfig.getEmbeddingsPath())
                                    .build();

                            // 避免重复添加相同的API配置
                            if (result.stream().noneMatch(vo -> vo.getApiId().equals(apiVO.getApiId()))) {
                                result.add(apiVO);
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    @Override
    public List<AiClientModelVO> AiClientModelVOByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientModelVO> result = new ArrayList<>();

        for (String clientId : clientIdList) {
            // 1. 通过clientId查询关联的modelId
            List<AiClientConfigPO> configs = aiClientConfigDao.queryBySourceTypeAndId(AiAgentEnumVO.AI_CLIENT.getCode(), clientId);

            for (AiClientConfigPO config : configs) {
                if (AiAgentEnumVO.AI_CLIENT_MODEL.getCode().equals(config.getTargetType()) && config.getStatus() == 1) {
                    String modelId = config.getTargetId();

                    // 2. 通过modelId查询模型配置
                    AiClientModelPO model = aiClientModelDao.queryByModelId(modelId);
                    if (model != null && model.getStatus() == 1) {

                        // 3. 查询该模型关联的tool_mcp配置
                        List<AiClientConfigPO> toolMcpConfigs = aiClientConfigDao.queryBySourceTypeAndId(AiAgentEnumVO.AI_CLIENT_MODEL.getCode(), modelId);
                        List<String> toolMcpIds = new ArrayList<>();

                        for (AiClientConfigPO toolMcpConfig : toolMcpConfigs) {
                            if (AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getCode().equals(toolMcpConfig.getTargetType()) && toolMcpConfig.getStatus() == 1) {
                                toolMcpIds.add(toolMcpConfig.getTargetId());
                            }
                        }

                        // 4. 解析 extParam 中的重试配置
                        ModelRuntimeConfig runtimeConfig = parseRuntimeConfig(model);

                        // 5. 转换为VO对象
                        AiClientModelVO modelVO = AiClientModelVO.builder()
                                .modelId(model.getModelId())
                                .apiId(model.getApiId())
                                .modelName(model.getModelName())
                                .modelType(model.getModelType())
                                .toolMcpIds(toolMcpIds)
                                .retryConfig(runtimeConfig.retryConfig())
                                .compressionConfig(runtimeConfig.compressionConfig())
                                .streamingTimeoutConfig(runtimeConfig.streamingTimeoutConfig())
                                .build();

                        // 避免重复添加相同的模型配置
                        if (result.stream().noneMatch(vo -> vo.getModelId().equals(modelVO.getModelId()))) {
                            result.add(modelVO);
                        }
                    }
                }
            }
        }

        return result;
    }

    @Override
    public List<AiClientToolMcpVO> AiClientToolMcpVOByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientToolMcpVO> result = new ArrayList<>();
        Set<String> processedMcpIds = new HashSet<>();

        for (String clientId : clientIdList) {
            // 1. 通过clientId查询关联的model配置
            List<AiClientConfigPO> clientConfigs = aiClientConfigDao.queryBySourceTypeAndId(AiAgentEnumVO.AI_CLIENT.getCode(), clientId);

            for (AiClientConfigPO clientConfig : clientConfigs) {
                if (AiAgentEnumVO.AI_CLIENT_MODEL.getCode().equals(clientConfig.getTargetType()) && clientConfig.getStatus() == 1) {
                    String modelId = clientConfig.getTargetId();

                    // 2. 通过modelId查询关联的tool_mcp配置
                    List<AiClientConfigPO> modelConfigs = aiClientConfigDao.queryBySourceTypeAndId(AiAgentEnumVO.AI_CLIENT_MODEL.getCode(), modelId);

                    for (AiClientConfigPO modelConfig : modelConfigs) {
                        if (AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getCode().equals(modelConfig.getTargetType()) && modelConfig.getStatus() == 1) {
                            String mcpId = modelConfig.getTargetId();

                            // 避免重复处理相同的mcpId
                            if (processedMcpIds.contains(mcpId)) {
                                continue;
                            }
                            processedMcpIds.add(mcpId);

                            // 3. 通过mcpId查询ai_client_tool_mcp表获取MCP工具配置
                            AiClientToolMcpPO toolMcp = aiClientToolMcpDao.queryByMcpId(mcpId);
                            if (toolMcp != null && toolMcp.getStatus() == 1) {
                                // 4. 转换为VO对象
                                AiClientToolMcpVO mcpVO = AiClientToolMcpVO.builder()
                                        .mcpId(toolMcp.getMcpId())
                                        .mcpName(toolMcp.getMcpName())
                                        .transportType(toolMcp.getTransportType())
                                        .transportConfig(toolMcp.getTransportConfig())
                                        .requestTimeout(toolMcp.getRequestTimeout())
                                        .build();

                                String transportConfig = toolMcp.getTransportConfig();
                                String transportType = toolMcp.getTransportType();

                                try {
                                    if ("sse".equals(transportType)) {
                                        // 解析SSE配置
                                        ObjectMapper objectMapper = new ObjectMapper();
                                        AiClientToolMcpVO.TransportConfigSse transportConfigSse = objectMapper.readValue(transportConfig, AiClientToolMcpVO.TransportConfigSse.class);
                                        mcpVO.setTransportConfigSse(transportConfigSse);
                                    } else if ("stdio".equals(transportType)) {
                                        // 解析STDIO配置
                                        Map<String, AiClientToolMcpVO.TransportConfigStdio.Stdio> stdio = JSON.parseObject(transportConfig,
                                                new TypeReference<>() {
                                                });

                                        AiClientToolMcpVO.TransportConfigStdio transportConfigStdio = new AiClientToolMcpVO.TransportConfigStdio();
                                        transportConfigStdio.setStdio(stdio);

                                        mcpVO.setTransportConfigStdio(transportConfigStdio);
                                    }
                                } catch (Exception e) {
                                    log.error("解析传输配置失败: {}", e.getMessage(), e);
                                }
                                result.add(mcpVO);
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    @Override
    public List<AiClientSystemPromptVO> AiClientSystemPromptVOByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientSystemPromptVO> result = new ArrayList<>();
        Set<String> processedPromptIds = new HashSet<>();

        for (String clientId : clientIdList) {
            // 1. 通过clientId查询关联的prompt配置
            List<AiClientConfigPO> configs = aiClientConfigDao.queryBySourceTypeAndId(AiAgentEnumVO.AI_CLIENT.getCode(), clientId);

            for (AiClientConfigPO config : configs) {
                if ("prompt".equals(config.getTargetType()) && config.getStatus() == 1) {
                    String promptId = config.getTargetId();

                    // 避免重复处理相同的promptId
                    if (processedPromptIds.contains(promptId)) {
                        continue;
                    }
                    processedPromptIds.add(promptId);

                    // 精确查 TYPE_SYSTEM，避免混返
                    AiClientSystemPromptPO systemPrompt =
                            aiClientSystemPromptDao.queryActiveByPromptIdAndType(promptId, AiClientSystemPromptPO.TYPE_SYSTEM);

                    if (systemPrompt != null) {
                        // 3. 转换为VO对象
                        AiClientSystemPromptVO promptVO = AiClientSystemPromptVO.builder()
                                .promptId(systemPrompt.getPromptId())
                                .promptName(systemPrompt.getPromptName())
                                .promptContent(systemPrompt.getPromptContent())
                                .description(systemPrompt.getDescription())
                                .promptType(systemPrompt.getPromptType())
                                .build();

                        result.add(promptVO);
                    }
                }
            }
        }

        return result;
    }


    @Override
    public List<AiClientAdvisorVO> AiClientAdvisorVOByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientAdvisorVO> result = new ArrayList<>();
        Set<String> processedAdvisorIds = new HashSet<>();

        for (String clientId : clientIdList) {
            // 1. 查询客户端相关的advisor配置
            List<AiClientConfigPO> configs = aiClientConfigDao.queryBySourceTypeAndId("client", clientId);

            for (AiClientConfigPO config : configs) {
                if (config.getStatus() != 1 || !"advisor".equals(config.getTargetType())) {
                    continue;
                }

                String advisorId = config.getTargetId();
                if (processedAdvisorIds.contains(advisorId)) {
                    continue;
                }
                processedAdvisorIds.add(advisorId);

                // 2. 查询advisor详细信息
                AiClientAdvisorPO aiClientAdvisor = aiClientAdvisorDao.queryByAdvisorId(advisorId);
                if (aiClientAdvisor == null || aiClientAdvisor.getStatus() != 1) {
                    continue;
                }

                // 3. 解析extParam中的配置
                AiClientAdvisorVO.ChatMemory chatMemory = null;
                AiClientAdvisorVO.RagAnswer ragAnswer = null;

                String extParam = aiClientAdvisor.getExtParam();
                if (extParam != null && !extParam.trim().isEmpty()) {
                    try {
                        if ("ChatMemory".equals(aiClientAdvisor.getAdvisorType())) {
                            // 解析chatMemory配置
                            chatMemory = JSON.parseObject(extParam, AiClientAdvisorVO.ChatMemory.class);
                        } else if ("RagAnswer".equals(aiClientAdvisor.getAdvisorType())) {
                            // 解析ragAnswer配置
                            ragAnswer = JSON.parseObject(extParam, AiClientAdvisorVO.RagAnswer.class);
                        }
                    } catch (Exception e) {
                        // 解析失败时忽略，使用默认值null
                    }
                }

                // 4. 构建AiClientAdvisorVO对象
                AiClientAdvisorVO advisorVO = AiClientAdvisorVO.builder()
                        .advisorId(aiClientAdvisor.getAdvisorId())
                        .advisorName(aiClientAdvisor.getAdvisorName())
                        .advisorType(aiClientAdvisor.getAdvisorType())
                        .orderNum(aiClientAdvisor.getOrderNum())
                        .chatMemory(chatMemory)
                        .ragAnswer(ragAnswer)
                        .build();

                result.add(advisorVO);
            }
        }

        return result;
    }

    @Override
    public List<AiClientVO> AiClientVOByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientVO> result = new ArrayList<>();
        Set<String> processedClientIds = new HashSet<>();

        for (String clientId : clientIdList) {
            if (processedClientIds.contains(clientId)) {
                continue;
            }
            processedClientIds.add(clientId);

            // 1. 查询客户端基本信息
            AiClientPO aiClient = aiClientDao.queryByClientId(clientId);
            if (aiClient == null || aiClient.getStatus() != 1) {
                continue;
            }

            // 2. 查询客户端相关配置
            List<AiClientConfigPO> configs = aiClientConfigDao.queryBySourceTypeAndId("client", clientId);

            String modelId = null;
            Integer taskType = 0;
            Map<String, Integer> map = new HashMap<>();
            List<String> promptIdList = new ArrayList<>();
            List<String> mcpIdList = new ArrayList<>();
            List<String> advisorIdList = new ArrayList<>();

            for (AiClientConfigPO config : configs) {
                if (config.getStatus() != 1) {
                    continue;
                }

                switch (config.getTargetType()) {
                    case "model":
                        modelId = config.getTargetId();
                        taskType = config.getTaskType();
                        map.putIfAbsent(modelId, taskType);
                        break;
                    case "prompt":
                        promptIdList.add(config.getTargetId());
                        break;
                    case "tool_mcp":
                        mcpIdList.add(config.getTargetId());
                        break;
                    case "advisor":
                        advisorIdList.add(config.getTargetId());
                        break;
                }
            }

            // 3. 构建AiClientVO对象
            map.forEach((key, value) -> {
                AiClientVO aiClientVO = AiClientVO.builder()
                        .clientId(aiClient.getClientId())
                        .clientName(aiClient.getClientName())
                        .description(aiClient.getDescription())
                        .modelId(key)
                        .taskType(value)
                        .promptIdList(promptIdList)
                        .mcpIdList(mcpIdList)
                        .advisorIdList(advisorIdList)
                        .build();

                result.add(aiClientVO);
            });
        }

        return result;
    }

    @Override
    public List<AiClientApiVO> queryAiClientApiVOListByModelIds(List<String> modelIdList) {
        if (modelIdList == null || modelIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientApiVO> result = new ArrayList<>();

        for (String modelId : modelIdList) {
            // 1. 通过modelId查询模型配置，获取apiId
            AiClientModelPO model = aiClientModelDao.queryByModelId(modelId);
            if (model != null && model.getStatus() == 1) {
                String apiId = model.getApiId();

                // 2. 通过apiId查询API配置信息
                AiClientApiPO apiConfig = aiClientApiDao.queryByApiId(apiId);
                if (apiConfig != null && apiConfig.getStatus() == 1) {
                    // 3. 转换为VO对象
                    AiClientApiVO apiVO = AiClientApiVO.builder()
                            .apiId(apiConfig.getApiId())
                            .baseUrl(apiConfig.getBaseUrl())
                            .apiKey(apiConfig.getApiKey())
                            .completionsPath(apiConfig.getCompletionsPath())
                            .embeddingsPath(apiConfig.getEmbeddingsPath())
                            .build();

                    // 避免重复添加相同的API配置
                    if (result.stream().noneMatch(vo -> vo.getApiId().equals(apiVO.getApiId()))) {
                        result.add(apiVO);
                    }
                }
            }
        }

        return result;
    }

    @Override
    public List<AiClientModelVO> AiClientModelVOByModelIds(List<String> modelIdList) {
        if (modelIdList == null || modelIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientModelVO> result = new ArrayList<>();

        for (String modelId : modelIdList) {
            // 通过modelId查询模型配置
            AiClientModelPO model = aiClientModelDao.queryByModelId(modelId);
            if (model != null && model.getStatus() == 1) {
                // 转换为VO对象
                AiClientModelVO modelVO = AiClientModelVO.builder()
                        .modelId(model.getModelId())
                        .apiId(model.getApiId())
                        .modelName(model.getModelName())
                        .modelType(model.getModelType())
                        .build();

                // 避免重复添加相同的模型配置
                if (result.stream().noneMatch(vo -> vo.getModelId().equals(modelVO.getModelId()))) {
                    result.add(modelVO);
                }
            }
        }

        return result;
    }

    @Override
    public Map<String, AiAgentClientFlowConfigVO> queryAiAgentClientFlowConfig(String aiAgentId) {
        if (aiAgentId == null || aiAgentId.trim().isEmpty()) {
            return Map.of();
        }

        try {
            // 根据智能体ID查询流程配置列表
            List<AiAgentFlowConfigPO> flowConfigs = aiAgentFlowConfigDao.queryByAgentId(aiAgentId);

            if (flowConfigs == null || flowConfigs.isEmpty()) {
                return Map.of();
            }

            // 批量查出所有 TYPE_STEP 生效记录，一次 DB 查询解决 N+1
            List<AiClientSystemPromptPO> activeStepPrompts =
                    aiClientSystemPromptDao.queryActivePromptsByPromptType(AiClientSystemPromptPO.TYPE_STEP);
            Map<String, String> stepPromptMap = activeStepPrompts.stream()
                    .collect(Collectors.toMap(
                            AiClientSystemPromptPO::getPromptId,
                            AiClientSystemPromptPO::getPromptContent,
                            (v1, v2) -> {
                                log.warn("Duplicate promptId detected: {}, keeping first value", v1);
                                return v1;
                            }
                    ));

            // 转换为Map结构，key为clientId，value为AiAgentClientFlowConfigVO
            Map<String, AiAgentClientFlowConfigVO> result = new HashMap<>();

            for (AiAgentFlowConfigPO flowConfig : flowConfigs) {
                // 命中则覆盖，无则 fallback
                String stepPrompt = stepPromptMap.getOrDefault(flowConfig.getClientId(), flowConfig.getStepPrompt());

                AiAgentClientFlowConfigVO configVO = AiAgentClientFlowConfigVO.builder()
                        .clientId(flowConfig.getClientId())
                        .clientName(flowConfig.getClientName())
                        .clientType(flowConfig.getClientType())
                        .sequence(flowConfig.getSequence())
                        .stepPrompt(stepPrompt)
                        .build();

                result.put(flowConfig.getClientType(), configVO);
            }

            return result;
        } catch (NumberFormatException e) {
            log.error("Invalid aiAgentId format: {}", aiAgentId, e);
            return Map.of();
        } catch (Exception e) {
            log.error("Query ai agent client flow config failed, aiAgentId: {}", aiAgentId, e);
            return Map.of();
        }
    }

    @Override
    public List<AiAgentClientFlowConfigVO> queryActiveFlowConfigsByClientType(String clientType) {
        if (clientType == null || clientType.isBlank()) {
            return List.of();
        }
        List<AiAgentFlowConfigPO> flowConfigs = aiAgentFlowConfigDao.queryByClientType(clientType);
        if (flowConfigs == null || flowConfigs.isEmpty()) {
            return List.of();
        }
        return flowConfigs.stream()
                .map(flowConfig -> AiAgentClientFlowConfigVO.builder()
                        .clientId(flowConfig.getClientId())
                        .clientName(flowConfig.getClientName())
                        .clientType(flowConfig.getClientType())
                        .sequence(flowConfig.getSequence())
                        .stepPrompt(flowConfig.getStepPrompt())
                        .build())
                .toList();
    }

    @Override
    public Map<String, AiAgentClientFlowConfigVO> queryAllFlowConfigForIntentRouting() {
        try {
            List<AiAgentFlowConfigPO> flowConfigs = aiAgentFlowConfigDao.queryAllForIntentRouting();
            if (flowConfigs == null || flowConfigs.isEmpty()) {
                return Map.of();
            }

            // 批量查出所有 TYPE_STEP 生效记录，一次 DB 查询解决 N+1
            List<AiClientSystemPromptPO> activeStepPrompts =
                    aiClientSystemPromptDao.queryActivePromptsByPromptType(AiClientSystemPromptPO.TYPE_STEP);
            Map<String, String> stepPromptMap = activeStepPrompts.stream()
                    .collect(Collectors.toMap(
                            AiClientSystemPromptPO::getPromptId,
                            AiClientSystemPromptPO::getPromptContent,
                            (v1, v2) -> v1
                    ));

            Map<String, AiAgentClientFlowConfigVO> result = new HashMap<>();
            for (AiAgentFlowConfigPO flowConfig : flowConfigs) {
                String stepPrompt = stepPromptMap.getOrDefault(flowConfig.getClientId(), flowConfig.getStepPrompt());
                AiAgentClientFlowConfigVO configVO = AiAgentClientFlowConfigVO.builder()
                        .clientId(flowConfig.getClientId())
                        .clientName(flowConfig.getClientName())
                        .clientType(flowConfig.getClientType())
                        .sequence(flowConfig.getSequence())
                        .stepPrompt(stepPrompt)
                        .build();
                result.put(flowConfig.getClientType(), configVO);
            }
            return result;
        } catch (Exception e) {
            log.error("Query all flow config for intent routing failed", e);
            return Map.of();
        }
    }

    @Override
    public Map<String, AiClientSystemPromptVO> queryAiClientSystemPromptMapByClientIds(List<String> clientIdList) {
        List<AiClientSystemPromptVO> aiClientSystemPrompts = AiClientSystemPromptVOByClientIds(clientIdList);

        if (null == aiClientSystemPrompts || aiClientSystemPrompts.isEmpty()) {
            return Collections.emptyMap();
        }

        // 将PO对象直接构建Map，无需冗余转换
        return aiClientSystemPrompts.stream()
                .collect(Collectors.toMap(
                        AiClientSystemPromptVO::getPromptId,
                        Function.identity(),
                        (v1, v2) -> v1
                ));
    }

    private ModelRuntimeConfig parseRuntimeConfig(AiClientModelPO modelPO) {
        AiClientModelVO.CompressionConfig defaults = AiClientModelVO.CompressionConfig.builder().build();
        if (modelPO.getExtParam() == null || modelPO.getExtParam().trim().isEmpty()) {
            log.warn("extparam is null, modelId: {}", modelPO.getModelId());
            return new ModelRuntimeConfig(null, defaults, null);
        }
        String extParam = modelPO.getExtParam();
        try {
            JSONObject root = JSON.parseObject(extParam);
            boolean composite = root.containsKey("retryConfig") || root.containsKey("compressionConfig")
                    || root.containsKey("streamingTimeout");
            AiClientModelVO.RetryConfig retryConfig = composite
                    ? root.getObject("retryConfig", AiClientModelVO.RetryConfig.class)
                    : root.toJavaObject(AiClientModelVO.RetryConfig.class);
            AiClientModelVO.CompressionConfig compressionConfig = composite
                    ? root.getObject("compressionConfig", AiClientModelVO.CompressionConfig.class)
                    : null;
            if (compressionConfig == null) {
                compressionConfig = defaults;
            }
            AiClientModelVO.StreamingTimeoutConfig streamingTimeoutConfig = composite
                    ? root.getObject("streamingTimeout", AiClientModelVO.StreamingTimeoutConfig.class)
                    : null;
            validateCompressionConfig(compressionConfig, modelPO.getModelId());
            validateStreamingTimeoutConfig(streamingTimeoutConfig, modelPO.getModelId());
            return new ModelRuntimeConfig(retryConfig, compressionConfig, streamingTimeoutConfig);
        } catch (Exception e) {
            log.warn("解析模型运行配置失败，modelId={}, error={}", modelPO.getModelId(), e.getMessage());
            if (JSON.isValidObject(extParam)) {
                throw new IllegalArgumentException("Invalid model runtime config, modelId="
                        + modelPO.getModelId() + ", error=" + e.getMessage(), e);
            }
            return new ModelRuntimeConfig(null, defaults, null);
        }
    }

    private void validateCompressionConfig(AiClientModelVO.CompressionConfig config, String modelId) {
        if (config.getProactiveThresholdTokens() <= 0) {
            throw new IllegalArgumentException("compressionConfig.proactiveThresholdTokens must be positive, modelId=" + modelId);
        }
        if (config.getMaxCompressionAttempts() < 1 || config.getMaxCompressionAttempts() > 3) {
            throw new IllegalArgumentException("compressionConfig.maxCompressionAttempts must be between 1 and 3, modelId=" + modelId);
        }
        if (config.getMaxSummaryTokens() <= 0) {
            throw new IllegalArgumentException("compressionConfig.maxSummaryTokens must be positive, modelId=" + modelId);
        }
        if (config.getProactiveThresholdTokens() <= config.getMaxSummaryTokens() + 1024) {
            throw new IllegalArgumentException("compressionConfig has no compression input budget, modelId=" + modelId);
        }
    }

    private void validateStreamingTimeoutConfig(AiClientModelVO.StreamingTimeoutConfig config,
                                                String modelId) {
        if (config == null) {
            return;
        }
        validatePositive(config.getFirstContentTimeoutMs(),
                "streamingTimeout.firstContentTimeoutMs", modelId);
        validatePositive(config.getIdleTimeoutMs(), "streamingTimeout.idleTimeoutMs", modelId);
        validatePositive(config.getTotalTimeoutMs(), "streamingTimeout.totalTimeoutMs", modelId);
    }

    private void validatePositive(Long value, String property, String modelId) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(property + " must be positive, modelId=" + modelId);
        }
    }

    private record ModelRuntimeConfig(AiClientModelVO.RetryConfig retryConfig,
                                      AiClientModelVO.CompressionConfig compressionConfig,
                                      AiClientModelVO.StreamingTimeoutConfig streamingTimeoutConfig) {
    }
}
