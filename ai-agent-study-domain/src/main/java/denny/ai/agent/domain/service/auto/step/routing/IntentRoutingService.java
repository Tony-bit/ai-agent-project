package denny.ai.agent.domain.service.auto.step.routing;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.entity.IntentFewshotSample;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.BaseSlot;
import denny.ai.agent.domain.model.valobj.IntentRoutingResult;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.StockSlot;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.intent.IntentFewshotService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 意图识别服务
 *
 * @author denny
 * 2026/5/10
 */
@Slf4j
@Service
public class IntentRoutingService extends AbstractExecuteSupport {

    @Resource
    private IntentFewshotService intentFewshotService;

    public MultiIntentRoutingResult routeUnified(String userMessage,
                                                 List<String> historyMessages,
                                                 AiAgentClientFlowConfigVO configVO) {
        List<IntentFewshotSample> fewshotSamples = retrieveFewshotSamples(userMessage);
        String prompt = IntentRoutingPrompt.buildUnifiedRoutingPrompt(userMessage, historyMessages, fewshotSamples);
        log.info("统一路由 LLM 原始请求: prompt:{}", prompt);
        try {
            ChatClient chatClient = getChatClientByClientId(configVO.getClientId(), 0);
            String response = chatClient.prompt(prompt).call().content();
            log.info("统一路由 LLM 原始响应: userMessage={}, clientId={}, response=[{}], responseLen={}",
                    userMessage, configVO.getClientId(), response, response == null ? -1 : response.length());
            if (response == null || response.isBlank()) {
                log.warn("响应为空，降级为 GENERAL_CHAT");
                return fallbackMultiIntentResult("LLM返回为空");
            }
            return parseUnifiedResponse(response);
        } catch (Exception e) {
            log.error("统一路由调用失败，降级为 GENERAL_CHAT: userMessage={}, clientId={}, error={}",
                    userMessage, configVO.getClientId(), e.getMessage(), e);
            return fallbackMultiIntentResult("LLM调用异常: " + e.getMessage());
        }
    }

    @Override
    protected String doApply(ExecuteCommandEntity request,
                             DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        throw new UnsupportedOperationException("IntentRoutingService 不支持策略节点执行");
    }

    @Override
    public cn.bugstack.wrench.design.framework.tree.StrategyHandler<ExecuteCommandEntity,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
            ExecuteCommandEntity request,
            DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        throw new UnsupportedOperationException("IntentRoutingService 不支持路由分发");
    }

    private List<IntentFewshotSample> retrieveFewshotSamples(String userMessage) {
        try {
            return intentFewshotService.retrieveTopK(userMessage, 5);
        } catch (Exception e) {
            log.warn("Few-Shot 检索失败，降级为空样本: userMessage={}, error={}",
                    userMessage, e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析 LLM 返回的 JSON 响应（支持切槽字段）
     * 解析失败时降级为 UNKNOWN + LOW
     */
    public IntentRoutingResult parseResponse(String response) {
        if (response == null || response.isBlank()) {
            log.warn("意图识别 LLM 返回为空，降级为 UNKNOWN + LOW");
            return fallbackResult("LLM返回为空");
        }

        try {
            String jsonStr = extractJson(response);
            JSONObject json = JSON.parseObject(jsonStr);

            String intentCode = json.getString("intent");
            String confidenceCode = json.getString("confidence");
            String reasoning = json.getString("reasoning");
            if (reasoning == null) {
                reasoning = "无推理过程";
            }

            IntentTypeEnum intent = IntentTypeEnum.fromCode(intentCode);
            ConfidenceEnum confidence = ConfidenceEnum.fromCode(confidenceCode);

            if (intent == null || intent == IntentTypeEnum.UNKNOWN) {
                log.warn("意图识别结果无效，降级为 UNKNOWN: response={}", response);
                return fallbackResult("intent字段无效");
            }

            BaseSlot baseSlot = null;
            if (json.containsKey("baseSlot") && json.getJSONObject("baseSlot") != null) {
                baseSlot = json.getObject("baseSlot", BaseSlot.class);
            }

            Map<String, Object> intentSpecificSlots = null;
            if (json.containsKey("intentSpecificSlots") && json.getJSONObject("intentSpecificSlots") != null) {
                intentSpecificSlots = json.getJSONObject("intentSpecificSlots").getInnerMap();
            }

            if (intent == IntentTypeEnum.STOCK_ANALYSIS && intentSpecificSlots != null) {
                intentSpecificSlots = buildStockSlot(intentSpecificSlots);
            }

            return IntentRoutingResult.builder()
                    .intent(intent)
                    .confidence(confidence)
                    .reasoning(reasoning)
                    .baseSlot(baseSlot)
                    .intentSpecificSlots(intentSpecificSlots)
                    .build();
        } catch (Exception e) {
            log.warn("意图识别 JSON 解析失败，降级为 UNKNOWN + LOW: response={}, error={}",
                    response, e.getMessage());
            return fallbackResult("JSON解析失败: " + e.getMessage());
        }
    }

    public MultiIntentRoutingResult parseUnifiedResponse(String response) {
        if (response == null || response.isBlank()) {
            log.warn("统一路由 LLM 返回为空，降级为 GENERAL_CHAT");
            return fallbackMultiIntentResult("LLM返回为空");
        }

        try {
            String jsonStr = extractJson(response);
            JSONObject json = JSON.parseObject(jsonStr);

            Boolean multiTask = json.getBoolean("multiTask");
            Boolean needsClarification = json.getBoolean("needsClarification");
            String reasoning = defaultReasoning(json.getString("reasoning"));
            List<String> missingInfo = extractMissingInfo(json.getJSONArray("missingInfo"));
            String clarificationPrompt = defaultClarificationPrompt(json.getString("clarificationPrompt"), missingInfo);
            List<SubTask> taskList = extractTaskList(json.getJSONArray("taskList"));

            if (Boolean.TRUE.equals(needsClarification)) {
                return MultiIntentRoutingResult.builder()
                        .multiTask(Boolean.TRUE.equals(multiTask))
                        .needsClarification(true)
                        .missingInfo(missingInfo)
                        .clarificationPrompt(clarificationPrompt)
                        .reasoning(reasoning)
                        .taskList(taskList)
                        .build();
            }

            if (taskList.isEmpty()) {
                log.warn("统一路由 taskList 为空，降级为 GENERAL_CHAT: response={}", response);
                return fallbackMultiIntentResult("taskList为空");
            }

            return MultiIntentRoutingResult.builder()
                    .multiTask(Boolean.TRUE.equals(multiTask) && taskList.size() > 1)
                    .needsClarification(false)
                    .missingInfo(missingInfo)
                    .clarificationPrompt(clarificationPrompt)
                    .reasoning(reasoning)
                    .taskList(taskList)
                    .build();
        } catch (Exception e) {
            log.warn("统一路由 JSON 解析失败，降级为 GENERAL_CHAT: response={}, error={}",
                    response, e.getMessage());
            return fallbackMultiIntentResult("JSON解析失败: " + e.getMessage());
        }
    }

    private Map<String, Object> buildStockSlot(Map<String, Object> rawSlots) {
        StockSlot stockSlot = StockSlot.builder()
                .stockCode((String) rawSlots.get("stockCode"))
                .stockQueryType((String) rawSlots.get("stockQueryType"))
                .timeRange((String) rawSlots.get("timeRange"))
                .exchange((String) rawSlots.get("exchange"))
                .build();
        return Map.of("stockSlot", stockSlot);
    }

    private String defaultReasoning(String reasoning) {
        return reasoning == null || reasoning.isBlank() ? "无推理过程" : reasoning;
    }

    private String defaultClarificationPrompt(String clarificationPrompt, List<String> missingInfo) {
        if (clarificationPrompt != null && !clarificationPrompt.isBlank()) {
            return clarificationPrompt;
        }

        if (missingInfo == null || missingInfo.isEmpty()) {
            return "请补充必要信息";
        }

        return "请补充以下信息: " + String.join("、", missingInfo);
    }

    private List<String> extractMissingInfo(JSONArray missingInfoArray) {
        if (missingInfoArray == null) {
            return List.of();
        }
        return missingInfoArray.toJavaList(String.class);
    }

    private List<SubTask> extractTaskList(JSONArray taskArray) {
        if (taskArray == null || taskArray.isEmpty()) {
            return List.of();
        }

        List<SubTask> taskList = taskArray.toJavaList(SubTask.class);
        for (SubTask subTask : taskList) {
            normalizeTask(subTask);
        }
        return taskList;
    }

    private void normalizeTask(SubTask subTask) {
        if (subTask == null) {
            return;
        }

        if (subTask.getIntent() == null) {
            subTask.setIntent(IntentTypeEnum.GENERAL_CHAT);
        }

        if (subTask.getConfidence() == null) {
            subTask.setConfidence(ConfidenceEnum.LOW);
        }

        if (subTask.getTaskType() == null) {
            subTask.setTaskType(0);
        }

        if (subTask.getStatus() == null) {
            subTask.setStatus(SubTask.SubTaskStatus.PENDING);
        }

        if (subTask.getSlots() == null) {
            subTask.setSlots(Map.of());
            return;
        }

        if (subTask.getIntent() == IntentTypeEnum.STOCK_ANALYSIS) {
            Object intentSpecificSlotsObj = subTask.getSlots().get("intentSpecificSlots");
            if (intentSpecificSlotsObj instanceof Map<?, ?> rawIntentSpecificSlots) {
                Map<String, Object> normalizedSlots = buildStockSlot(convertToStringObjectMap(rawIntentSpecificSlots));
                subTask.getSlots().put("intentSpecificSlots", normalizedSlots);
            }
        }
    }

    private Map<String, Object> convertToStringObjectMap(Map<?, ?> source) {
        java.util.HashMap<String, Object> result = new java.util.HashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private String extractJson(String response) {
        int start = response.indexOf("{");
        int end = response.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    public IntentRoutingResult fallbackResult(String reason) {
        return IntentRoutingResult.builder()
                .intent(IntentTypeEnum.UNKNOWN)
                .confidence(ConfidenceEnum.LOW)
                .reasoning(reason)
                .build();
    }

    public MultiIntentRoutingResult fallbackMultiIntentResult(String reason) {
        return MultiIntentRoutingResult.builder()
                .multiTask(false)
                .needsClarification(false)
                .reasoning(reason)
                .missingInfo(List.of())
                .clarificationPrompt("")
                .taskList(List.of(
                        SubTask.builder()
                                .taskId("fallback-1")
                                .taskIndex(1)
                                .totalTasks(1)
                                .content("通用对话")
                                .intent(IntentTypeEnum.GENERAL_CHAT)
                                .executorNode("generalChatNode")
                                .confidence(ConfidenceEnum.MEDIUM)
                                .slots(Map.of())
                                .status(SubTask.SubTaskStatus.PENDING)
                                .taskType(0)
                                .build()
                ))
                .build();
    }
}
