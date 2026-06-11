package denny.ai.agent.domain.service.auto.step.routing.support;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.routing.model.IntentRoutingEvalCase;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Intent Routing 评测 Case 加载器
 * <p>
 * 从 classpath 资源文件 intent-routing-cases.json 加载评测 case 列表。
 * 支持按 ID 查询、按 category 过滤。
 *
 * @author denny
 * 2026/06/08
 */
@Slf4j
public class IntentRoutingEvalCaseLoader {

    private static final String RESOURCE_PATH = "eval/intent-routing-cases.json";
    private static final Set<String> SUPPORTED_CATEGORIES = Set.of(
            "single-task", "multi-task", "clarification", "fallback"
    );
    private static final Set<String> SUPPORTED_STATUSES = Set.of(
            "pending", "pass", "fail", "disabled"
    );

    private final List<IntentRoutingEvalCase> cases;

    public IntentRoutingEvalCaseLoader() {
        this.cases = load();
    }

    /**
     * 加载全部评测 case
     */
    public List<IntentRoutingEvalCase> load() {
        List<IntentRoutingEvalCase> result = new ArrayList<>();
        InputStream resource = getClass().getClassLoader().getResourceAsStream(RESOURCE_PATH);
        if (resource == null) {
            throw new IllegalStateException("评测数据集文件不存在: " + RESOURCE_PATH);
        }

        try (InputStream is = resource;
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String jsonStr = reader.lines().collect(Collectors.joining());

            JSONArray jsonArray = JSON.parseArray(jsonStr);
            if (jsonArray == null || jsonArray.isEmpty()) {
                throw new IllegalStateException("评测数据集不能为空: " + RESOURCE_PATH);
            }
            for (int i = 0; i < jsonArray.size(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                IntentRoutingEvalCase aCase = parseCase(obj);
                result.add(aCase);
            }
            validateCases(result);
            log.info("成功加载 {} 条评测 case", result.size());
        } catch (Exception e) {
            throw new IllegalStateException("加载评测数据集失败: " + RESOURCE_PATH, e);
        }
        return result;
    }

    private IntentRoutingEvalCase parseCase(JSONObject obj) {
        String caseId = obj.getString("caseId");
        String status = obj.getString("status");
        String category = obj.getString("category");
        String description = obj.getString("description");
        List<String> tags = parseStringList(obj.getJSONArray("tags"));

        Object responseObj = obj.get("response");
        String responseStr;
        if (responseObj instanceof String) {
            responseStr = (String) responseObj;
        } else if (responseObj instanceof JSONObject) {
            responseStr = ((JSONObject) responseObj).toJSONString();
        } else {
            responseStr = responseObj != null ? responseObj.toString() : "{}";
        }

        JSONObject expectedObj = obj.getJSONObject("expected");
        IntentRoutingEvalCase.ExpectedResult expected = parseExpectedResult(expectedObj);

        return IntentRoutingEvalCase.builder()
                .caseId(caseId)
                .status(status)
                .category(category)
                .description(description)
                .response(responseStr)
                .expected(expected)
                .tags(tags)
                .build();
    }

    private IntentRoutingEvalCase.ExpectedResult parseExpectedResult(JSONObject obj) {
        if (obj == null) {
            return IntentRoutingEvalCase.ExpectedResult.builder().build();
        }
        Boolean multiTask = obj.getBoolean("multiTask");
        Boolean needsClarification = obj.getBoolean("needsClarification");
        Integer taskCount = obj.getInteger("taskCount");
        List<String> taskIntents = parseStringList(obj.getJSONArray("taskIntents"));
        List<String> executorNodes = parseStringList(obj.getJSONArray("executorNodes"));
        List<String> confidences = parseStringList(obj.getJSONArray("confidences"));
        List<Integer> taskTypes = parseIntegerList(obj.getJSONArray("taskTypes"));
        List<String> taskStatuses = parseStringList(obj.getJSONArray("taskStatuses"));
        List<String> missingInfo = parseStringList(obj.getJSONArray("missingInfo"));
        String clarificationPrompt = obj.getString("clarificationPrompt");
        String reasoningContains = obj.getString("reasoningContains");

        return IntentRoutingEvalCase.ExpectedResult.builder()
                .multiTask(multiTask)
                .needsClarification(needsClarification)
                .taskCount(taskCount)
                .taskIntents(taskIntents)
                .executorNodes(executorNodes)
                .confidences(confidences)
                .taskTypes(taskTypes)
                .taskStatuses(taskStatuses)
                .missingInfo(missingInfo)
                .clarificationPrompt(clarificationPrompt)
                .reasoningContains(reasoningContains)
                .build();
    }

    private List<String> parseStringList(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            result.add(arr.getString(i));
        }
        return result;
    }

    private List<Integer> parseIntegerList(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            result.add(arr.getInteger(i));
        }
        return result;
    }

    private void validateCases(List<IntentRoutingEvalCase> loadedCases) {
        Set<String> caseIds = new HashSet<>();
        for (IntentRoutingEvalCase aCase : loadedCases) {
            requireText(aCase.getCaseId(), "caseId");
            if (!caseIds.add(aCase.getCaseId())) {
                throw new IllegalArgumentException("caseId 重复: " + aCase.getCaseId());
            }
            requireText(aCase.getDescription(), "description, caseId=" + aCase.getCaseId());
            if (!SUPPORTED_CATEGORIES.contains(aCase.getCategory())) {
                throw new IllegalArgumentException("不支持的 category: " + aCase.getCategory()
                        + ", caseId=" + aCase.getCaseId());
            }
            if (!SUPPORTED_STATUSES.contains(aCase.getStatus())) {
                throw new IllegalArgumentException("不支持的 status: " + aCase.getStatus()
                        + ", caseId=" + aCase.getCaseId());
            }
            if (aCase.getExpected() == null) {
                throw new IllegalArgumentException("expected 不能为空, caseId=" + aCase.getCaseId());
            }
            validateExpected(aCase);
        }
    }

    private void validateExpected(IntentRoutingEvalCase aCase) {
        IntentRoutingEvalCase.ExpectedResult expected = aCase.getExpected();
        Integer taskCount = expected.getTaskCount();
        validateExpectedSize("taskIntents", expected.getTaskIntents(), taskCount, aCase.getCaseId());
        validateExpectedSize("executorNodes", expected.getExecutorNodes(), taskCount, aCase.getCaseId());
        validateExpectedSize("confidences", expected.getConfidences(), taskCount, aCase.getCaseId());
        validateExpectedSize("taskTypes", expected.getTaskTypes(), taskCount, aCase.getCaseId());
        validateExpectedSize("taskStatuses", expected.getTaskStatuses(), taskCount, aCase.getCaseId());

        for (String intent : expected.getTaskIntents()) {
            if (IntentTypeEnum.fromCode(intent) == IntentTypeEnum.UNKNOWN && !"UNKNOWN".equals(intent)) {
                throw new IllegalArgumentException("未知 taskIntent: " + intent + ", caseId=" + aCase.getCaseId());
            }
        }
        for (String confidence : expected.getConfidences()) {
            boolean supported = false;
            for (ConfidenceEnum value : ConfidenceEnum.values()) {
                supported = supported || value.getCode().equals(confidence);
            }
            if (!supported) {
                throw new IllegalArgumentException("未知 confidence: " + confidence + ", caseId=" + aCase.getCaseId());
            }
        }
        for (String status : expected.getTaskStatuses()) {
            try {
                SubTask.SubTaskStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("未知 taskStatus: " + status
                        + ", caseId=" + aCase.getCaseId(), e);
            }
        }
    }

    private void validateExpectedSize(String fieldName, List<?> values, Integer taskCount, String caseId) {
        if (values != null && !values.isEmpty() && taskCount != null && values.size() != taskCount) {
            throw new IllegalArgumentException(fieldName + " 数量必须与 taskCount 一致, caseId=" + caseId);
        }
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }

    /**
     * 根据 caseId 精确查找
     */
    public IntentRoutingEvalCase getCaseById(String caseId) {
        return cases.stream()
                .filter(c -> caseId.equals(c.getCaseId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 按 category 过滤
     */
    public List<IntentRoutingEvalCase> getCasesByCategory(String category) {
        return cases.stream()
                .filter(c -> category.equals(c.getCategory()))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有 case
     */
    public List<IntentRoutingEvalCase> getAll() {
        return Collections.unmodifiableList(cases);
    }

    /**
     * 获取参与参数化回归的 case，disabled case 仍保留在数据集中但不执行。
     */
    public List<IntentRoutingEvalCase> getRunnableCases() {
        return cases.stream()
                .filter(c -> !"disabled".equals(c.getStatus()))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 获取指定 caseIds 的子集（保持顺序）
     */
    public List<IntentRoutingEvalCase> getCasesByIds(List<String> caseIds) {
        return caseIds.stream()
                .map(this::getCaseById)
                .filter(c -> c != null)
                .collect(Collectors.toList());
    }
}
