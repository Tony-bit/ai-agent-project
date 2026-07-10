package denny.ai.agent.test.eval.routing;

import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.MultiIntentRoutingResult;
import denny.ai.agent.domain.model.valobj.RoutingExecutionMetrics;
import denny.ai.agent.domain.model.valobj.RoutingStageMetric;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.service.auto.step.routing.IntentRoutingService;
import denny.ai.agent.domain.service.observability.ObservabilityService;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class IntentRoutingOnlineEvaluator {

    private static final String INFRA_PREFIX = "LLM\u8c03\u7528\u5f02\u5e38";
    private static final List<String> FORMAT_PREFIXES = List.of(
            "LLM\u8fd4\u56de\u4e3a\u7a7a",
            "JSON\u89e3\u6790\u5931\u8d25",
            "taskList\u4e3a\u7a7a"
    );

    private final IntentRoutingService routingService;
    private final ObservabilityService observabilityService;
    private final String clientId;
    private final EvalRoutingMode routingMode;
    private final Integer runsOverride;

    public IntentRoutingOnlineEvaluator(IntentRoutingService routingService, String clientId) {
        this(routingService, null, clientId, EvalRoutingMode.UNIFIED);
    }

    public IntentRoutingOnlineEvaluator(IntentRoutingService routingService,
                                        ObservabilityService observabilityService,
                                        String clientId) {
        this(routingService, observabilityService, clientId, EvalRoutingMode.UNIFIED);
    }

    public IntentRoutingOnlineEvaluator(IntentRoutingService routingService,
                                        ObservabilityService observabilityService,
                                        String clientId,
                                        EvalRoutingMode routingMode) {
        this(routingService, observabilityService, clientId, routingMode, null);
    }

    public IntentRoutingOnlineEvaluator(IntentRoutingService routingService,
                                        ObservabilityService observabilityService,
                                        String clientId,
                                        EvalRoutingMode routingMode,
                                        Integer runsOverride) {
        this.routingService = Objects.requireNonNull(routingService, "routingService");
        this.observabilityService = observabilityService;
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.routingMode = Objects.requireNonNull(routingMode, "routingMode");
        if (runsOverride != null && runsOverride < 1) {
            throw new IllegalArgumentException("runsOverride must be >= 1");
        }
        this.runsOverride = runsOverride;
    }

    public EvalReport evaluate(List<IntentRoutingOnlineEvalCase> cases, String suite, String tag) {
        EvalReport report = new EvalReport();
        report.setEvalRunId(newEvalRunId());
        report.setStartedAt(Instant.now().toString());
        report.setClientId(clientId);
        report.setRoutingMode(routingMode.name());
        report.setSuite(suite);
        report.setTag(tag);
        report.setBatchTraceId(startBatchTrace(report, cases.size()));

        for (IntentRoutingOnlineEvalCase c : cases) {
            report.getCases().add(evaluateCase(c, report.getEvalRunId()));
        }

        report.setFinishedAt(Instant.now().toString());
        report.setMetrics(calculateMetrics(report.getCases()));
        completeBatchTrace(report);
        return report;
    }

    private CaseResult evaluateCase(IntentRoutingOnlineEvalCase c, String evalRunId) {
        CaseResult caseResult = new CaseResult();
        caseResult.setCaseId(c.getCaseId());
        caseResult.setSuite(c.getSuite());
        caseResult.setCategory(c.getCategory());
        caseResult.setDescription(c.getDescription());
        caseResult.setQuery(c.getInput().getQuery());
        caseResult.setExpectedIntents(safeList(c.getExpected().getTaskIntents()));
        caseResult.setMinPassRate(c.getEvaluation().getMinPassRate());
        caseResult.setMinConsistencyRate(c.getEvaluation().getMinConsistencyRate());

        AiAgentClientFlowConfigVO config = AiAgentClientFlowConfigVO.builder()
                .clientId(clientId)
                .clientType(AiClientTypeEnumVO.INTENT_ROUTING.getCode())
                .build();

        int runs = runsOverride == null ? c.getEvaluation().getRuns() : runsOverride;
        for (int i = 1; i <= runs; i++) {
            String traceId = startRunTrace(evalRunId, c, i);
            long startNanos = System.nanoTime();
            MultiIntentRoutingResult result = route(c, evalRunId, i, traceId, config);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            RunResult run = toRunResult(i, latencyMs, c, result);
            run.setTraceId(traceId);
            caseResult.getRuns().add(run);
            completeRunTrace(evalRunId, c, run);
        }

        summarizeCase(caseResult);
        publishCaseScores(evalRunId, caseResult);
        return caseResult;
    }

    private MultiIntentRoutingResult route(IntentRoutingOnlineEvalCase c,
                                           String evalRunId,
                                           int runIndex,
                                           String traceId,
                                           AiAgentClientFlowConfigVO config) {
        List<String> historyMessages = safeList(c.getInput().getHistoryMessages());
        if (routingMode == EvalRoutingMode.SPLIT) {
            return routingService.routeSplit(c.getInput().getQuery(), historyMessages, config);
        }
        if (observabilityService == null) {
            return routingService.routeUnified(c.getInput().getQuery(), historyMessages, config);
        }
        return routingService.routeUnified(
                c.getInput().getQuery(),
                historyMessages,
                config,
                observationContext(evalRunId, c, runIndex, traceId));
    }

    private String newEvalRunId() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
        return timestamp + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String startBatchTrace(EvalReport report, int caseCount) {
        if (observabilityService == null) {
            return "";
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("traceName", "intent-routing-eval-batch");
        metadata.put("scene", "intent_routing_online_eval_batch");
        metadata.put("evalRunId", report.getEvalRunId());
        metadata.put("clientId", clientId);
        metadata.put("routingMode", routingMode.name());
        metadata.put("caseCount", caseCount);
        putIfText(metadata, "suite", report.getSuite());
        putIfText(metadata, "tag", report.getTag());
        return observabilityService.startTrace(
                report.getEvalRunId(), "Intent routing online evaluation", metadata);
    }

    private String startRunTrace(String evalRunId, IntentRoutingOnlineEvalCase c, int runIndex) {
        if (observabilityService == null) {
            return "";
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("traceName", "intent-routing-eval-run/" + c.getCaseId() + "/" + runIndex);
        metadata.put("scene", "intent_routing_online_eval_run");
        metadata.put("evalRunId", evalRunId);
        metadata.put("caseId", c.getCaseId());
        metadata.put("runIndex", runIndex);
        metadata.put("suite", c.getSuite());
        metadata.put("category", c.getCategory());
        metadata.put("expectedIntents", safeList(c.getExpected().getTaskIntents()));
        metadata.put("clientId", clientId);
        metadata.put("routingMode", routingMode.name());
        return observabilityService.startTrace(evalRunId, c.getInput().getQuery(), metadata);
    }

    private Map<String, Object> observationContext(String evalRunId,
                                                   IntentRoutingOnlineEvalCase c,
                                                   int runIndex,
                                                   String traceId) {
        Map<String, Object> context = new HashMap<>();
        context.put("trace_id", traceId);
        context.put("chat_memory_conversation_id", memoryConversationId(evalRunId, c.getCaseId(), runIndex));
        context.put("client_id", clientId);
        context.put("eval_run_id", evalRunId);
        context.put("eval_case_id", c.getCaseId());
        context.put("eval_run_index", runIndex);
        return context;
    }

    private String memoryConversationId(String evalRunId, String caseId, int runIndex) {
        return evalRunId + "/" + caseId + "/" + runIndex;
    }

    private void completeRunTrace(String evalRunId,
                                  IntentRoutingOnlineEvalCase c,
                                  RunResult run) {
        if (observabilityService == null || run.getTraceId().isBlank()) {
            return;
        }
        Map<String, Object> metadata = runMetadata(evalRunId, c.getCaseId(), run);
        metadata.put("suite", c.getSuite());
        metadata.put("category", c.getCategory());
        metadata.put("expectedIntents", safeList(c.getExpected().getTaskIntents()));
        observabilityService.logScore(run.getTraceId(), "routing_correct", run.isPassed() ? 1.0 : 0.0,
                "Intent routing exact match", metadata);
        observabilityService.endTrace(run.getTraceId(), run.getSignature(), metadata);
    }

    private void publishCaseScores(String evalRunId, CaseResult caseResult) {
        if (observabilityService == null) {
            return;
        }
        RunResult representativeRun = caseResult.getRuns().stream()
                .filter(run -> run.getTraceId() != null && !run.getTraceId().isBlank())
                .findFirst()
                .orElse(null);
        if (representativeRun != null) {
            Map<String, Object> metadata = runMetadata(evalRunId, caseResult.getCaseId(), representativeRun);
            metadata.put("expectedIntents", caseResult.getExpectedIntents());
            observabilityService.logScore(representativeRun.getTraceId(), "case_pass_rate", caseResult.getPassRate(),
                    "Pass rate across repeated runs", metadata);
            observabilityService.logScore(representativeRun.getTraceId(), "consistency_rate", caseResult.getConsistencyRate(),
                    "Majority signature consistency", metadata);
            observabilityService.logScore(representativeRun.getTraceId(), "case_passed", caseResult.isPassed() ? 1.0 : 0.0,
                    "Case threshold result", metadata);
        }
    }

    private void completeBatchTrace(EvalReport report) {
        if (observabilityService == null || report.getBatchTraceId().isBlank()) {
            return;
        }
        GlobalMetrics metrics = report.getMetrics();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("scene", "intent_routing_online_eval_batch");
        metadata.put("evalRunId", report.getEvalRunId());
        metadata.put("failedCaseIds", report.failedCaseIds());
        metadata.put("routingMode", routingMode.name());
        observabilityService.logScore(report.getBatchTraceId(), "case_pass_rate", metrics.getCasePassRate(),
                "Global case pass rate", metadata);
        observabilityService.logScore(report.getBatchTraceId(), "run_accuracy", metrics.getRunAccuracy(),
                "Global run accuracy", metadata);
        observabilityService.logScore(report.getBatchTraceId(), "format_error_rate", metrics.getFormatErrorRate(),
                "Global format error rate", metadata);
        observabilityService.logScore(report.getBatchTraceId(), "infrastructure_error_rate",
                metrics.getInfrastructureErrorRate(), "Global infrastructure error rate", metadata);
        String summary = "casePassRate=" + metrics.getCasePassRate()
                + ", runAccuracy=" + metrics.getRunAccuracy()
                + ", failedCases=" + report.failedCaseIds();
        observabilityService.endTrace(report.getBatchTraceId(), summary, metadata);
    }

    private Map<String, Object> runMetadata(String evalRunId, String caseId, RunResult run) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("evalRunId", evalRunId);
        metadata.put("caseId", caseId);
        metadata.put("runIndex", run.getRunIndex());
        metadata.put("outcomeType", run.getOutcomeType());
        metadata.put("signature", run.getSignature());
        metadata.put("actualIntents", run.getActualIntents());
        metadata.put("routingConfidences", run.getRoutingConfidences());
        metadata.put("routingMinConfidence", run.getRoutingMinConfidence());
        metadata.put("routingHasLowConfidence", run.getRoutingHasLowConfidence());
        metadata.put("latencyMs", run.getLatencyMs());
        metadata.put("routingMode", run.getRoutingMode());
        metadata.put("totalTokens", run.getTotalTokens());
        metadata.put("stageCount", run.getStageCount());
        metadata.put("finalFailureType", run.getFinalFailureType());
        metadata.put("jsonModeEnabled", run.getJsonModeEnabled());
        metadata.put("schemaValidationEnabled", run.getSchemaValidationEnabled());
        return metadata;
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private RunResult toRunResult(int runIndex,
                                  long latencyMs,
                                  IntentRoutingOnlineEvalCase c,
                                  MultiIntentRoutingResult result) {
        RunResult run = new RunResult();
        run.setRunIndex(runIndex);
        run.setLatencyMs(latencyMs);
        run.setRoutingMode(routingMode.name());

        if (result == null) {
            run.setOutcomeType(OutcomeType.FORMAT_ERROR.name());
            run.setSignature("FORMAT_ERROR|NULL_RESULT");
            run.setReasoning(routingMode.name() + " routing returned null");
            return run;
        }

        attachRoutingMetrics(run, result.getMetrics());
        run.setReasoning(result.getReasoning());
        run.setActualMultiTask(Boolean.TRUE.equals(result.getMultiTask()));
        run.setActualNeedsClarification(Boolean.TRUE.equals(result.getNeedsClarification()));
        run.setMissingInfo(safeList(result.getMissingInfo()));
        run.setActualIntents(extractIntents(result));
        run.setRoutingConfidences(extractConfidenceCodes(result));
        run.setRoutingMinConfidence(minConfidence(run.getRoutingConfidences()));
        run.setRoutingHasLowConfidence(run.getRoutingConfidences().contains(ConfidenceEnum.LOW.getCode()));

        OutcomeType outcomeType = classifyOutcome(run);
        run.setOutcomeType(outcomeType.name());
        run.setSignature(buildSignature(outcomeType, run));
        run.setPassed(outcomeType == OutcomeType.ROUTE && matchesExpected(c, run));
        return run;
    }

    private void attachRoutingMetrics(RunResult run, RoutingExecutionMetrics metrics) {
        if (metrics == null) {
            return;
        }
        run.setRoutingLatencyMs(metrics.getTotalLatencyMs());
        run.setTotalPromptTokens(metrics.getTotalPromptTokens());
        run.setTotalCompletionTokens(metrics.getTotalCompletionTokens());
        run.setTotalTokens(metrics.getTotalTokens());
        run.setEstimated(metrics.getEstimated());
        run.setStageMetrics(safeList(metrics.getStageMetrics()));
        run.setStageCount(run.getStageMetrics().size());
        run.setFinalFailureType(firstFailureType(run.getStageMetrics()));
        run.setJsonModeEnabled(run.getStageMetrics().stream()
                .anyMatch(stage -> Boolean.TRUE.equals(stage.getJsonModeEnabled())));
        run.setSchemaValidationEnabled(run.getStageMetrics().stream()
                .anyMatch(stage -> Boolean.TRUE.equals(stage.getSchemaValidationEnabled())));
    }

    private String firstFailureType(List<RoutingStageMetric> stages) {
        return stages.stream()
                .map(RoutingStageMetric::getFinalFailureType)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private OutcomeType classifyOutcome(RunResult run) {
        String failureType = run.getFinalFailureType();
        if ("INFRA_ERROR".equals(failureType)) {
            return OutcomeType.INFRA_ERROR;
        }
        if (failureType != null && !failureType.isBlank()) {
            return OutcomeType.FORMAT_ERROR;
        }
        String reasoning = run.getReasoning();
        if (reasoning != null && reasoning.startsWith(INFRA_PREFIX)) {
            return OutcomeType.INFRA_ERROR;
        }
        if (reasoning != null && FORMAT_PREFIXES.stream().anyMatch(reasoning::startsWith)) {
            return OutcomeType.FORMAT_ERROR;
        }
        return OutcomeType.ROUTE;
    }

    private String buildSignature(OutcomeType outcomeType, RunResult run) {
        if (outcomeType == OutcomeType.INFRA_ERROR) {
            return "INFRA_ERROR|LLM_CALL";
        }
        if (outcomeType == OutcomeType.FORMAT_ERROR) {
            if (run.getFinalFailureType() != null && !run.getFinalFailureType().isBlank()) {
                return "FORMAT_ERROR|" + run.getFinalFailureType();
            }
            if (run.getReasoning() != null && run.getReasoning().startsWith("LLM\u8fd4\u56de\u4e3a\u7a7a")) {
                return "FORMAT_ERROR|EMPTY_RESPONSE";
            }
            if (run.getReasoning() != null && run.getReasoning().startsWith("taskList\u4e3a\u7a7a")) {
                return "FORMAT_ERROR|EMPTY_TASK_LIST";
            }
            return "FORMAT_ERROR|JSON_PARSE";
        }
        if (run.isActualNeedsClarification()) {
            List<String> missing = new ArrayList<>(run.getMissingInfo());
            Collections.sort(missing);
            return "CLARIFICATION|" + String.join(",", missing);
        }
        return "ROUTE|" + (run.isActualMultiTask() ? "multi" : "single") + "|"
                + String.join(",", run.getActualIntents());
    }

    private boolean matchesExpected(IntentRoutingOnlineEvalCase c, RunResult run) {
        IntentRoutingOnlineEvalCase.Expected expected = c.getExpected();
        if (run.isActualNeedsClarification() != Boolean.TRUE.equals(expected.getNeedsClarification())) {
            return false;
        }
        if (run.isActualMultiTask() != Boolean.TRUE.equals(expected.getMultiTask())) {
            return false;
        }
        if (Boolean.TRUE.equals(expected.getNeedsClarification())) {
            List<String> missing = run.getMissingInfo();
            if (Boolean.TRUE.equals(expected.getMissingInfoNotEmpty()) && missing.isEmpty()) {
                return false;
            }
            return missing.containsAll(safeList(expected.getMissingInfoContains()));
        }

        List<String> actual = run.getActualIntents();
        if (intentListsMatch(expected.getTaskIntents(), actual, expected.getOrderSensitive())) {
            return true;
        }
        for (List<String> acceptable : normalizeAcceptable(expected.getAcceptableTaskIntents())) {
            if (intentListsMatch(acceptable, actual, expected.getOrderSensitive())) {
                return true;
            }
        }
        return false;
    }

    private boolean intentListsMatch(List<String> expected, List<String> actual, Boolean orderSensitive) {
        List<String> safeExpected = safeList(expected);
        List<String> safeActual = safeList(actual);
        if (safeExpected.size() != safeActual.size()) {
            return false;
        }
        if (!Boolean.FALSE.equals(orderSensitive)) {
            return safeExpected.equals(safeActual);
        }
        return frequencies(safeExpected).equals(frequencies(safeActual));
    }

    private List<List<String>> normalizeAcceptable(List<Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<List<String>> alternatives = new ArrayList<>();
        for (Object value : raw) {
            if (value instanceof String stringValue) {
                alternatives.add(List.of(stringValue));
            } else if (value instanceof List<?> listValue) {
                alternatives.add(listValue.stream().map(String::valueOf).toList());
            }
        }
        return alternatives;
    }

    private Map<String, Long> frequencies(List<String> values) {
        return values.stream().collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
    }

    private List<String> extractIntents(MultiIntentRoutingResult result) {
        if (result.getTaskList() == null) {
            return List.of();
        }
        return result.getTaskList().stream()
                .map(SubTask::getIntent)
                .map(intent -> intent == null ? "UNKNOWN" : intent.getCode())
                .toList();
    }

    private List<String> extractConfidenceCodes(MultiIntentRoutingResult result) {
        if (result.getTaskList() == null) {
            return List.of();
        }
        return result.getTaskList().stream()
                .map(SubTask::getConfidence)
                .map(confidence -> confidence == null ? ConfidenceEnum.LOW : confidence)
                .map(ConfidenceEnum::getCode)
                .toList();
    }

    private String minConfidence(List<String> confidences) {
        ConfidenceEnum min = null;
        for (String code : confidences) {
            ConfidenceEnum confidence = ConfidenceEnum.fromCode(code);
            if (min == null || confidenceRank(confidence) < confidenceRank(min)) {
                min = confidence;
            }
        }
        return min == null ? null : min.getCode();
    }

    private int confidenceRank(ConfidenceEnum confidence) {
        return switch (confidence) {
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
        };
    }

    private void summarizeCase(CaseResult result) {
        int infraErrors = (int) result.getRuns().stream().filter(r -> OutcomeType.INFRA_ERROR.name().equals(r.getOutcomeType())).count();
        int formatErrors = (int) result.getRuns().stream().filter(r -> OutcomeType.FORMAT_ERROR.name().equals(r.getOutcomeType())).count();
        int effectiveRuns = result.getRuns().size() - infraErrors;
        int passedRuns = (int) result.getRuns().stream().filter(RunResult::isPassed).count();

        result.setInfrastructureErrorCount(infraErrors);
        result.setFormatErrorCount(formatErrors);
        result.setEffectiveRunCount(effectiveRuns);
        result.setPassedRunCount(passedRuns);
        result.setPassRate(rate(passedRuns, effectiveRuns));

        Map<String, Long> signatureCounts = result.getRuns().stream()
                .filter(r -> !OutcomeType.INFRA_ERROR.name().equals(r.getOutcomeType()))
                .collect(Collectors.groupingBy(RunResult::getSignature, LinkedHashMap::new, Collectors.counting()));
        long majority = signatureCounts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        result.setConsistencyRate(rate(majority, effectiveRuns));
        result.setPassed(infraErrors == 0
                && result.getPassRate() >= result.getMinPassRate()
                && result.getConsistencyRate() >= result.getMinConsistencyRate());
    }

    private GlobalMetrics calculateMetrics(List<CaseResult> cases) {
        GlobalMetrics metrics = new GlobalMetrics();
        metrics.setCaseCount(cases.size());
        metrics.setPassedCaseCount((int) cases.stream().filter(CaseResult::isPassed).count());
        metrics.setCasePassRate(rate(metrics.getPassedCaseCount(), metrics.getCaseCount()));

        List<RunResult> allRuns = cases.stream().flatMap(c -> c.getRuns().stream()).toList();
        metrics.setRunCount(allRuns.size());
        metrics.setInfrastructureErrorCount((int) allRuns.stream()
                .filter(r -> OutcomeType.INFRA_ERROR.name().equals(r.getOutcomeType())).count());
        metrics.setFormatErrorCount((int) allRuns.stream()
                .filter(r -> OutcomeType.FORMAT_ERROR.name().equals(r.getOutcomeType())).count());
        metrics.setEffectiveRunCount(metrics.getRunCount() - metrics.getInfrastructureErrorCount());
        metrics.setPassedRunCount((int) allRuns.stream().filter(RunResult::isPassed).count());
        metrics.setRunAccuracy(rate(metrics.getPassedRunCount(), metrics.getEffectiveRunCount()));
        metrics.setFormatErrorRate(rate(metrics.getFormatErrorCount(), metrics.getRunCount()));
        metrics.setInfrastructureErrorRate(rate(metrics.getInfrastructureErrorCount(), metrics.getRunCount()));

        populateCategoryMetrics(cases, metrics);
        populatePerIntentAccuracy(cases, metrics);
        populateConfusionMatrix(cases, metrics);
        populateRuntimeMetrics(allRuns, metrics);
        return metrics;
    }

    private void populateRuntimeMetrics(List<RunResult> runs, GlobalMetrics metrics) {
        metrics.setAvgLatencyMs(averageLong(runs.stream().map(RunResult::getLatencyMs).toList()));
        metrics.setP95LatencyMs(percentile95(runs.stream().map(RunResult::getLatencyMs).toList()));
        metrics.setAvgRoutingLatencyMs(averageLong(runs.stream()
                .map(RunResult::getRoutingLatencyMs)
                .filter(Objects::nonNull)
                .toList()));
        metrics.setP95RoutingLatencyMs(percentile95(runs.stream()
                .map(RunResult::getRoutingLatencyMs)
                .filter(Objects::nonNull)
                .toList()));
        metrics.setAvgTokens(averageInteger(runs.stream().map(RunResult::getTotalTokens).toList()));
        metrics.setAvgPromptTokens(averageInteger(runs.stream().map(RunResult::getTotalPromptTokens).toList()));
        metrics.setAvgCompletionTokens(averageInteger(runs.stream().map(RunResult::getTotalCompletionTokens).toList()));
        metrics.setAvgStageCount(averageInteger(runs.stream().map(RunResult::getStageCount).toList()));

        List<RunResult> runsWithMetrics = runs.stream()
                .filter(run -> run.getStageCount() != null)
                .toList();
        long estimatedRuns = runsWithMetrics.stream()
                .filter(run -> Boolean.TRUE.equals(run.getEstimated()))
                .count();
        metrics.setEstimatedTokenRate(rate(estimatedRuns, runsWithMetrics.size()));

        List<RoutingStageMetric> stages = runs.stream()
                .flatMap(run -> safeList(run.getStageMetrics()).stream())
                .toList();
        long successfulStages = stages.stream()
                .filter(stage -> Boolean.TRUE.equals(stage.getSuccess()))
                .count();
        metrics.setStageSuccessRate(rate(successfulStages, stages.size()));
    }

    private void populateCategoryMetrics(List<CaseResult> cases, GlobalMetrics metrics) {
        List<RunResult> clarificationRuns = runsForCategory(cases, "clarification");
        List<RunResult> multiTaskRuns = runsForCategory(cases, "multi-task");
        metrics.setClarificationAccuracy(accuracyExcludingInfra(clarificationRuns));
        metrics.setMultiTaskExactMatch(accuracyExcludingInfra(multiTaskRuns));
    }

    private List<RunResult> runsForCategory(List<CaseResult> cases, String category) {
        return cases.stream()
                .filter(c -> category.equals(c.getCategory()))
                .flatMap(c -> c.getRuns().stream())
                .toList();
    }

    private double accuracyExcludingInfra(List<RunResult> runs) {
        long effective = runs.stream().filter(r -> !OutcomeType.INFRA_ERROR.name().equals(r.getOutcomeType())).count();
        long passed = runs.stream().filter(RunResult::isPassed).count();
        return rate(passed, effective);
    }

    private void populatePerIntentAccuracy(List<CaseResult> cases, GlobalMetrics metrics) {
        Map<String, AccuracyMetric> result = new LinkedHashMap<>();
        for (CaseResult c : cases) {
            if (c.getExpectedIntents().isEmpty()) {
                continue;
            }
            String intent = c.getExpectedIntents().get(0);
            AccuracyMetric metric = result.computeIfAbsent(intent, ignored -> new AccuracyMetric());
            for (RunResult run : c.getRuns()) {
                if (!OutcomeType.INFRA_ERROR.name().equals(run.getOutcomeType())) {
                    metric.setRunCount(metric.getRunCount() + 1);
                    if (run.isPassed()) {
                        metric.setPassedRunCount(metric.getPassedRunCount() + 1);
                    }
                }
            }
        }
        result.values().forEach(metric -> metric.setAccuracy(rate(metric.getPassedRunCount(), metric.getRunCount())));
        metrics.setPerIntentAccuracy(result);
    }

    private void populateConfusionMatrix(List<CaseResult> cases, GlobalMetrics metrics) {
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();
        for (CaseResult c : cases) {
            if (!"single-task".equals(c.getCategory()) || c.getExpectedIntents().size() != 1) {
                continue;
            }
            String expected = c.getExpectedIntents().get(0);
            for (RunResult run : c.getRuns()) {
                if (!OutcomeType.ROUTE.name().equals(run.getOutcomeType()) || run.getActualIntents().size() != 1) {
                    continue;
                }
                String actual = run.getActualIntents().get(0);
                matrix.computeIfAbsent(expected, ignored -> new LinkedHashMap<>())
                        .merge(actual, 1, Integer::sum);
            }
        }
        metrics.setConfusionMatrix(matrix);
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private double averageInteger(List<Integer> values) {
        List<Integer> safeValues = values.stream().filter(Objects::nonNull).toList();
        return safeValues.isEmpty() ? 0 : safeValues.stream().mapToInt(Integer::intValue).average().orElse(0);
    }

    private double averageLong(List<Long> values) {
        List<Long> safeValues = values.stream().filter(Objects::nonNull).toList();
        return safeValues.isEmpty() ? 0 : safeValues.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private long percentile95(List<Long> values) {
        List<Long> safeValues = values.stream()
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (safeValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(safeValues.size() * 0.95) - 1;
        return safeValues.get(Math.max(0, Math.min(index, safeValues.size() - 1)));
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private enum OutcomeType {
        ROUTE,
        FORMAT_ERROR,
        INFRA_ERROR
    }

    @Data
    public static class EvalReport {
        private String evalRunId;
        private String batchTraceId;
        private String baselineEvalRunId;
        private String startedAt;
        private String finishedAt;
        private String clientId;
        private String routingMode;
        private String suite;
        private String tag;
        private GlobalMetrics metrics;
        private EvalComparison comparison;
        private List<CaseResult> cases = new ArrayList<>();

        public List<String> failedCaseIds() {
            return cases.stream().filter(c -> !c.isPassed()).map(CaseResult::getCaseId).toList();
        }
    }

    @Data
    public static class EvalComparison {
        private Map<String, Double> metricDeltas = new LinkedHashMap<>();
        private List<String> regressedCaseIds = new ArrayList<>();
        private List<String> improvedCaseIds = new ArrayList<>();
        private List<String> newCaseIds = new ArrayList<>();
        private List<String> removedCaseIds = new ArrayList<>();
    }

    @Data
    public static class CaseResult {
        private String caseId;
        private String suite;
        private String category;
        private String description;
        private String query;
        private List<String> expectedIntents = new ArrayList<>();
        private double minPassRate;
        private double minConsistencyRate;
        private int effectiveRunCount;
        private int passedRunCount;
        private int formatErrorCount;
        private int infrastructureErrorCount;
        private double passRate;
        private double consistencyRate;
        private boolean passed;
        private List<RunResult> runs = new ArrayList<>();
    }

    @Data
    public static class RunResult {
        private String traceId;
        private int runIndex;
        private long latencyMs;
        private String outcomeType;
        private String signature;
        private String reasoning;
        private boolean passed;
        private boolean actualMultiTask;
        private boolean actualNeedsClarification;
        private List<String> actualIntents = new ArrayList<>();
        private List<String> routingConfidences = new ArrayList<>();
        private String routingMinConfidence;
        private Boolean routingHasLowConfidence;
        private List<String> missingInfo = new ArrayList<>();
        private String routingMode;
        private Long routingLatencyMs;
        private Integer totalPromptTokens;
        private Integer totalCompletionTokens;
        private Integer totalTokens;
        private Integer stageCount;
        private Boolean estimated;
        private String finalFailureType;
        private Boolean jsonModeEnabled;
        private Boolean schemaValidationEnabled;
        private List<RoutingStageMetric> stageMetrics = new ArrayList<>();
    }

    @Data
    public static class GlobalMetrics {
        private int caseCount;
        private int passedCaseCount;
        private double casePassRate;
        private int runCount;
        private int effectiveRunCount;
        private int passedRunCount;
        private double runAccuracy;
        private int formatErrorCount;
        private double formatErrorRate;
        private int infrastructureErrorCount;
        private double infrastructureErrorRate;
        private double clarificationAccuracy;
        private double multiTaskExactMatch;
        private double avgLatencyMs;
        private long p95LatencyMs;
        private double avgRoutingLatencyMs;
        private long p95RoutingLatencyMs;
        private double avgTokens;
        private double avgPromptTokens;
        private double avgCompletionTokens;
        private double avgStageCount;
        private double estimatedTokenRate;
        private double stageSuccessRate;
        private Map<String, AccuracyMetric> perIntentAccuracy = new LinkedHashMap<>();
        private Map<String, Map<String, Integer>> confusionMatrix = new LinkedHashMap<>();
    }

    @Data
    public static class AccuracyMetric {
        private int runCount;
        private int passedRunCount;
        private double accuracy;
    }
}
