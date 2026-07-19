package denny.ai.agent.test.spring.ai.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.auto.step.routing.RoutingStructuredOutputValidator;
import denny.ai.agent.domain.service.auto.step.routing.UnifiedRoutingOutput;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class IntentFewshotDocumentLoader {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final RoutingStructuredOutputValidator validator;

    public IntentFewshotDocumentLoader(ObjectMapper objectMapper,
            RoutingStructuredOutputValidator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public List<Document> load(Resource resource) throws IOException {
        JsonNode root;
        try (InputStream inputStream = resource.getInputStream()) {
            root = objectMapper.readTree(inputStream);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Few-shot archive root must be an array");
        }

        List<Document> documents = new ArrayList<>();
        Set<String> documentIds = new HashSet<>();
        Set<String> logicalIds = new HashSet<>();
        Set<String> enabledTexts = new HashSet<>();
        for (int index = 0; index < root.size(); index++) {
            JsonNode item = root.get(index);
            try {
                Document document = parseDocument(item, documentIds, logicalIds, enabledTexts);
                if (document != null) {
                    documents.add(document);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid Few-shot document at index " + index
                        + ": " + e.getMessage(), e);
            }
        }
        return documents;
    }

    private Document parseDocument(JsonNode item, Set<String> documentIds,
            Set<String> logicalIds, Set<String> enabledTexts) {
        if (item == null || !item.isObject()) {
            throw new IllegalArgumentException("document must be an object");
        }
        String documentId = requiredText(item, "id");
        String text = requiredText(item, "text");
        JsonNode metadataNode = item.get("metadata");
        if (metadataNode == null || !metadataNode.isObject()) {
            throw new IllegalArgumentException("metadata must be an object");
        }

        String logicalId = requiredText(metadataNode, "id");
        if (!logicalId.matches("\\d+")) {
            throw new IllegalArgumentException("metadata.id must be a numeric string");
        }
        String intentCode = requiredText(metadataNode, "intentCode");
        String exampleJson = requiredText(metadataNode, "exampleJson");
        int status = requiredStatus(metadataNode);

        if (!documentIds.add(documentId)) {
            throw new IllegalArgumentException("duplicate document id: " + documentId);
        }
        if (!logicalIds.add(logicalId)) {
            throw new IllegalArgumentException("duplicate metadata.id: " + logicalId);
        }
        String expectedId = stableDocumentId(logicalId);
        if (!expectedId.equals(documentId)) {
            throw new IllegalArgumentException("id must equal deterministic UUID " + expectedId);
        }

        IntentTypeEnum metadataIntent = IntentTypeEnum.fromCode(intentCode);
        if (metadataIntent == IntentTypeEnum.UNKNOWN) {
            throw new IllegalArgumentException("unsupported metadata.intentCode: " + intentCode);
        }
        validateExample(logicalId, metadataIntent, intentCode, exampleJson);

        if (status == 0) {
            return null;
        }
        String normalizedText = text.trim().replaceAll("\\s+", " ");
        if (!enabledTexts.add(normalizedText)) {
            throw new IllegalArgumentException("duplicate enabled text: " + normalizedText);
        }

        Map<String, Object> metadata = objectMapper.convertValue(metadataNode, METADATA_TYPE);
        return new Document(documentId, text, metadata);
    }

    private void validateExample(String logicalId, IntentTypeEnum metadataIntent,
            String intentCode, String exampleJson) {
        UnifiedRoutingOutput output;
        try {
            output = validator.validateAndParseUnified(exampleJson);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("metadata.exampleJson is invalid for metadata.id="
                    + logicalId + ": " + e.getMessage(), e);
        }

        if (Boolean.TRUE.equals(output.getNeedsClarification())) {
            if (metadataIntent != IntentTypeEnum.AMBIGUOUS
                    || output.getMissingInfo() == null
                    || output.getMissingInfo().isEmpty()
                    || output.getTaskList() == null
                    || !output.getTaskList().isEmpty()) {
                throw new IllegalArgumentException(
                        "clarification example must use AMBIGUOUS, non-empty missingInfo, and empty taskList");
            }
            return;
        }
        if (metadataIntent == IntentTypeEnum.AMBIGUOUS
                || output.getTaskList() == null
                || output.getTaskList().isEmpty()
                || output.getTaskList().stream().anyMatch(task -> !intentCode.equals(task.getIntent()))) {
            throw new IllegalArgumentException("metadata.intentCode does not match taskList intents");
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || !StringUtils.hasText(value.textValue())) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private int requiredStatus(JsonNode metadata) {
        JsonNode value = metadata.get("status");
        if (value == null || !value.isIntegralNumber() || (value.intValue() != 0 && value.intValue() != 1)) {
            throw new IllegalArgumentException("metadata.status must be 0 or 1");
        }
        return value.intValue();
    }

    private String stableDocumentId(String logicalId) {
        return UUID.nameUUIDFromBytes(("intent-fewshot:" + logicalId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
