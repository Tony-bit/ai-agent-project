package denny.ai.agent.domain.service.intent;

import denny.ai.agent.domain.adapter.repository.IIntentFewshotSampleRepository;
import denny.ai.agent.domain.model.entity.IntentFewshotSample;
import denny.ai.agent.domain.service.auto.step.routing.RoutingStructuredOutputValidator;
import denny.ai.agent.domain.service.auto.step.routing.TaskGraphValidator;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class IntentFewshotServiceTest {

    @Mock
    private PgVectorStore vectorStore;
    @Mock
    private IIntentFewshotSampleRepository repository;

    private IntentFewshotService service;

    @Before
    public void setUp() {
        service = new IntentFewshotService();
        ReflectionTestUtils.setField(service, "intentFewshotVectorStore", vectorStore);
        ReflectionTestUtils.setField(service, "intentFewshotSampleRepository", repository);
        ReflectionTestUtils.setField(service, "structuredOutputValidator",
                new RoutingStructuredOutputValidator(new TaskGraphValidator()));
    }

    @Test
    public void retrieveTopKFiltersInvalidDisabledAndConflictingSamples() {
        Document valid = document(1L, "查询贵州茅台市盈率", "FINANCIAL_GENERAL",
                taskExample("FINANCIAL_GENERAL"), 1);
        Document disabled = document(2L, "查询股价", "FINANCIAL_GENERAL",
                taskExample("FINANCIAL_GENERAL"), 0);
        Document unknown = document(3L, "未知", "DEPRECATED_FINANCE", taskExample("FINANCIAL_GENERAL"), 1);
        Document mismatch = document(4L, "是否买入", "FINANCIAL_GENERAL", taskExample("STOCK_ANALYSIS"), 1);
        Document malformed = document(5L, "坏 JSON", "FINANCIAL_GENERAL", "not-json", 1);
        Document stockCodeClarification = document(6L, "帮我分析这只股票", "AMBIGUOUS",
                clarificationExample("stockCode"), 1);
        Document clarification = document(7L, "茅台最近怎么样", "AMBIGUOUS",
                clarificationExample("analysisDepth"), 1);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(valid, disabled, unknown, mismatch, malformed,
                        stockCodeClarification, clarification));

        List<IntentFewshotSample> samples = service.retrieveTopK("茅台", 5);

        assertEquals(List.of(1L, 6L, 7L), samples.stream().map(IntentFewshotSample::getId).toList());
    }

    @Test
    public void migrateSampleUpdatesMysqlAndRewritesStableVectorDocument() {
        IntentFewshotSample existing = IntentFewshotSample.builder()
                .id(7L)
                .queryText("查询贵州茅台市盈率")
                .intentCode("STOCK_ANALYSIS")
                .exampleJson(taskExample("STOCK_ANALYSIS"))
                .status(IntentFewshotSample.STATUS_ENABLED)
                .build();
        when(repository.queryById(7L)).thenReturn(existing);

        service.migrateSample(7L, "FINANCIAL_GENERAL", taskExample("FINANCIAL_GENERAL"), true);

        ArgumentCaptor<IntentFewshotSample> mysql = ArgumentCaptor.forClass(IntentFewshotSample.class);
        verify(repository).update(mysql.capture());
        assertEquals("FINANCIAL_GENERAL", mysql.getValue().getIntentCode());
        assertEquals(taskExample("FINANCIAL_GENERAL"), mysql.getValue().getExampleJson());
        assertEquals(Integer.valueOf(IntentFewshotSample.STATUS_ENABLED), mysql.getValue().getStatus());
        String stableId = stableVectorId(7L);
        verify(vectorStore).delete(List.of(stableId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> docs = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).accept(docs.capture());
        assertEquals(stableId, docs.getValue().get(0).getId());
        assertEquals("FINANCIAL_GENERAL", docs.getValue().get(0).getMetadata().get("intentCode"));
    }

    @Test
    public void addSampleRejectsMalformedOrMismatchedExamplesBeforeWriting() {
        assertThrows(IllegalArgumentException.class,
                () -> service.addSample("是否买入贵州茅台", "FINANCIAL_GENERAL", taskExample("STOCK_ANALYSIS")));

        verify(repository, never()).save(any());
        verify(vectorStore, never()).accept(any());
    }

    private Document document(Long id, String text, String intentCode, String exampleJson, int status) {
        return new Document(stableVectorId(id), text, Map.of(
                "id", String.valueOf(id),
                "intentCode", intentCode,
                "exampleJson", exampleJson,
                "status", status));
    }

    private String stableVectorId(Long id) {
        return UUID.nameUUIDFromBytes(("intent-fewshot:" + id).getBytes(StandardCharsets.UTF_8)).toString();
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
                 "clarificationPrompt":"你需要快速了解，还是进行完整投资分析？",
                 "reasoning":"sample","taskList":[]}
                """.formatted(missingInfo).trim();
    }
}
