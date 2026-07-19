package denny.ai.agent.test.spring.ai.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.domain.service.auto.step.routing.RoutingStructuredOutputValidator;
import denny.ai.agent.domain.service.auto.step.routing.TaskGraphValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IntentFewshotDocumentLoaderTest {

    private IntentFewshotDocumentLoader loader;

    @BeforeEach
    public void setUp() {
        loader = new IntentFewshotDocumentLoader(
                new ObjectMapper(),
                new RoutingStructuredOutputValidator(new TaskGraphValidator()));
    }

    @Test
    public void loadsEnabledDocumentsWithoutReshapingMetadata() throws Exception {
        List<Document> docs = loader.load(resource(archive(document("1", "什么是市盈率？",
                "FINANCIAL_GENERAL", taskExample("FINANCIAL_GENERAL"), 1))));

        assertEquals(1, docs.size());
        assertEquals(stableId("1"), docs.get(0).getId());
        assertEquals("什么是市盈率？", docs.get(0).getText());
        assertEquals("1", docs.get(0).getMetadata().get("id"));
        assertEquals("FINANCIAL_GENERAL", docs.get(0).getMetadata().get("intentCode"));
        assertTrue(docs.get(0).getMetadata().get("exampleJson") instanceof String);
        assertEquals(1, docs.get(0).getMetadata().get("status"));
    }

    @Test
    public void skipsDisabledDocuments() throws Exception {
        List<Document> docs = loader.load(resource(archive(document("2", "停用样本",
                "GENERAL_CHAT", taskExample("GENERAL_CHAT"), 0))));

        assertTrue(docs.isEmpty());
    }

    @Test
    public void rejectsDuplicateLogicalIdsAndTexts() {
        String duplicateId = archive(
                document("3", "文本一", "GENERAL_CHAT", taskExample("GENERAL_CHAT"), 1),
                document("3", "文本二", "GENERAL_CHAT", taskExample("GENERAL_CHAT"), 1));
        assertThrows(IllegalArgumentException.class, () -> loader.load(resource(duplicateId)));

        String duplicateText = archive(
                document("4", "重复   文本", "GENERAL_CHAT", taskExample("GENERAL_CHAT"), 1),
                document("5", " 重复 文本 ", "GENERAL_CHAT", taskExample("GENERAL_CHAT"), 1));
        assertThrows(IllegalArgumentException.class, () -> loader.load(resource(duplicateText)));
    }

    @Test
    public void rejectsNonDeterministicDocumentId() {
        String json = document("6", "错误 UUID", "GENERAL_CHAT", taskExample("GENERAL_CHAT"), 1)
                .replace(stableId("6"), UUID.randomUUID().toString());

        assertThrows(IllegalArgumentException.class, () -> loader.load(resource(archive(json))));
    }

    @Test
    public void rejectsInvalidOrMismatchedExampleJson() {
        assertThrows(IllegalArgumentException.class, () -> loader.load(resource(archive(
                document("7", "非法 JSON", "GENERAL_CHAT", "not-json", 1)))));
        assertThrows(IllegalArgumentException.class, () -> loader.load(resource(archive(
                document("8", "标签不一致", "FINANCIAL_GENERAL", taskExample("STOCK_ANALYSIS"), 1)))));
        assertThrows(IllegalArgumentException.class, () -> loader.load(resource(archive(
                document("9", "澄清标签错误", "STOCK_ANALYSIS", clarificationExample("stockCode"), 1)))));
    }

    @Test
    public void fullArchiveContainsAllMigratedAndNewSamples() throws Exception {
        Resource resource = new ClassPathResource("fewshot/intent-fewshot-documents.json");
        List<Document> documents = loader.load(resource);

        assertEquals(63, documents.size());
        assertEquals(63, documents.stream().map(Document::getId).distinct().count());
        assertTrue(documents.stream().anyMatch(doc ->
                "FINANCIAL_GENERAL".equals(doc.getMetadata().get("intentCode"))));
        assertTrue(documents.stream().anyMatch(doc ->
                "STOCK_ANALYSIS".equals(doc.getMetadata().get("intentCode"))));
        assertTrue(documents.stream().anyMatch(doc ->
                "AMBIGUOUS".equals(doc.getMetadata().get("intentCode"))));
    }

    private Resource resource(String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }

    private String archive(String... documents) {
        return "[" + String.join(",", documents) + "]";
    }

    private String document(String logicalId, String text, String intentCode, String exampleJson, int status) {
        try {
            return new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "id", stableId(logicalId),
                    "text", text,
                    "metadata", java.util.Map.of(
                            "id", logicalId,
                            "intentCode", intentCode,
                            "exampleJson", exampleJson,
                            "status", status)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String stableId(String logicalId) {
        return UUID.nameUUIDFromBytes(("intent-fewshot:" + logicalId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String taskExample(String intent) {
        return """
                {"multiTask":false,"needsClarification":false,"missingInfo":[],"clarificationPrompt":"",
                 "reasoning":"sample","taskList":[
                   {"taskId":"sub-1","taskIndex":1,"totalTasks":1,"content":"sample",
                    "intent":"%s","confidence":"HIGH","dependsOn":[],"slots":{}}
                 ]}
                """.formatted(intent).trim();
    }

    private String clarificationExample(String missingInfo) {
        return """
                {"multiTask":false,"needsClarification":true,"missingInfo":["%s"],
                 "clarificationPrompt":"请补充信息。","reasoning":"sample","taskList":[]}
                """.formatted(missingInfo).trim();
    }
}
