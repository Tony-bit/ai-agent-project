# Intent Few-shot Document Archive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace hard-coded Few-shot seeds with one versioned JSON archive shaped like Spring AI `Document`, migrate legacy examples to the current routing schema, and let the existing integration test import the archive directly into PGVector.

**Architecture:** The JSON archive is the only repository-side source of Few-shot text. A test-support loader validates stable IDs, metadata, routing JSON, and enabled status before creating Spring AI `Document` instances. Production retrieval continues to read PGVector; actual remote embedding and table cleanup remain manual integration-test operations.

**Tech Stack:** Java 17, Spring AI `Document`/`PgVectorStore`, Jackson, JUnit 4, Maven

---

## File Structure

- Create `ai-agent-study-app/src/test/resources/fewshot/intent-fewshot-documents.json`: all legacy and new Few-shot documents.
- Create `ai-agent-study-app/src/test/java/denny/ai/agent/test/spring/ai/support/IntentFewshotDocumentLoader.java`: parse and validate the archive, returning enabled `Document` objects.
- Create `ai-agent-study-app/src/test/java/denny/ai/agent/test/spring/ai/support/IntentFewshotDocumentLoaderTest.java`: unit tests for valid loading and invalid archive rejection.
- Modify `ai-agent-study-app/src/test/java/denny/ai/agent/test/spring/ai/OpenAiIntegrationTest.java`: replace hard-coded samples with archive loading and batched `accept()`.
- Modify `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/intent/IntentFewshotService.java`: accept generic valid clarification samples instead of requiring only `analysisDepth`.
- Modify `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/intent/IntentFewshotServiceTest.java`: verify legacy `stockCode` clarification compatibility.

### Task 1: Preserve Valid Legacy Clarification Samples

| Task | status |
|------|------|
| Task 1: Preserve Valid Legacy Clarification Samples | pass |

**Files:**
- Modify: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/intent/IntentFewshotServiceTest.java`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/intent/IntentFewshotService.java:240-263`

- [ ] **Step 1: Change the retrieval test expectation**

Rename `badClarification` to `stockCodeClarification` and assert that IDs `1`, `6`, and `7` survive filtering. Keep malformed, disabled, unknown-intent, and mismatched-intent examples rejected.

```java
Document stockCodeClarification = document(6L, "帮我分析这只股票", "AMBIGUOUS",
        clarificationExample("stockCode"), 1);

assertEquals(List.of(1L, 6L, 7L),
        samples.stream().map(IntentFewshotSample::getId).toList());
```

- [ ] **Step 2: Run the focused test and confirm failure**

Run:

```powershell
mvn -pl ai-agent-study-domain -Dtest=IntentFewshotServiceTest test
```

Expected: FAIL because the current service only accepts clarification samples containing `analysisDepth`.

- [ ] **Step 3: Generalize clarification validation**

Replace the hard-coded `analysisDepth` requirement with the canonical clarification contract:

```java
if (Boolean.TRUE.equals(output.getNeedsClarification())) {
    if (metadataIntent != IntentTypeEnum.AMBIGUOUS
            || output.getMissingInfo() == null
            || output.getMissingInfo().isEmpty()
            || output.getTaskList() == null
            || !output.getTaskList().isEmpty()) {
        throw new IllegalArgumentException(
                "clarification sample must use AMBIGUOUS, non-empty missingInfo, and empty taskList");
    }
    return;
}
```

- [ ] **Step 4: Run the domain test**

Run the command from Step 2.

Expected: PASS; both `stockCode` and `analysisDepth` clarification samples are retained.

- [ ] **Step 5: Commit**

```powershell
git add ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/intent/IntentFewshotService.java ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/intent/IntentFewshotServiceTest.java
git commit -m "fix: preserve valid fewshot clarifications"
```

### Task 2: Add the Document Archive Loader

| Task | status |
|------|------|
| Task 2: Add the Document Archive Loader | pass |

**Files:**
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/spring/ai/support/IntentFewshotDocumentLoader.java`
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/spring/ai/support/IntentFewshotDocumentLoaderTest.java`

- [ ] **Step 1: Write loader tests**

Cover these behaviors using in-memory `ByteArrayResource` values:

```java
@Test
public void loadsEnabledDocumentsWithoutReshapingMetadata() {
    List<Document> docs = loader.load(resource(validArchive()));
    assertEquals(1, docs.size());
    assertEquals("FINANCIAL_GENERAL", docs.get(0).getMetadata().get("intentCode"));
    assertTrue(docs.get(0).getMetadata().get("exampleJson") instanceof String);
}

@Test
public void skipsDisabledDocuments() {
    assertTrue(loader.load(resource(disabledArchive())).isEmpty());
}

@Test
public void rejectsDuplicateLogicalIdsAndTexts() {
    assertThrows(IllegalArgumentException.class,
            () -> loader.load(resource(duplicateArchive())));
}

@Test
public void rejectsNonDeterministicDocumentId() {
    assertThrows(IllegalArgumentException.class,
            () -> loader.load(resource(wrongUuidArchive())));
}

@Test
public void rejectsInvalidOrMismatchedExampleJson() {
    assertThrows(IllegalArgumentException.class,
            () -> loader.load(resource(mismatchedArchive())));
}
```

- [ ] **Step 2: Run the loader test and confirm failure**

Run:

```powershell
mvn -pl ai-agent-study-app -am -Dtest=IntentFewshotDocumentLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because `IntentFewshotDocumentLoader` does not exist.

- [ ] **Step 3: Implement the loader**

The loader must:

1. Parse the root JSON array with Jackson.
2. Require `id`, `text`, `metadata.id`, `metadata.intentCode`, `metadata.exampleJson`, and `metadata.status`.
3. Verify `id` equals `UUID.nameUUIDFromBytes(("intent-fewshot:" + metadata.id).getBytes(UTF_8))`.
4. Reject duplicate document IDs, logical IDs, and normalized enabled texts.
5. Validate `metadata.exampleJson` through `RoutingStructuredOutputValidator.validateAndParseUnified()`.
6. Enforce `AMBIGUOUS` for clarification samples and metadata/task intent agreement for normal samples.
7. Skip `status=0` and return `status=1` as `new Document(id, text, metadata)` without reshaping metadata.

Use constructor injection so the test and integration test share the production validator:

```java
public IntentFewshotDocumentLoader(ObjectMapper objectMapper,
        RoutingStructuredOutputValidator validator) {
    this.objectMapper = objectMapper;
    this.validator = validator;
}

public List<Document> load(Resource resource) throws IOException {
    // Parse, validate, and return enabled documents.
}
```

- [ ] **Step 4: Run the loader tests**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add ai-agent-study-app/src/test/java/denny/ai/agent/test/spring/ai/support
git commit -m "test: add fewshot document archive loader"
```

### Task 3: Build and Validate the Full Archive

| Task | status |
|------|------|
| Task 3: Build and Validate the Full Archive | pass |

**Files:**
- Create: `ai-agent-study-app/src/test/resources/fewshot/intent-fewshot-documents.json`
- Modify: `ai-agent-study-app/src/test/java/denny/ai/agent/test/spring/ai/support/IntentFewshotDocumentLoaderTest.java`

- [ ] **Step 1: Add a failing full-archive contract test**

```java
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
```

- [ ] **Step 2: Run the contract test and confirm failure**

Run the loader-test Maven command from Task 2.

Expected: FAIL because the archive does not exist.

- [ ] **Step 3: Convert the 33 legacy samples**

For logical IDs `1` through `33`:

- Copy `Document.text` from the existing hard-coded sample.
- Remove `executorNode` and `taskType` from every `exampleJson` task.
- Add root `missingInfo` and `clarificationPrompt`.
- Add task `dependsOn`.
- Preserve existing `GENERAL_CHAT`, `PE_RETRIEVAL`, `PE_REASONING`, `PE_CALCULATION`, and `INSPECTION` labels.
- Convert objective financial queries such as K-line, indicators, and quotes to `FINANCIAL_GENERAL`.
- Convert depth-ambiguous stock requests to `AMBIGUOUS` with `missingInfo=["analysisDepth"]`.
- Convert the two legacy missing-stock samples to `AMBIGUOUS` while preserving `missingInfo=["stockCode"]`.
- Preserve the history-document follow-up as `PE_RETRIEVAL`.

Every `exampleJson` value must be a JSON string, not an embedded object.

- [ ] **Step 4: Append the 30 financial samples**

For logical IDs `34` through `63`, copy the 12 `FINANCIAL_GENERAL`, 10 `STOCK_ANALYSIS`, and 8 `AMBIGUOUS` texts from `docs/dev-ops/mysql/sql/dml/003-financial-general-intent-fewshot.sql`. Generate canonical `exampleJson` strings using the new routing schema.

Generate every outer document ID with:

```java
UUID.nameUUIDFromBytes(("intent-fewshot:" + logicalId)
        .getBytes(StandardCharsets.UTF_8)).toString()
```

- [ ] **Step 5: Run the archive contract test**

Run the loader-test Maven command from Task 2.

Expected: PASS with exactly 63 enabled documents and no duplicate IDs or texts.

- [ ] **Step 6: Commit**

```powershell
git add ai-agent-study-app/src/test/resources/fewshot/intent-fewshot-documents.json ai-agent-study-app/src/test/java/denny/ai/agent/test/spring/ai/support/IntentFewshotDocumentLoaderTest.java
git commit -m "test: archive intent fewshot documents"
```

### Task 4: Import the Archive from the Existing Integration Test

| Task | status |
|------|------|
| Task 4: Import the Archive from the Existing Integration Test | pass |

**Files:**
- Modify: `ai-agent-study-app/src/test/java/denny/ai/agent/test/spring/ai/OpenAiIntegrationTest.java:364-507`

- [ ] **Step 1: Inject the archive resource and validator**

```java
@Value("classpath:fewshot/intent-fewshot-documents.json")
private Resource intentFewshotDocumentsResource;

@Autowired
private RoutingStructuredOutputValidator routingStructuredOutputValidator;
```

- [ ] **Step 2: Replace hard-coded construction**

Remove the example generators, `docs.add(...)` calls, and `createDoc()` helpers. Load the archive directly:

```java
IntentFewshotDocumentLoader loader = new IntentFewshotDocumentLoader(
        new ObjectMapper(), routingStructuredOutputValidator);
List<Document> docs = loader.load(intentFewshotDocumentsResource);
log.info("准备写入 intent_fewshot_vector_store，docs.size={}", docs.size());
```

Retain the existing batch size of 10, `accept(batch)`, and similarity recall smoke check.

- [ ] **Step 3: Compile the integration test without running remote calls**

Run:

```powershell
mvn -pl ai-agent-study-app -am -DskipTests package
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Run all offline focused tests**

Run:

```powershell
mvn -pl ai-agent-study-domain,ai-agent-study-app -am -Dtest=IntentFewshotServiceTest,IntentFewshotDocumentLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: BUILD SUCCESS; no remote embedding call is made.

- [ ] **Step 5: Document the manual import precondition**

Add a method comment stating that the dedicated `intent_fewshot_vector_store` table must be cleared once before the first V2 import because historical records used random document IDs. Do not execute destructive SQL from the test.

- [ ] **Step 6: Commit**

```powershell
git add ai-agent-study-app/src/test/java/denny/ai/agent/test/spring/ai/OpenAiIntegrationTest.java
git commit -m "test: import fewshots from document archive"
```

## Final Verification

- [ ] Run `git diff --check`.
- [ ] Confirm the archive has exactly 63 enabled entries.
- [ ] Confirm every outer `id` matches the deterministic UUID for `metadata.id`.
- [ ] Confirm `exampleJson` is a string in every entry.
- [ ] Confirm no archive query is copied from `intent-routing-online-cases.json` solely for training.
- [ ] Run the focused Maven tests from Task 4.
- [ ] Do not run `test_intent_fewshot_pgvector_recall()` automatically because it calls the remote Embedding service and mutates PGVector.
