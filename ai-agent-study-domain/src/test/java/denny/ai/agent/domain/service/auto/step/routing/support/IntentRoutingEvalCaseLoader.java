package denny.ai.agent.domain.service.auto.step.routing.support;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
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
import java.util.List;
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

    private final List<IntentRoutingEvalCase> cases;

    public IntentRoutingEvalCaseLoader() {
        this.cases = load();
    }

    /**
     * 加载全部评测 case
     */
    public List<IntentRoutingEvalCase> load() {
        List<IntentRoutingEvalCase> result = new ArrayList<>();
        InputStream is = null;
        BufferedReader reader = null;
        try {
            is = getClass().getClassLoader().getResourceAsStream(RESOURCE_PATH);
            if (is == null) {
                log.error("评测数据集文件不存在: {}", RESOURCE_PATH);
                return Collections.emptyList();
            }
            reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String jsonStr = reader.lines().collect(Collectors.joining());

            JSONArray jsonArray = JSON.parseArray(jsonStr);
            for (int i = 0; i < jsonArray.size(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                IntentRoutingEvalCase aCase = parseCase(obj);
                result.add(aCase);
            }
            log.info("成功加载 {} 条评测 case", result.size());
        } catch (Exception e) {
            log.error("加载评测数据集失败: {}", RESOURCE_PATH, e);
        } finally {
            closeQuietly(reader);
            closeQuietly(is);
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
        List<String> missingInfo = parseStringList(obj.getJSONArray("missingInfo"));

        return IntentRoutingEvalCase.ExpectedResult.builder()
                .multiTask(multiTask)
                .needsClarification(needsClarification)
                .taskCount(taskCount)
                .taskIntents(taskIntents)
                .executorNodes(executorNodes)
                .missingInfo(missingInfo)
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

    private void closeQuietly(InputStream is) {
        if (is != null) {
            try {
                is.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void closeQuietly(BufferedReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
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
     * 获取指定 caseIds 的子集（保持顺序）
     */
    public List<IntentRoutingEvalCase> getCasesByIds(List<String> caseIds) {
        return caseIds.stream()
                .map(this::getCaseById)
                .filter(c -> c != null)
                .collect(Collectors.toList());
    }
}
