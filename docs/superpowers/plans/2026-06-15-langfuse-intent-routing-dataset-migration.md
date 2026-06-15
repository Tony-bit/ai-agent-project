# Langfuse Intent Routing Dataset Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the stable intent-routing online eval cases from repository JSON into a Langfuse Dataset, while keeping the existing Java evaluator and report thresholds as the source of execution truth.

**Architecture:** Add a repeatable Node.js sync script that upserts `intent-routing-online-cases.json` into a Langfuse dataset. Add a Java test-only Langfuse dataset adapter that fetches hosted dataset items and maps them back to the existing `IntentRoutingOnlineEvalCase` model. Keep `IntentRoutingOnlineEvaluator` unchanged in the first migration so current pass rate, consistency, format error, infrastructure error, trace, score, and report behavior remain stable.

**Tech Stack:** Java 17, JUnit 5, fastjson, JDK `HttpClient`, Node.js 20+, `@langfuse/client`, Langfuse Datasets and Experiments.

---

## References

- Langfuse Datasets docs: https://langfuse.com/docs/evaluation/experiments/datasets
- Langfuse Experiments via SDK docs: https://langfuse.com/docs/evaluation/experiments/experiments-via-sdk
- Langfuse Experiments Data Model docs: https://langfuse.com/docs/evaluation/experiments/data-model
- Langfuse CI/CD experiments docs: https://langfuse.com/docs/evaluation/experiments/experiments-ci-cd

## Scope

This plan migrates only the online routing regression dataset:

- Source JSON: `ai-agent-study-app/src/test/resources/eval/intent-routing-online-cases.json`
- Existing model: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/IntentRoutingOnlineEvalCase.java`
- Existing loader: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/IntentRoutingOnlineEvalCaseLoader.java`
- Existing evaluator: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/IntentRoutingOnlineEvaluator.java`
- Existing integration entrypoint: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/IntentRoutingOnlineIntegrationTest.java`

The domain-side parser fixture `ai-agent-study-domain/src/test/resources/eval/intent-routing-cases.json` stays local for now because it tests parser, fallback, and normalization behavior from synthetic model responses, not true online routing inputs.

## Target Langfuse Dataset Shape

Dataset name:

```text
intent-routing/online-routing-regression
```

Each dataset item:

```json
{
  "id": "intent-routing-online/online-main-general-001",
  "datasetName": "intent-routing/online-routing-regression",
  "input": {
    "query": "你好，今天过得怎么样？",
    "historyMessages": []
  },
  "expectedOutput": {
    "multiTask": false,
    "needsClarification": false,
    "taskIntents": ["GENERAL_CHAT"],
    "acceptableTaskIntents": [],
    "orderSensitive": true,
    "missingInfoContains": [],
    "missingInfoNotEmpty": false
  },
  "metadata": {
    "caseId": "online-main-general-001",
    "enabled": true,
    "suite": "smoke",
    "category": "single-task",
    "description": "标准问候应进入普通对话",
    "evaluation": {
      "runs": 2,
      "minPassRate": 1.0,
      "minConsistencyRate": 1.0
    },
    "tags": ["general", "mainline", "chinese"],
    "source": "ai-agent-study-app/src/test/resources/eval/intent-routing-online-cases.json"
  }
}
```

Important invariants:

- Use `intent-routing-online/${caseId}` as Langfuse dataset item id because Langfuse dataset item ids are project-level unique and are upserted by id.
- Keep evaluation thresholds in `metadata.evaluation`; do not put them into `expectedOutput`, because they are harness settings, not expected model output.
- Keep `enabled`, `suite`, `category`, `description`, and `tags` in metadata so existing filtering works.
- Keep `input.query` and `input.historyMessages` exactly as current Java tests expect.

## File Structure

- Create: `scripts/langfuse/package.json`
  - Owns the small Node tool dependency boundary.
- Create: `scripts/langfuse/sync-intent-routing-dataset.mjs`
  - Reads repository JSON and upserts the Langfuse dataset plus dataset items.
- Create: `scripts/langfuse/README.md`
  - Documents local execution, environment variables, dry-run, and verification.
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseDatasetItem.java`
  - Test-only DTO for Langfuse dataset item responses.
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseDatasetClient.java`
  - Test-only JDK HTTP client for fetching Langfuse dataset items.
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseIntentRoutingOnlineEvalCaseMapper.java`
  - Maps Langfuse dataset items to `IntentRoutingOnlineEvalCase`.
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseIntentRoutingOnlineEvalCaseLoader.java`
  - Fetches remote dataset and applies suite/tag/enabled filters.
- Modify: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/IntentRoutingOnlineEvalCaseLoader.java`
  - Adds source switch: default local JSON, optional Langfuse source.
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseIntentRoutingOnlineEvalCaseMapperTest.java`
  - Verifies exact field mapping.
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseDatasetClientTest.java`
  - Verifies auth header, pagination, dataset name encoding, and parse behavior against a local JDK HTTP server.
- Modify: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/IntentRoutingOnlineEvalDatasetTest.java`
  - Keeps local JSON validation as baseline; adds a source-neutral validation test if Langfuse source is configured.

## Task 1: Add the Langfuse Dataset Sync Script

**Files:**
- Create: `scripts/langfuse/package.json`
- Create: `scripts/langfuse/sync-intent-routing-dataset.mjs`
- Create: `scripts/langfuse/README.md`

- [ ] **Step 1: Create the script package**

Create `scripts/langfuse/package.json`:

```json
{
  "name": "ai-agent-study-langfuse-tools",
  "private": true,
  "type": "module",
  "scripts": {
    "sync:intent-routing": "node sync-intent-routing-dataset.mjs"
  },
  "dependencies": {
    "@langfuse/client": "^5.0.0"
  }
}
```

- [ ] **Step 2: Install dependencies**

Run:

```powershell
cd scripts/langfuse
npm install
```

Expected:

```text
added ... packages
found 0 vulnerabilities
```

- [ ] **Step 3: Create the sync script**

Create `scripts/langfuse/sync-intent-routing-dataset.mjs`:

```javascript
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { LangfuseClient } from "@langfuse/client";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "../..");
const defaultSource = path.join(
  repoRoot,
  "ai-agent-study-app/src/test/resources/eval/intent-routing-online-cases.json",
);

const datasetName = process.env.LANGFUSE_DATASET_NAME || "intent-routing/online-routing-regression";
const sourcePath = process.env.INTENT_ROUTING_EVAL_SOURCE_JSON || defaultSource;
const dryRun = process.argv.includes("--dry-run");

function required(name) {
  const value = process.env[name];
  if (!value || value.trim() === "") {
    throw new Error(`${name} is required`);
  }
  return value;
}

function datasetItemId(caseId) {
  return `intent-routing-online/${caseId}`;
}

function toDatasetItem(c) {
  if (!c.caseId) throw new Error("caseId is required");
  if (!c.input?.query) throw new Error(`${c.caseId}: input.query is required`);
  if (!c.expected) throw new Error(`${c.caseId}: expected is required`);
  if (!c.evaluation) throw new Error(`${c.caseId}: evaluation is required`);

  return {
    id: datasetItemId(c.caseId),
    datasetName,
    input: {
      query: c.input.query,
      historyMessages: c.input.historyMessages || [],
    },
    expectedOutput: {
      multiTask: c.expected.multiTask,
      needsClarification: c.expected.needsClarification,
      taskIntents: c.expected.taskIntents || [],
      acceptableTaskIntents: c.expected.acceptableTaskIntents || [],
      orderSensitive: c.expected.orderSensitive !== false,
      missingInfoContains: c.expected.missingInfoContains || [],
      missingInfoNotEmpty: c.expected.missingInfoNotEmpty === true,
    },
    metadata: {
      caseId: c.caseId,
      enabled: c.enabled !== false,
      suite: c.suite,
      category: c.category,
      description: c.description,
      evaluation: c.evaluation,
      tags: c.tags || [],
      source: "ai-agent-study-app/src/test/resources/eval/intent-routing-online-cases.json",
    },
  };
}

async function main() {
  required("LANGFUSE_PUBLIC_KEY");
  required("LANGFUSE_SECRET_KEY");

  const raw = await fs.readFile(sourcePath, "utf8");
  const cases = JSON.parse(raw);
  const items = cases.map(toDatasetItem);
  const ids = new Set();
  for (const item of items) {
    if (ids.has(item.id)) throw new Error(`duplicate dataset item id: ${item.id}`);
    ids.add(item.id);
  }

  console.log(`Dataset: ${datasetName}`);
  console.log(`Source: ${sourcePath}`);
  console.log(`Items: ${items.length}`);

  if (dryRun) {
    console.log(JSON.stringify(items.slice(0, 2), null, 2));
    console.log("Dry run completed; no Langfuse writes were made.");
    return;
  }

  const langfuse = new LangfuseClient();
  await langfuse.api.datasets.create({
    name: datasetName,
    description: "Intent routing online regression dataset migrated from ai-agent-study",
    metadata: {
      owner: "ai-agent-study",
      source: "repo-json",
      migratedAt: new Date().toISOString(),
    },
  }).catch((error) => {
    if (!String(error?.message || error).includes("already")) throw error;
    console.log("Dataset already exists; continuing with item upserts.");
  });

  for (const item of items) {
    await langfuse.api.datasetItems.create(item);
    console.log(`upserted ${item.id}`);
  }

  console.log(`Synced ${items.length} dataset items to ${datasetName}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
```

- [ ] **Step 4: Dry-run the transformation**

Run:

```powershell
cd scripts/langfuse
$env:LANGFUSE_PUBLIC_KEY="dry-run"
$env:LANGFUSE_SECRET_KEY="dry-run"
npm run sync:intent-routing -- --dry-run
```

Expected:

```text
Dataset: intent-routing/online-routing-regression
Items: 38
Dry run completed; no Langfuse writes were made.
```

- [ ] **Step 5: Sync to Langfuse**

Run:

```powershell
cd scripts/langfuse
$env:LANGFUSE_PUBLIC_KEY="pk-lf-..."
$env:LANGFUSE_SECRET_KEY="sk-lf-..."
$env:LANGFUSE_BASE_URL="https://cloud.langfuse.com"
$env:LANGFUSE_DATASET_NAME="intent-routing/online-routing-regression"
npm run sync:intent-routing
```

Expected:

```text
Synced 38 dataset items to intent-routing/online-routing-regression
```

- [ ] **Step 6: Document the migration command**

Create `scripts/langfuse/README.md`:

```markdown
# Langfuse Dataset Tools

## Sync intent routing cases

This syncs `ai-agent-study-app/src/test/resources/eval/intent-routing-online-cases.json`
into the Langfuse dataset `intent-routing/online-routing-regression`.

```powershell
cd scripts/langfuse
npm install
$env:LANGFUSE_PUBLIC_KEY="pk-lf-..."
$env:LANGFUSE_SECRET_KEY="sk-lf-..."
$env:LANGFUSE_BASE_URL="https://cloud.langfuse.com"
$env:LANGFUSE_DATASET_NAME="intent-routing/online-routing-regression"
npm run sync:intent-routing
```

Dry-run:

```powershell
cd scripts/langfuse
$env:LANGFUSE_PUBLIC_KEY="dry-run"
$env:LANGFUSE_SECRET_KEY="dry-run"
npm run sync:intent-routing -- --dry-run
```
```

- [ ] **Step 7: Commit**

Run:

```powershell
git add scripts/langfuse/package.json scripts/langfuse/package-lock.json scripts/langfuse/sync-intent-routing-dataset.mjs scripts/langfuse/README.md
git commit -m "chore: add langfuse intent routing dataset sync"
```

## Task 2: Add Java DTO, Client, and Mapper for Langfuse Dataset Items

**Files:**
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseDatasetItem.java`
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseDatasetClient.java`
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseIntentRoutingOnlineEvalCaseMapper.java`
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseIntentRoutingOnlineEvalCaseMapperTest.java`
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseDatasetClientTest.java`

- [ ] **Step 1: Write mapper test first**

Create `LangfuseIntentRoutingOnlineEvalCaseMapperTest.java` with a test that builds a `LangfuseDatasetItem` containing:

```json
{
  "id": "intent-routing-online/case-1",
  "input": {"query": "query", "historyMessages": ["user: before"]},
  "expectedOutput": {"multiTask": false, "needsClarification": false, "taskIntents": ["GENERAL_CHAT"]},
  "metadata": {
    "caseId": "case-1",
    "enabled": true,
    "suite": "smoke",
    "category": "single-task",
    "description": "case desc",
    "evaluation": {"runs": 2, "minPassRate": 1.0, "minConsistencyRate": 1.0},
    "tags": ["general"]
  }
}
```

Assert:

```java
assertEquals("case-1", mapped.getCaseId());
assertEquals("query", mapped.getInput().getQuery());
assertEquals(List.of("user: before"), mapped.getInput().getHistoryMessages());
assertEquals(List.of("GENERAL_CHAT"), mapped.getExpected().getTaskIntents());
assertEquals(2, mapped.getEvaluation().getRuns());
assertEquals(List.of("general"), mapped.getTags());
```

- [ ] **Step 2: Run mapper test and verify it fails**

Run:

```powershell
mvn -pl ai-agent-study-app -am "-Dtest=LangfuseIntentRoutingOnlineEvalCaseMapperTest" "-Dsurefire.failIfNoSpecifiedTests=false" test -q
```

Expected:

```text
Compilation failure: cannot find symbol LangfuseDatasetItem
```

- [ ] **Step 3: Add dataset item DTO**

Create `LangfuseDatasetItem.java`:

```java
package denny.ai.agent.test.eval.routing;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;

@Data
public class LangfuseDatasetItem {
    private String id;
    private JSONObject input;
    private JSONObject expectedOutput;
    private JSONObject metadata;
    private String status;
}
```

- [ ] **Step 4: Add mapper implementation**

Create `LangfuseIntentRoutingOnlineEvalCaseMapper.java` with these rules:

```java
package denny.ai.agent.test.eval.routing;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

public class LangfuseIntentRoutingOnlineEvalCaseMapper {

    public IntentRoutingOnlineEvalCase map(LangfuseDatasetItem item) {
        if (item == null) {
            throw new IllegalArgumentException("dataset item must not be null");
        }
        JSONObject metadata = requiredObject(item.getMetadata(), "metadata", item.getId());
        JSONObject input = requiredObject(item.getInput(), "input", item.getId());
        JSONObject expectedOutput = requiredObject(item.getExpectedOutput(), "expectedOutput", item.getId());

        IntentRoutingOnlineEvalCase c = new IntentRoutingOnlineEvalCase();
        c.setCaseId(requiredText(metadata.getString("caseId"), "metadata.caseId", item.getId()));
        c.setEnabled(!Boolean.FALSE.equals(metadata.getBoolean("enabled")));
        c.setSuite(requiredText(metadata.getString("suite"), "metadata.suite", item.getId()));
        c.setCategory(requiredText(metadata.getString("category"), "metadata.category", item.getId()));
        c.setDescription(metadata.getString("description"));
        c.setTags(metadata.getJSONArray("tags") == null
                ? java.util.List.of()
                : metadata.getJSONArray("tags").toJavaList(String.class));

        c.setInput(JSON.parseObject(input.toJSONString(), IntentRoutingOnlineEvalCase.Input.class));
        c.setExpected(JSON.parseObject(expectedOutput.toJSONString(), IntentRoutingOnlineEvalCase.Expected.class));

        JSONObject evaluation = requiredObject(metadata.getJSONObject("evaluation"),
                "metadata.evaluation", item.getId());
        c.setEvaluation(JSON.parseObject(evaluation.toJSONString(), IntentRoutingOnlineEvalCase.Evaluation.class));
        return c;
    }

    private JSONObject requiredObject(JSONObject value, String field, String itemId) {
        if (value == null) {
            throw new IllegalArgumentException(itemId + ": " + field + " is required");
        }
        return value;
    }

    private String requiredText(String value, String field, String itemId) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(itemId + ": " + field + " is required");
        }
        return value;
    }
}
```

- [ ] **Step 5: Add client test**

Create `LangfuseDatasetClientTest.java` using `com.sun.net.httpserver.HttpServer`.

Test response body:

```json
{
  "data": [
    {
      "id": "intent-routing-online/case-1",
      "status": "ACTIVE",
      "input": {"query": "query", "historyMessages": []},
      "expectedOutput": {"multiTask": false, "needsClarification": false, "taskIntents": ["GENERAL_CHAT"]},
      "metadata": {
        "caseId": "case-1",
        "enabled": true,
        "suite": "smoke",
        "category": "single-task",
        "description": "case desc",
        "evaluation": {"runs": 1, "minPassRate": 1.0, "minConsistencyRate": 1.0},
        "tags": ["general"]
      }
    }
  ],
  "meta": {"page": 1, "limit": 100, "totalItems": 1, "totalPages": 1}
}
```

Assert that the request includes Basic Auth and requests:

```text
/api/public/dataset-items?datasetName=intent-routing%2Fonline-routing-regression&page=1&limit=100
```

- [ ] **Step 6: Add client implementation**

Create `LangfuseDatasetClient.java`:

```java
package denny.ai.agent.test.eval.routing;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class LangfuseDatasetClient {

    private final String baseUrl;
    private final String publicKey;
    private final String secretKey;
    private final HttpClient httpClient;

    public LangfuseDatasetClient(String baseUrl, String publicKey, String secretKey) {
        this(baseUrl, publicKey, secretKey, HttpClient.newHttpClient());
    }

    LangfuseDatasetClient(String baseUrl, String publicKey, String secretKey, HttpClient httpClient) {
        this.baseUrl = requireText(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.publicKey = requireText(publicKey, "publicKey");
        this.secretKey = requireText(secretKey, "secretKey");
        this.httpClient = httpClient;
    }

    public List<LangfuseDatasetItem> listDatasetItems(String datasetName) {
        List<LangfuseDatasetItem> result = new ArrayList<>();
        int page = 1;
        int totalPages = 1;
        do {
            JSONObject body = getPage(datasetName, page);
            JSONArray data = body.getJSONArray("data");
            if (data != null) {
                result.addAll(data.toJavaList(LangfuseDatasetItem.class));
            }
            JSONObject meta = body.getJSONObject("meta");
            totalPages = meta == null ? page : Math.max(page, meta.getIntValue("totalPages"));
            page++;
        } while (page <= totalPages);
        return result;
    }

    private JSONObject getPage(String datasetName, int page) {
        try {
            String encoded = URLEncoder.encode(datasetName, StandardCharsets.UTF_8);
            URI uri = URI.create(baseUrl + "/api/public/dataset-items?datasetName="
                    + encoded + "&page=" + page + "&limit=100");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Basic " + basicAuth())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Langfuse dataset items request failed, status="
                        + response.statusCode() + ", body=" + response.body());
            }
            return JSON.parseObject(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch Langfuse dataset items", e);
        }
    }

    private String basicAuth() {
        String token = publicKey + ":" + secretKey;
        return Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
```

- [ ] **Step 7: Run focused tests**

Run:

```powershell
mvn -pl ai-agent-study-app -am "-Dtest=LangfuseIntentRoutingOnlineEvalCaseMapperTest,LangfuseDatasetClientTest" "-Dsurefire.failIfNoSpecifiedTests=false" test -q
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 8: Commit**

Run:

```powershell
git add ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseDatasetItem.java ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseDatasetClient.java ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseIntentRoutingOnlineEvalCaseMapper.java ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseIntentRoutingOnlineEvalCaseMapperTest.java ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseDatasetClientTest.java
git commit -m "test: add langfuse dataset item adapter"
```

## Task 3: Wire Langfuse Dataset Source into the Existing Loader

**Files:**
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseIntentRoutingOnlineEvalCaseLoader.java`
- Modify: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/IntentRoutingOnlineEvalCaseLoader.java`
- Modify: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/IntentRoutingOnlineEvalDatasetTest.java`

- [ ] **Step 1: Add remote loader**

Create `LangfuseIntentRoutingOnlineEvalCaseLoader.java`:

```java
package denny.ai.agent.test.eval.routing;

import java.util.List;

public class LangfuseIntentRoutingOnlineEvalCaseLoader {

    private final LangfuseDatasetClient client;
    private final LangfuseIntentRoutingOnlineEvalCaseMapper mapper;
    private final String datasetName;

    public LangfuseIntentRoutingOnlineEvalCaseLoader(LangfuseDatasetClient client,
                                                     LangfuseIntentRoutingOnlineEvalCaseMapper mapper,
                                                     String datasetName) {
        this.client = client;
        this.mapper = mapper;
        this.datasetName = datasetName;
    }

    public List<IntentRoutingOnlineEvalCase> loadAll() {
        return client.listDatasetItems(datasetName).stream()
                .filter(item -> item.getStatus() == null || "ACTIVE".equals(item.getStatus()))
                .map(mapper::map)
                .toList();
    }
}
```

- [ ] **Step 2: Modify local loader to preserve local default**

In `IntentRoutingOnlineEvalCaseLoader.java`, change `loadAll()` to:

```java
public List<IntentRoutingOnlineEvalCase> loadAll() {
    if (useLangfuse()) {
        LangfuseDatasetClient client = new LangfuseDatasetClient(
                setting("intent.routing.eval.langfuse.base-url", "LANGFUSE_BASE_URL", "https://cloud.langfuse.com"),
                setting("intent.routing.eval.langfuse.public-key", "LANGFUSE_PUBLIC_KEY", null),
                setting("intent.routing.eval.langfuse.secret-key", "LANGFUSE_SECRET_KEY", null));
        return new LangfuseIntentRoutingOnlineEvalCaseLoader(
                client,
                new LangfuseIntentRoutingOnlineEvalCaseMapper(),
                setting("intent.routing.eval.langfuse.dataset-name",
                        "INTENT_ROUTING_EVAL_LANGFUSE_DATASET_NAME",
                        "intent-routing/online-routing-regression"))
                .loadAll();
    }
    return loadLocalAll();
}
```

Move the current body of `loadAll()` into:

```java
List<IntentRoutingOnlineEvalCase> loadLocalAll() {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
        if (input == null) {
            throw new IllegalStateException("Missing online eval dataset: " + RESOURCE_PATH);
        }
        String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        List<IntentRoutingOnlineEvalCase> cases = JSON.parseArray(json, IntentRoutingOnlineEvalCase.class);
        return cases == null ? List.of() : cases;
    } catch (IOException e) {
        throw new IllegalStateException("Failed to read online eval dataset", e);
    }
}
```

Add helper methods:

```java
private boolean useLangfuse() {
    return Boolean.parseBoolean(setting(
            "intent.routing.eval.dataset-source.langfuse",
            "INTENT_ROUTING_EVAL_LANGFUSE_DATASET_SOURCE",
            "false"));
}

private String setting(String property, String environment, String defaultValue) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
        value = System.getenv(environment);
    }
    return value == null || value.isBlank() ? defaultValue : value;
}
```

- [ ] **Step 3: Keep runnable filtering unchanged**

Verify `loadRunnable(String suite, String tag)` still calls `loadAll()` and then applies:

```java
.filter(c -> !Boolean.FALSE.equals(c.getEnabled()))
.filter(c -> isBlank(suite) || suite.equals(c.getSuite()))
.filter(c -> isBlank(tag) || (c.getTags() != null && c.getTags().contains(tag)))
```

Expected behavior:

- No Langfuse env vars: local JSON behavior is identical.
- `INTENT_ROUTING_EVAL_LANGFUSE_DATASET_SOURCE=true`: remote dataset is used.

- [ ] **Step 4: Add source-neutral dataset validation test**

In `IntentRoutingOnlineEvalDatasetTest.java`, keep `datasetShouldBeValidAndMeetCoverageRequirements()` local-only by changing the loader call to:

```java
List<IntentRoutingOnlineEvalCase> cases = loader.loadLocalAll();
```

Add:

```java
@Test
public void configuredDatasetSourceShouldBeValid() {
    List<IntentRoutingOnlineEvalCase> cases = loader.loadAll();
    List<String> errors = loader.validate(cases);

    assertTrue(errors.isEmpty(), "Dataset validation failed:\n" + String.join("\n", errors));
}
```

- [ ] **Step 5: Run local regression tests**

Run:

```powershell
mvn -pl ai-agent-study-app -am "-Dtest=IntentRoutingOnlineEvalDatasetTest,IntentRoutingOnlineEvaluatorTest,LangfuseIntentRoutingOnlineEvalCaseMapperTest,LangfuseDatasetClientTest" "-Dsurefire.failIfNoSpecifiedTests=false" test -q
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Run remote dataset validation**

Run:

```powershell
$env:INTENT_ROUTING_EVAL_LANGFUSE_DATASET_SOURCE="true"
$env:INTENT_ROUTING_EVAL_LANGFUSE_DATASET_NAME="intent-routing/online-routing-regression"
$env:LANGFUSE_PUBLIC_KEY="pk-lf-..."
$env:LANGFUSE_SECRET_KEY="sk-lf-..."
$env:LANGFUSE_BASE_URL="https://cloud.langfuse.com"
mvn -pl ai-agent-study-app -am "-Dtest=IntentRoutingOnlineEvalDatasetTest#configuredDatasetSourceShouldBeValid" "-Dsurefire.failIfNoSpecifiedTests=false" test -q
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 7: Commit**

Run:

```powershell
git add ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/LangfuseIntentRoutingOnlineEvalCaseLoader.java ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/IntentRoutingOnlineEvalCaseLoader.java ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/IntentRoutingOnlineEvalDatasetTest.java
git commit -m "test: load intent routing eval cases from langfuse"
```

## Task 4: Run the Existing Online Evaluation Against Langfuse Dataset

**Files:**
- Modify only if needed after verification: `ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/IntentRoutingOnlineIntegrationTest.java`

- [ ] **Step 1: Run low-cost smoke suite from Langfuse**

Run:

```powershell
$env:INTENT_ROUTING_ONLINE_EVAL_ENABLED="true"
$env:INTENT_ROUTING_EVAL_LANGFUSE_DATASET_SOURCE="true"
$env:INTENT_ROUTING_EVAL_LANGFUSE_DATASET_NAME="intent-routing/online-routing-regression"
$env:LANGFUSE_PUBLIC_KEY="pk-lf-..."
$env:LANGFUSE_SECRET_KEY="sk-lf-..."
$env:LANGFUSE_BASE_URL="https://cloud.langfuse.com"
$env:INTENT_ROUTING_EVAL_SUITE="smoke"
$env:INTENT_ROUTING_EVAL_RUNS="1"
mvn -pl ai-agent-study-app -am -P integration "-Dit.test=IntentRoutingOnlineIntegrationTest" verify -q
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 2: Run challenge suite from Langfuse**

Run:

```powershell
$env:INTENT_ROUTING_ONLINE_EVAL_ENABLED="true"
$env:INTENT_ROUTING_EVAL_LANGFUSE_DATASET_SOURCE="true"
$env:INTENT_ROUTING_EVAL_LANGFUSE_DATASET_NAME="intent-routing/online-routing-regression"
$env:LANGFUSE_PUBLIC_KEY="pk-lf-..."
$env:LANGFUSE_SECRET_KEY="sk-lf-..."
$env:LANGFUSE_BASE_URL="https://cloud.langfuse.com"
$env:INTENT_ROUTING_EVAL_SUITE="challenge"
mvn -pl ai-agent-study-app -am -P integration "-Dit.test=IntentRoutingOnlineIntegrationTest" verify -q
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 3: Compare reports**

Open the report directory logged by `IntentRoutingOnlineIntegrationTest`.

Expected:

- `latest.json` exists.
- `latest.md` exists.
- `casePassRate`, `runAccuracy`, `formatErrorRate`, and `infrastructureErrorRate` are present.
- `failedCases=[]` for the stable suite.

- [ ] **Step 4: Commit only if integration test code changed**

Run only if `IntentRoutingOnlineIntegrationTest.java` was changed:

```powershell
git add ai-agent-study-app/src/test/java/denny/ai/agent/test/eval/routing/IntentRoutingOnlineIntegrationTest.java
git commit -m "test: run online routing eval from langfuse dataset"
```

## Task 5: Optional Follow-Up - Native Langfuse Experiment Runs

This task is optional and should be done after Task 4 is stable. It creates Langfuse dataset runs in the Langfuse UI. The Java evaluator can remain the local quality gate.

**Files:**
- Create: `experiments/intent-routing/package.json`
- Create: `experiments/intent-routing/experiment.ts`
- Create: `.github/workflows/langfuse-intent-routing-experiment.yml` if CI gating is wanted

- [ ] **Step 1: Add an experiment script that calls an application endpoint**

Use this only after the app exposes a test endpoint or CLI command that routes a single query and returns the same output shape as `RunResult`.

The task input is Langfuse dataset item `input`; the evaluator compares output to `expectedOutput`.

- [ ] **Step 2: Add item-level evaluator**

The evaluator returns:

```typescript
{
  name: "routing_correct",
  value: passed ? 1 : 0,
  comment: signature
}
```

- [ ] **Step 3: Add run-level evaluator**

The run evaluator returns aggregate scores:

```typescript
[
  { name: "case_pass_rate", value: casePassRate },
  { name: "run_accuracy", value: runAccuracy },
  { name: "format_error_rate", value: formatErrorRate },
  { name: "infrastructure_error_rate", value: infrastructureErrorRate }
]
```

- [ ] **Step 4: Add CI only after local experiment succeeds**

Use Langfuse's CI/CD experiment action only after the dataset source and local online evaluator have passed at least once.

## Final Verification

Run:

```powershell
mvn -pl ai-agent-study-app -am "-Dtest=IntentRoutingOnlineEvalDatasetTest,IntentRoutingOnlineEvaluatorTest,LangfuseIntentRoutingOnlineEvalCaseMapperTest,LangfuseDatasetClientTest" "-Dsurefire.failIfNoSpecifiedTests=false" test -q
mvn clean compile -q
```

Expected:

```text
BUILD SUCCESS
```

For real online validation:

```powershell
$env:INTENT_ROUTING_ONLINE_EVAL_ENABLED="true"
$env:INTENT_ROUTING_EVAL_LANGFUSE_DATASET_SOURCE="true"
$env:INTENT_ROUTING_EVAL_LANGFUSE_DATASET_NAME="intent-routing/online-routing-regression"
$env:LANGFUSE_PUBLIC_KEY="pk-lf-..."
$env:LANGFUSE_SECRET_KEY="sk-lf-..."
$env:LANGFUSE_BASE_URL="https://cloud.langfuse.com"
$env:INTENT_ROUTING_EVAL_SUITE="smoke"
$env:INTENT_ROUTING_EVAL_RUNS="1"
mvn -pl ai-agent-study-app -am -P integration "-Dit.test=IntentRoutingOnlineIntegrationTest" verify -q
```

Expected:

```text
BUILD SUCCESS
```

## Rollback

Unset this environment variable to return to local JSON immediately:

```powershell
Remove-Item Env:INTENT_ROUTING_EVAL_LANGFUSE_DATASET_SOURCE
```

Local JSON remains in the repository until Langfuse has been stable for several runs. After that, convert the local file into a small fixture snapshot or remove it in a separate cleanup change.

## Self-Review

- Spec coverage: The plan migrates repo cases to Langfuse, keeps current Java evaluator, adds remote dataset loading, preserves local fallback, and includes verification commands.
- Placeholder scan: The plan avoids TBD and gives concrete file paths, commands, and implementation snippets.
- Type consistency: The plan consistently uses `input`, `expectedOutput`, `metadata.evaluation`, `IntentRoutingOnlineEvalCase`, `LangfuseDatasetItem`, and existing loader/evaluator names.
