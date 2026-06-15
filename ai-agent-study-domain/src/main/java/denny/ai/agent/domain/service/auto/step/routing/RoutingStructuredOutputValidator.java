package denny.ai.agent.domain.service.auto.step.routing;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import denny.ai.agent.domain.model.valobj.DecomposedTask;
import denny.ai.agent.domain.model.valobj.SubTask;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.armory.factory.element.ChatResponseValidator;
import denny.ai.agent.domain.service.armory.factory.element.ResponseValidationException;
import denny.ai.agent.domain.service.armory.factory.element.ResponseValidationFailureType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class RoutingStructuredOutputValidator {

    private static final Set<String> ALLOWED_INTENTS = Set.of(
            "STOCK_ANALYSIS",
            "PE_REASONING",
            "PE_CALCULATION",
            "PE_RETRIEVAL",
            "INSPECTION",
            "GENERAL_CHAT"
    );
    private static final Set<String> ALLOWED_CONFIDENCE = Set.of("HIGH", "MEDIUM", "LOW");
    private static final Set<String> UNIFIED_ROOT_FIELDS = Set.of(
            "multiTask", "needsClarification", "missingInfo", "clarificationPrompt", "reasoning", "taskList"
    );
    private static final Set<String> UNIFIED_TASK_FIELDS = Set.of(
            "taskId", "taskIndex", "totalTasks", "content", "intent", "confidence", "slots", "dependsOn"
    );
    private static final Set<String> DECOMPOSITION_ROOT_FIELDS = Set.of("multiTask", "reasoning", "taskList");
    private static final Set<String> DECOMPOSITION_TASK_FIELDS = Set.of(
            "taskId", "taskIndex", "totalTasks", "content", "dependsOn"
    );
    private static final Set<String> TASK_INTENT_FIELDS = Set.of(
            "intent", "confidence", "reasoning", "baseSlot", "intentSpecificSlots"
    );

    private final TaskGraphValidator taskGraphValidator;

    public RoutingStructuredOutputValidator(TaskGraphValidator taskGraphValidator) {
        this.taskGraphValidator = taskGraphValidator;
    }

    public ChatResponseValidator unified() {
        return response -> validateUnifiedOutput(extractJsonObject(response));
    }

    public ChatResponseValidator queryDecomposition() {
        return response -> validateQueryDecompositionOutput(extractJsonObject(response));
    }

    public ChatResponseValidator taskIntentRouting() {
        return response -> validateTaskIntentRoutingOutput(extractJsonObject(response));
    }

    public UnifiedRoutingOutput parseUnified(String response) {
        return toDto(parseObject(response), UnifiedRoutingOutput.class);
    }

    public QueryDecompositionOutput parseQueryDecomposition(String response) {
        return toDto(parseObject(response), QueryDecompositionOutput.class);
    }

    public TaskIntentRoutingOutput parseTaskIntentRouting(String response) {
        return toDto(parseObject(response), TaskIntentRoutingOutput.class);
    }

    private void validateUnifiedOutput(JSONObject json) {
        rejectUnknownFields(json, UNIFIED_ROOT_FIELDS, "unified root");
        requireBoolean(json, "multiTask");
        requireBoolean(json, "needsClarification");
        requireString(json, "reasoning");
        requireArray(json, "missingInfo");
        requireArray(json, "taskList");
        ensureOptionalString(json, "clarificationPrompt");

        JSONArray taskArray = json.getJSONArray("taskList");
        for (Object item : taskArray) {
            JSONObject task = requireObject(item, "taskList item");
            rejectUnknownFields(task, UNIFIED_TASK_FIELDS, "unified task");
            requireString(task, "taskId");
            requireInteger(task, "taskIndex");
            requireInteger(task, "totalTasks");
            requireString(task, "content");
            requireAllowedString(task, "intent", ALLOWED_INTENTS);
            requireAllowedString(task, "confidence", ALLOWED_CONFIDENCE);
            ensureOptionalObject(task, "slots");
            ensureOptionalStringArray(task, "dependsOn");
        }

        UnifiedRoutingOutput output = toDto(json, UnifiedRoutingOutput.class);
        validateUnifiedBusinessRules(output);
    }

    private void validateQueryDecompositionOutput(JSONObject json) {
        rejectUnknownFields(json, DECOMPOSITION_ROOT_FIELDS, "decomposition root");
        requireBoolean(json, "multiTask");
        requireString(json, "reasoning");
        requireArray(json, "taskList");

        JSONArray taskArray = json.getJSONArray("taskList");
        for (Object item : taskArray) {
            JSONObject task = requireObject(item, "taskList item");
            rejectUnknownFields(task, DECOMPOSITION_TASK_FIELDS, "decomposition task");
            requireString(task, "taskId");
            requireInteger(task, "taskIndex");
            requireInteger(task, "totalTasks");
            requireString(task, "content");
            ensureOptionalStringArray(task, "dependsOn");
        }

        QueryDecompositionOutput output = toDto(json, QueryDecompositionOutput.class);
        List<DecomposedTask> tasks = toDecomposedTasks(output);
        if (tasks.size() == 1 && Boolean.TRUE.equals(output.getMultiTask())) {
            throw business("single task must not be marked as multiTask");
        }
        validateTaskGraph(tasks);
    }

    private void validateTaskIntentRoutingOutput(JSONObject json) {
        rejectUnknownFields(json, TASK_INTENT_FIELDS, "task intent root");
        requireAllowedString(json, "intent", ALLOWED_INTENTS);
        requireAllowedString(json, "confidence", ALLOWED_CONFIDENCE);
        requireString(json, "reasoning");
        ensureOptionalObject(json, "baseSlot");
        ensureOptionalObject(json, "intentSpecificSlots");
        toDto(json, TaskIntentRoutingOutput.class);
    }

    private JSONObject extractJsonObject(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || !StringUtils.hasText(response.getResult().getOutput().getText())) {
            throw new ResponseValidationException(ResponseValidationFailureType.EMPTY_RESPONSE,
                    "LLM returned an empty response");
        }
        return parseObject(response.getResult().getOutput().getText());
    }

    private JSONObject parseObject(String response) {
        if (!StringUtils.hasText(response)) {
            throw new ResponseValidationException(ResponseValidationFailureType.EMPTY_RESPONSE,
                    "LLM returned an empty response");
        }
        String jsonText = extractJson(response);
        try {
            return JSON.parseObject(jsonText);
        } catch (JSONException e) {
            throw new ResponseValidationException(ResponseValidationFailureType.JSON_PARSE_ERROR,
                    "JSON parsing failed: " + e.getMessage(), e);
        }
    }

    private String extractJson(String response) {
        int start = response.indexOf("{");
        int end = response.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    private <T> T toDto(JSONObject json, Class<T> type) {
        try {
            return json.toJavaObject(type);
        } catch (Exception e) {
            throw new ResponseValidationException(ResponseValidationFailureType.DTO_CONVERSION_ERROR,
                    "DTO conversion failed: " + e.getMessage(), e);
        }
    }

    private void validateUnifiedBusinessRules(UnifiedRoutingOutput output) {
        if (Boolean.TRUE.equals(output.getNeedsClarification())) {
            if (output.getMissingInfo() == null || output.getMissingInfo().isEmpty()) {
                throw business("missingInfo must not be empty when needsClarification is true");
            }
            return;
        }
        List<SubTask> tasks = toSubTasks(output);
        if (Boolean.TRUE.equals(output.getMultiTask()) && tasks.size() <= 1) {
            throw business("multiTask=true requires more than one task");
        }
        if (!Boolean.TRUE.equals(output.getMultiTask()) && tasks.size() > 1) {
            throw business("multiTask=false allows at most one task");
        }
        validateTaskGraph(tasks);
    }

    private List<SubTask> toSubTasks(UnifiedRoutingOutput output) {
        List<SubTask> tasks = new ArrayList<>();
        if (output.getTaskList() == null) {
            return tasks;
        }
        for (UnifiedRoutingOutput.TaskOutput task : output.getTaskList()) {
            IntentTypeEnum intent = IntentTypeEnum.fromCode(task.getIntent());
            ConfidenceEnum confidence = ConfidenceEnum.fromCode(task.getConfidence());
            tasks.add(SubTask.builder()
                    .taskId(task.getTaskId())
                    .taskIndex(task.getTaskIndex())
                    .totalTasks(task.getTotalTasks())
                    .content(task.getContent())
                    .intent(intent)
                    .confidence(confidence)
                    .dependsOn(task.getDependsOn() == null ? List.of() : task.getDependsOn())
                    .slots(task.getSlots() == null ? Map.of() : task.getSlots())
                    .build());
        }
        return tasks;
    }

    private List<DecomposedTask> toDecomposedTasks(QueryDecompositionOutput output) {
        List<DecomposedTask> tasks = new ArrayList<>();
        if (output.getTaskList() == null) {
            return tasks;
        }
        for (QueryDecompositionOutput.TaskOutput task : output.getTaskList()) {
            tasks.add(DecomposedTask.builder()
                    .taskId(task.getTaskId())
                    .taskIndex(task.getTaskIndex())
                    .totalTasks(task.getTotalTasks())
                    .content(task.getContent())
                    .dependsOn(task.getDependsOn() == null ? List.of() : task.getDependsOn())
                    .build());
        }
        return tasks;
    }

    private void validateTaskGraph(List<?> tasks) {
        try {
            if (tasks.isEmpty() || tasks.get(0) instanceof SubTask) {
                @SuppressWarnings("unchecked")
                List<SubTask> subTasks = (List<SubTask>) tasks;
                taskGraphValidator.validateSubTasks(subTasks);
            } else {
                @SuppressWarnings("unchecked")
                List<DecomposedTask> decomposedTasks = (List<DecomposedTask>) tasks;
                taskGraphValidator.validateDecomposedTasks(decomposedTasks);
            }
        } catch (TaskGraphValidationException e) {
            throw business(e.getMessage());
        }
    }

    private void rejectUnknownFields(JSONObject object, Set<String> allowedFields, String path) {
        for (String key : object.keySet()) {
            if (!allowedFields.contains(key)) {
                throw schema(path + " contains unsupported field: " + key);
            }
        }
    }

    private JSONObject requireObject(Object value, String field) {
        if (value instanceof JSONObject json) {
            return json;
        }
        throw schema(field + " must be an object");
    }

    private void requireBoolean(JSONObject object, String field) {
        Object value = requirePresent(object, field);
        if (!(value instanceof Boolean)) {
            throw schema(field + " must be boolean");
        }
    }

    private void requireInteger(JSONObject object, String field) {
        Object value = requirePresent(object, field);
        if (!(value instanceof Integer)) {
            throw schema(field + " must be integer");
        }
    }

    private void requireString(JSONObject object, String field) {
        Object value = requirePresent(object, field);
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw schema(field + " must be non-blank string");
        }
    }

    private void requireAllowedString(JSONObject object, String field, Set<String> allowedValues) {
        requireString(object, field);
        String value = object.getString(field);
        if (!allowedValues.contains(value)) {
            throw schema(field + " has unsupported value: " + value);
        }
    }

    private void requireArray(JSONObject object, String field) {
        Object value = requirePresent(object, field);
        if (!(value instanceof JSONArray)) {
            throw schema(field + " must be array");
        }
    }

    private Object requirePresent(JSONObject object, String field) {
        if (!object.containsKey(field) || object.get(field) == null) {
            throw schema(field + " is required");
        }
        return object.get(field);
    }

    private void ensureOptionalString(JSONObject object, String field) {
        if (object.containsKey(field) && object.get(field) != null && !(object.get(field) instanceof String)) {
            throw schema(field + " must be string or null");
        }
    }

    private void ensureOptionalObject(JSONObject object, String field) {
        if (object.containsKey(field) && object.get(field) != null && !(object.get(field) instanceof JSONObject)) {
            throw schema(field + " must be object or null");
        }
    }

    private void ensureOptionalStringArray(JSONObject object, String field) {
        if (!object.containsKey(field) || object.get(field) == null) {
            return;
        }
        Object value = object.get(field);
        if (!(value instanceof JSONArray array)) {
            throw schema(field + " must be array or null");
        }
        for (Object item : array) {
            if (!(item instanceof String text) || !StringUtils.hasText(text)) {
                throw schema(field + " must contain non-blank strings");
            }
        }
    }

    private ResponseValidationException schema(String message) {
        return new ResponseValidationException(ResponseValidationFailureType.SCHEMA_VALIDATION_ERROR, message);
    }

    private ResponseValidationException business(String message) {
        return new ResponseValidationException(ResponseValidationFailureType.BUSINESS_VALIDATION_ERROR, message);
    }
}
