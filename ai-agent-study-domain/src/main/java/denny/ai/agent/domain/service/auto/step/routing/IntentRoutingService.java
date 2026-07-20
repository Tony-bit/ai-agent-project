package denny.ai.agent.domain.service.auto.step.routing;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.entity.IntentFewshotSample;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.BaseSlot;
import denny.ai.agent.domain.model.valobj.DecomposedTask;
import denny.ai.agent.domain.model.valobj.IntentRoutingResult;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.QueryDecompositionResult;
import denny.ai.agent.domain.model.valobj.RoutingExecutionMetrics;
import denny.ai.agent.domain.model.valobj.RoutingStageMetric;
import denny.ai.agent.domain.model.valobj.StockSlot;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.armory.factory.element.ChatResponseValidator;
import denny.ai.agent.domain.service.armory.factory.element.ResponseValidationContext;
import denny.ai.agent.domain.service.armory.factory.element.ResponseValidationException;
import denny.ai.agent.domain.service.armory.factory.element.ResponseValidationFailureType;
import denny.ai.agent.domain.service.auto.step.AbstractExecuteSupport;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.chatmemory.ConversationContextAdvisor;
import denny.ai.agent.domain.service.intent.IntentFewshotService;
import denny.ai.agent.domain.util.TokenCountUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 意图识别服务
 *
 * @author denny
 * 2026/5/10
 */
@Slf4j
@Service
public class IntentRoutingService extends AbstractExecuteSupport {

    private static final Set<String> TRIVIAL_GENERAL_CHAT_INPUTS = Set.of(
            "\u4f60\u597d",
            "\u60a8\u597d",
            "\u54c8\u55bd",
            "hello",
            "hi",
            "hey",
            "thanks",
            "thankyou",
            "\u8c22\u8c22",
            "\u5728\u5417"
    );

    @Resource
    private IntentFewshotService intentFewshotService;

    @Resource
    private TaskGraphValidator taskGraphValidator = new TaskGraphValidator();

    @Resource
    private RoutingStructuredOutputValidator structuredOutputValidator = new RoutingStructuredOutputValidator(taskGraphValidator);

    public MultiIntentRoutingResult routeUnified(String userMessage,
                                                 List<String> historyMessages,
                                                 AiAgentClientFlowConfigVO configVO) {
        return routeUnified(userMessage, historyMessages, configVO, null, Map.of());
    }

    public MultiIntentRoutingResult routeUnified(String userMessage,
                                                 List<String> historyMessages,
                                                 AiAgentClientFlowConfigVO configVO,
                                                 Map<String, Object> observationContext) {
        return routeUnified(userMessage, historyMessages, configVO, null, observationContext);
    }

    public MultiIntentRoutingResult routeUnified(String userMessage,
                                                 List<String> historyMessages,
                                                 AiAgentClientFlowConfigVO configVO,
                                                 String conversationId) {
        return routeUnified(userMessage, historyMessages, configVO, conversationId, Map.of());
    }

    public MultiIntentRoutingResult routeUnified(String userMessage,
                                                 List<String> historyMessages,
                                                 AiAgentClientFlowConfigVO configVO,
                                                 String conversationId,
                                                 Map<String, Object> observationContext) {
        List<IntentFewshotSample> fewshotSamples = retrieveFewshotSamples(userMessage);
        String prompt = IntentRoutingPrompt.buildUnifiedRoutingPrompt(userMessage, historyMessages, fewshotSamples);
        long startedAt = System.currentTimeMillis();
        RoutingCallResult<MultiIntentRoutingResult> call = callRoutingModel(
                "unified-routing", null, 0, prompt, configVO,
                routingAdvisorContext(conversationId, ConversationContextAdvisor.SCENE_ROUTING, observationContext),
                structuredOutputValidator.unified(),
                this::parseUnifiedResponse,
                error -> fallbackMultiIntentResult(error));
        MultiIntentRoutingResult result = call.result();
        RoutingExecutionMetrics metrics = emptyMetrics(IntentRoutingMode.UNIFIED);
        metrics.addStage(call.metric());
        metrics.setTotalLatencyMs(System.currentTimeMillis() - startedAt);
        result.setMetrics(metrics);
        logMetrics(metrics);
        return result;
    }

    public QueryDecompositionResult decomposeQuery(String userMessage,
                                                   List<String> historyMessages,
                                                   AiAgentClientFlowConfigVO configVO) {
        return decomposeQueryWithMetric(userMessage, historyMessages, configVO).result();
    }

    RoutingCallResult<QueryDecompositionResult> decomposeQueryWithMetric(String userMessage,
                                                                         List<String> historyMessages,
                                                                         AiAgentClientFlowConfigVO configVO) {
        return decomposeQueryWithMetric(userMessage, historyMessages, configVO, null);
    }

    RoutingCallResult<QueryDecompositionResult> decomposeQueryWithMetric(String userMessage,
                                                                         List<String> historyMessages,
                                                                         AiAgentClientFlowConfigVO configVO,
                                                                         String conversationId) {
        String prompt = IntentRoutingPrompt.buildQueryDecompositionPrompt(userMessage, historyMessages);
        return callRoutingModel("query-decomposition", null, 0, prompt, configVO,
                routingAdvisorContext(conversationId, ConversationContextAdvisor.SCENE_DECOMPOSITION, Map.of()),
                structuredOutputValidator.queryDecomposition(),
                response -> parseQueryDecompositionResponse(response, userMessage),
                error -> fallbackDecomposition(userMessage, error));
    }

    public IntentRoutingResult routeTaskIntentSlots(String taskContent,
                                                    List<String> historyMessages,
                                                    AiAgentClientFlowConfigVO configVO) {
        return routeTaskIntentSlotsWithMetric(taskContent, null, 1, historyMessages, configVO).result();
    }

    RoutingCallResult<IntentRoutingResult> routeTaskIntentSlotsWithMetric(String taskContent,
                                                                          String taskId,
                                                                          int callIndex,
                                                                          List<String> historyMessages,
                                                                          AiAgentClientFlowConfigVO configVO) {
        return routeTaskIntentSlotsWithMetric(taskContent, taskId, callIndex, historyMessages, configVO, null);
    }

    RoutingCallResult<IntentRoutingResult> routeTaskIntentSlotsWithMetric(String taskContent,
                                                                          String taskId,
                                                                          int callIndex,
                                                                          List<String> historyMessages,
                                                                          AiAgentClientFlowConfigVO configVO,
                                                                          String conversationId) {
        List<IntentFewshotSample> fewshotSamples = retrieveFewshotSamples(taskContent);
        String prompt = IntentRoutingPrompt.buildTaskRoutingSlotPrompt(taskContent, historyMessages, fewshotSamples);
        return callRoutingModel("task-routing-slot", taskId, callIndex, prompt, configVO,
                routingAdvisorContext(conversationId, ConversationContextAdvisor.SCENE_SLOT, Map.of()),
                structuredOutputValidator.taskIntentRouting(),
                response -> {
                    IntentRoutingResult parsed = parseResponse(response);
                    return parsed.getIntent() == null || parsed.getIntent() == IntentTypeEnum.UNKNOWN
                            ? fallbackTaskRoutingResult(parsed.getReasoning()) : parsed;
                }, this::fallbackTaskRoutingResult);
    }

    public MultiIntentRoutingResult routeSplit(String userMessage,
                                               List<String> historyMessages,
                                               AiAgentClientFlowConfigVO configVO) {
        return routeSplit(userMessage, historyMessages, configVO, null);
    }

    public MultiIntentRoutingResult routeSplit(String userMessage,
                                               List<String> historyMessages,
                                               AiAgentClientFlowConfigVO configVO,
                                               String conversationId) {
        long startedAt = System.currentTimeMillis();
        RoutingExecutionMetrics metrics = emptyMetrics(IntentRoutingMode.SPLIT);
        RoutingCallResult<QueryDecompositionResult> decompositionCall =
                decomposeQueryWithMetric(userMessage, historyMessages, configVO, conversationId);
        metrics.addStage(decompositionCall.metric());
        QueryDecompositionResult decomposition = validateOrFallbackDecomposition(
                decompositionCall.result(), userMessage, decompositionCall.metric());

        List<SubTask> tasks = new ArrayList<>();
        List<DecomposedTask> orderedTasks = decomposition.getTaskList().stream()
                .sorted(Comparator.comparing(DecomposedTask::getTaskIndex))
                .toList();
        for (int i = 0; i < orderedTasks.size(); i++) {
            DecomposedTask task = orderedTasks.get(i);
            RoutingCallResult<IntentRoutingResult> routingCall = routeTaskIntentSlotsWithMetric(
                    task.getContent(), task.getTaskId(), i + 1, historyMessages, configVO, conversationId);
            metrics.addStage(routingCall.metric());
            tasks.add(toSubTask(task, routingCall.result()));
        }

        MultiIntentRoutingResult result = buildSplitResult(decomposition, tasks);
        try {
            taskGraphValidator.validateSubTasks(tasks);
        } catch (TaskGraphValidationException e) {
            result = fallbackMultiIntentResult("Task graph validation failed: " + e.getMessage());
        }
        metrics.setTotalLatencyMs(System.currentTimeMillis() - startedAt);
        result.setMetrics(metrics);
        logMetrics(metrics);
        return result;
    }

    Map<String, Object> routingAdvisorContext(String conversationId,
                                              String scene,
                                              Map<String, Object> observationContext) {
        Map<String, Object> context = new HashMap<>();
        if (observationContext != null) {
            context.putAll(observationContext);
        }
        context.put(ConversationContextAdvisor.CONVERSATION_CONTEXT_SCENE_KEY, scene);
        context.put(ConversationContextAdvisor.CONVERSATION_CONTEXT_PRELOADED_KEY, true);
        if (StringUtils.hasText(conversationId)) {
            context.put(ConversationContextAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId);
        }
        return context;
    }

    public QueryDecompositionResult parseQueryDecompositionResponse(String response, String userMessage) {
        try {
            QueryDecompositionOutput output = structuredOutputValidator.parseQueryDecomposition(response);
            List<DecomposedTask> tasks = output.getTaskList() == null ? List.of() : output.getTaskList().stream()
                    .map(task -> DecomposedTask.builder()
                            .taskId(task.getTaskId())
                            .taskIndex(task.getTaskIndex())
                            .totalTasks(task.getTotalTasks())
                            .content(task.getContent())
                            .dependsOn(task.getDependsOn() == null ? List.of() : task.getDependsOn())
                            .build())
                    .toList();
            if (tasks.isEmpty()) {
                return fallbackDecomposition(userMessage, "taskList is empty");
            }
            return QueryDecompositionResult.builder()
                    .multiTask(tasks.size() > 1)
                    .reasoning(defaultReasoning(output.getReasoning()))
                    .taskList(tasks)
                    .build();
        } catch (ResponseValidationException e) {
            return fallbackDecomposition(userMessage, legacyValidationMessage(e));
        }
    }

    public QueryDecompositionResult fallbackDecomposition(String userMessage, String reason) {
        return QueryDecompositionResult.builder()
                .multiTask(false)
                .reasoning(reason)
                .taskList(List.of(DecomposedTask.builder()
                        .taskId("fallback-1")
                        .taskIndex(1)
                        .totalTasks(1)
                        .content(userMessage)
                        .dependsOn(List.of())
                        .build()))
                .build();
    }

    public IntentRoutingResult fallbackTaskRoutingResult(String reason) {
        return IntentRoutingResult.builder()
                .intent(IntentTypeEnum.GENERAL_CHAT)
                .confidence(ConfidenceEnum.LOW)
                .reasoning(reason)
                .intentSpecificSlots(Map.of())
                .build();
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
        if (shouldSkipFewshotRetrieval(userMessage)) {
            log.debug("Few-Shot bypassed for trivial general chat input: userMessage={}", userMessage);
            return List.of();
        }
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
    private boolean shouldSkipFewshotRetrieval(String userMessage) {
        String normalized = normalizeForFewshotBypass(userMessage);
        if (!StringUtils.hasText(normalized)) {
            return true;
        }
        return TRIVIAL_GENERAL_CHAT_INPUTS.contains(normalized);
    }

    private String normalizeForFewshotBypass(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return "";
        }
        return userMessage
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
    }

    public IntentRoutingResult parseResponse(String response) {
        try {
            TaskIntentRoutingOutput output = structuredOutputValidator.parseTaskIntentRouting(response);
            IntentTypeEnum intent = IntentTypeEnum.fromCode(output.getIntent());
            ConfidenceEnum confidence = ConfidenceEnum.fromCode(output.getConfidence());
            Map<String, Object> intentSpecificSlots = output.getIntentSpecificSlots();
            if (intent == IntentTypeEnum.STOCK_ANALYSIS && intentSpecificSlots != null) {
                intentSpecificSlots = buildStockSlot(intentSpecificSlots);
            }
            return IntentRoutingResult.builder()
                    .intent(intent)
                    .confidence(confidence)
                    .reasoning(defaultReasoning(output.getReasoning()))
                    .baseSlot(output.getBaseSlot())
                    .intentSpecificSlots(intentSpecificSlots)
                    .build();
        } catch (ResponseValidationException e) {
            return fallbackResult(legacyValidationMessage(e));
        }
    }

    public MultiIntentRoutingResult parseUnifiedResponse(String response) {
        try {
            UnifiedRoutingOutput output = structuredOutputValidator.parseUnified(response);
            List<String> missingInfo = normalizeMissingInfo(output.getMissingInfo());
            String reasoning = defaultReasoning(output.getReasoning());
            List<SubTask> taskList = toSubTasks(output);

            if (Boolean.TRUE.equals(output.getNeedsClarification())) {
                return MultiIntentRoutingResult.builder()
                        .multiTask(Boolean.TRUE.equals(output.getMultiTask()))
                        .needsClarification(true)
                        .missingInfo(missingInfo)
                        .clarificationPrompt(defaultClarificationPrompt(output.getClarificationPrompt(), missingInfo))
                        .reasoning(reasoning)
                        .taskList(taskList)
                        .build();
            }
            if (taskList.isEmpty()) {
                return fallbackMultiIntentResult("taskList为空");
            }

            taskList.forEach(this::normalizeTask);

            return MultiIntentRoutingResult.builder()
                    .multiTask(Boolean.TRUE.equals(output.getMultiTask()) && taskList.size() > 1)
                    .needsClarification(false)
                    .missingInfo(missingInfo)
                    .clarificationPrompt(defaultClarificationPrompt(output.getClarificationPrompt(), missingInfo))
                    .reasoning(reasoning)
                    .taskList(taskList)
                    .build();
        } catch (ResponseValidationException e) {
            return fallbackMultiIntentResult(legacyValidationMessage(e));
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
        if (missingInfo != null && missingInfo.contains("analysisDepth")) {
            return "你需要快速了解，还是进行完整投资分析？";
        }
        if (clarificationPrompt != null && !clarificationPrompt.isBlank()) {
            return clarificationPrompt;
        }

        if (missingInfo == null || missingInfo.isEmpty()) {
            return "请补充必要信息";
        }

        return "请补充以下信息: " + String.join("、", missingInfo);
    }

    private List<String> normalizeMissingInfo(List<String> missingInfo) {
        if (missingInfo == null || missingInfo.isEmpty()) {
            return List.of();
        }
        return missingInfo.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(this::normalizeMissingInfoName)
                .distinct()
                .toList();
    }

    private String normalizeMissingInfoName(String name) {
        return switch (name) {
            case "queryTopic", "query_topic", "retrievalTopic", "retrieval_topic" -> "topic";
            case "stock_code", "ticker", "stockTicker" -> "stockCode";
            default -> name;
        };
    }

    private List<SubTask> toSubTasks(UnifiedRoutingOutput output) {
        if (output.getTaskList() == null || output.getTaskList().isEmpty()) {
            return List.of();
        }
        List<SubTask> tasks = new ArrayList<>();
        for (UnifiedRoutingOutput.TaskOutput task : output.getTaskList()) {
            tasks.add(SubTask.builder()
                    .taskId(task.getTaskId())
                    .taskIndex(task.getTaskIndex())
                    .totalTasks(task.getTotalTasks())
                    .content(task.getContent())
                    .intent(IntentTypeEnum.fromCode(task.getIntent()))
                    .confidence(ConfidenceEnum.fromCode(task.getConfidence()))
                    .slots(task.getSlots() == null ? new HashMap<>() : new HashMap<>(task.getSlots()))
                    .dependsOn(task.getDependsOn() == null ? List.of() : task.getDependsOn())
                    .build());
        }
        return tasks;
    }

    private void normalizeTask(SubTask subTask) {
        if (subTask == null) {
            return;
        }

        if (subTask.getIntent() == null || subTask.getIntent() == IntentTypeEnum.UNKNOWN) {
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

        subTask.setExecutorNode(resolveExecutorNode(subTask.getIntent()));

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

    private QueryDecompositionResult validateOrFallbackDecomposition(QueryDecompositionResult result,
                                                                     String userMessage,
                                                                     RoutingStageMetric metric) {
        try {
            taskGraphValidator.validateDecomposedTasks(result.getTaskList());
            result.setMultiTask(result.getTaskList().size() > 1);
            return result;
        } catch (TaskGraphValidationException e) {
            metric.setSuccess(false);
            metric.setErrorMessage(e.getMessage());
            return fallbackDecomposition(userMessage, "Task graph validation failed: " + e.getMessage());
        }
    }

    SubTask toSubTask(DecomposedTask task, IntentRoutingResult routing) {
        Map<String, Object> slots = new HashMap<>();
        if (routing.getBaseSlot() != null) {
            slots.put("baseSlot", routing.getBaseSlot());
        }
        slots.put("intentSpecificSlots", routing.getIntentSpecificSlots() == null
                ? Map.of() : routing.getIntentSpecificSlots());
        IntentTypeEnum intent = routing.getIntent() == null ? IntentTypeEnum.GENERAL_CHAT : routing.getIntent();
        return SubTask.builder()
                .taskId(task.getTaskId())
                .taskIndex(task.getTaskIndex())
                .totalTasks(task.getTotalTasks())
                .content(task.getContent())
                .dependsOn(task.getDependsOn() == null ? List.of() : task.getDependsOn())
                .intent(intent)
                .executorNode(resolveExecutorNode(intent))
                .confidence(routing.getConfidence() == null ? ConfidenceEnum.LOW : routing.getConfidence())
                .slots(slots)
                .status(SubTask.SubTaskStatus.PENDING)
                .taskType(0)
                .build();
    }

    MultiIntentRoutingResult buildSplitResult(QueryDecompositionResult decomposition, List<SubTask> tasks) {
        return MultiIntentRoutingResult.builder()
                .multiTask(tasks.size() > 1)
                .needsClarification(false)
                .missingInfo(List.of())
                .clarificationPrompt("")
                .reasoning(decomposition.getReasoning())
                .taskList(tasks)
                .build();
    }

    private RoutingExecutionMetrics emptyMetrics(IntentRoutingMode mode) {
        return RoutingExecutionMetrics.builder()
                .mode(mode)
                .totalLatencyMs(0L)
                .totalPromptTokens(0)
                .totalCompletionTokens(0)
                .totalTokens(0)
                .estimated(false)
                .stageMetrics(new ArrayList<>())
                .build();
    }

    private <T> RoutingCallResult<T> callRoutingModel(String stageName,
                                                      String taskId,
                                                      int callIndex,
                                                      String prompt,
                                                      AiAgentClientFlowConfigVO configVO,
                                                      Map<String, Object> observationContext,
                                                      ChatResponseValidator validator,
                                                      Function<String, T> parser,
                                                      Function<String, T> fallback) {
        long startedAt = System.currentTimeMillis();
        String content = "";
        ChatResponse response = null;
        try {
            ChatClient chatClient = getChatClientByClientId(configVO.getClientId(), 0);
            Map<String, Object> advisorContext = new HashMap<>();
            if (observationContext != null) {
                advisorContext.putAll(observationContext);
            }
            advisorContext.putIfAbsent("client_id", configVO.getClientId());
            response = ResponseValidationContext.withValidator(validator,
                    () -> chatClient.prompt(prompt)
                            .options(jsonObjectOptions())
                            .advisors(advisor -> advisorContext.forEach(advisor::param))
                            .call()
                            .chatResponse());
            if (validator != null) {
                validator.validate(response);
            }
            content = response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                    ? null : response.getResult().getOutput().getText();
            RoutingStageMetric metric = buildMetric(stageName, taskId, callIndex, configVO.getClientId(),
                    startedAt, prompt, content, response, true, null, null);
            metric.setJsonModeEnabled(true);
            metric.setSchemaValidationEnabled(validator != null);
            if (!StringUtils.hasText(content)) {
                metric.setSuccess(false);
                metric.setErrorMessage("LLM returned an empty response");
                metric.setFinalFailureType(ResponseValidationFailureType.EMPTY_RESPONSE.name());
                return new RoutingCallResult<>(fallback.apply(metric.getErrorMessage()), metric);
            }
            try {
                return new RoutingCallResult<>(parser.apply(content), metric);
            } catch (ResponseValidationException e) {
                metric.setSuccess(false);
                metric.setErrorMessage(e.getMessage());
                metric.setFinalFailureType(e.getFailureType().name());
                return new RoutingCallResult<>(fallback.apply(e.getMessage()), metric);
            }
        } catch (Exception e) {
            ResponseValidationFailureType failureType = failureTypeOf(e);
            RoutingStageMetric metric = buildMetric(stageName, taskId, callIndex, configVO.getClientId(),
                    startedAt, prompt, content, response, false, e.getMessage(), failureType);
            metric.setJsonModeEnabled(true);
            metric.setSchemaValidationEnabled(validator != null);
            log.error("Routing model call failed: stage={}, taskId={}, error={}", stageName, taskId, e.getMessage(), e);
            return new RoutingCallResult<>(fallback.apply("LLM调用异常: " + e.getMessage()), metric);
        }
    }

    private RoutingStageMetric buildMetric(String stageName,
                                           String taskId,
                                           int callIndex,
                                           String clientId,
                                           long startedAt,
                                           String prompt,
                                           String content,
                                           ChatResponse response,
                                           boolean success,
                                           String errorMessage,
                                           ResponseValidationFailureType failureType) {
        Integer promptTokens = null;
        Integer completionTokens = null;
        Integer totalTokens = null;
        if (response != null && response.getMetadata() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                promptTokens = usage.getPromptTokens();
                completionTokens = usage.getCompletionTokens();
                totalTokens = usage.getTotalTokens();
            }
        }
        boolean estimated = promptTokens == null || completionTokens == null;
        if (promptTokens == null) {
            promptTokens = TokenCountUtils.estimate(prompt);
        }
        if (completionTokens == null) {
            completionTokens = TokenCountUtils.estimate(content);
        }
        if (totalTokens == null) {
            totalTokens = promptTokens + completionTokens;
        }
        return RoutingStageMetric.builder()
                .stageName(stageName)
                .clientId(clientId)
                .taskId(taskId)
                .callIndex(callIndex)
                .latencyMs(System.currentTimeMillis() - startedAt)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .estimatedTokens(estimated)
                .success(success)
                .errorMessage(errorMessage)
                .finalFailureType(failureType == null ? null : failureType.name())
                .jsonModeEnabled(true)
                .schemaValidationEnabled(true)
                .build();
    }

    private ResponseValidationFailureType failureTypeOf(Exception e) {
        if (e instanceof ResponseValidationException validationException) {
            return validationException.getFailureType();
        }
        return ResponseValidationFailureType.INFRA_ERROR;
    }

    private String legacyValidationMessage(ResponseValidationException e) {
        if (e.getFailureType() == ResponseValidationFailureType.EMPTY_RESPONSE) {
            return "LLM返回为空";
        }
        if (e.getFailureType() == ResponseValidationFailureType.JSON_PARSE_ERROR) {
            return "JSON解析失败: " + e.getMessage();
        }
        return e.getMessage();
    }

    private OpenAiChatOptions jsonObjectOptions() {
        return OpenAiChatOptions.builder()
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_OBJECT)
                        .build())
                .build();
    }

    private void logMetrics(RoutingExecutionMetrics metrics) {
        log.info("Intent routing metrics: mode={}, totalLatencyMs={}, totalTokens={}, stages={}",
                metrics.getMode(), metrics.getTotalLatencyMs(), metrics.getTotalTokens(), metrics.getStageMetrics());
    }

    record RoutingCallResult<T>(T result, RoutingStageMetric metric) {
    }

    private String resolveExecutorNode(IntentTypeEnum intent) {
        if (intent == null) {
            return "generalChatNode";
        }

        return switch (intent) {
            case STOCK_ANALYSIS -> "tradingStarter";
            case PE_REASONING, PE_CALCULATION, PE_RETRIEVAL -> "step1AnalyzerNode";
            case INSPECTION -> "intelligentInspection";
            default -> "generalChatNode";
        };
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
