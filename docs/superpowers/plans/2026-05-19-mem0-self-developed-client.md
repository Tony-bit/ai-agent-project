# Mem0 自研 HTTP Client 替换方案

**Metadata:**
- 状态: draft
- 预估工时: 4h
- 日期: 2026-05-19

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用自研的轻量 HTTP Client 替换 `spring-ai-alibaba-starter-memory-mem0` 包，实现 Spring AI 版本解绑。

**Architecture:** 在 `ai-agent-study-app` 模块新建 `Mem0RestClient` 类，使用 `RestTemplate` 调用远端 Mem0 REST API（`http://127.0.0.1:8889`）。新 Client 保持与原 `Mem0ServiceClient` 相同的方法签名，现有 3 个调用方只需替换注入类型和 import 语句，无需改动业务逻辑。删除两处 pom.xml 依赖。

<details>
<summary><strong>Background (点击展开)</strong></summary>

- **问题现象:** `spring-ai-alibaba-starter-memory-mem0` 限制了 Spring AI 版本为 1.1.2，无法升级到 1.1.6+
- **根因分析:** 该包声明了与特定 Spring AI 版本强绑定的传递依赖。业务中真正使用到的仅是其 HTTP Client 功能（`addMemory`、`searchMemories`、`getAllMemories`），完全不需要 Spring AI 的接口
- **方案选型:**
  - 备选1：等官方升级包 → 被动，依赖外部节奏
  - 备选2：直接自研 Client → 主动，完全控制版本，代码量仅 1 个类 + 若干 DTO
  - **选择备选2**，改造成本可控，收益明确

</details>

**Tech Stack:** RestTemplate + Jackson（已有），无新增外部依赖

**Mem0 REST API 端点（远端服务: http://127.0.0.1:8889）:**

| 方法 | 端点 | 用途 |
|---|---|---|
| POST | `/memories` | addMemory |
| GET | `/memories?user_id=xxx&agent_id=xxx` | getAllMemories |
| POST | `/search` | searchMemories |

**执行顺序:** Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6 → Task 6.5 → Task 7

---

## 依赖分析

```
spring-ai-alibaba-starter-memory-mem0 在项目中出现的位置：

根 pom.xml (parent, version=1.1.2.2):
  └── ai-agent-study-domain/pom.xml (imports parent)

ai-agent-study-domain/pom.xml:
  └── spring-ai-alibaba-starter-memory-mem0  ← 删除此依赖

实际使用方（3 个文件）:
  1. ai-agent-study-app/.../config/AiAgentConfig.java
        → 创建 Mem0Client / Mem0Server / Mem0ServiceClient Bean
  2. ai-agent-study-app/.../service/ChatSessionMemorySyncService.java
        → @Resource Mem0ServiceClient，调用 addMemory()
  3. ai-agent-study-infrastructure/.../crossmemory/CrossSessionMemoryCacheServiceImpl.java
        → @Resource Mem0ServiceClient，调用 searchMemories()
  4. ai-agent-study-trigger/.../Mem0MemoryController.java
        → 构造函数注入 Mem0ServiceClient，调用 addMemory() / getAllMemories() / searchMemories()
```

---

## Task 1: 创建 Mem0Request / Mem0Response DTO

> **前置条件:** 无

**Files:**
- Create: `ai-agent-study-app/src/main/java/denny/ai/agent/config/mem0/dto/Mem0Dtos.java`

**Mem0Dtos.java** 包含所有 Request/Response 内部类，完全复用现有代码中的嵌套类命名风格：

```java
package denny.ai.agent.config.mem0.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Mem0 REST API 请求与响应 DTO
 */
public class Mem0Dtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;
        private String content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MemoryCreateRequest {
        private List<Message> messages;
        private String user_id;
        private String agent_id;
        private String run_id;
        private Map<String, Object> metadata;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SearchRequest {
        private String query;
        private String user_id;
        private String run_id;
        private String agent_id;
        private Map<String, Object> filters;
        private Integer limit;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResponse {
        private List<Mem0Result> results;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Mem0Result {
        private String memory;
        private Map<String, Object> metadata;
        private Double score;
    }
}
```

- [ ] **Step 1: 创建 Mem0Dtos.java 文件**

```bash
mkdir -p ai-agent-study-app/src/main/java/denny/ai/agent/config/mem0/dto
touch ai-agent-study-app/src/main/java/denny/ai/agent/config/mem0/dto/Mem0Dtos.java
```

- [ ] **Step 2: 写入上述 DTO 代码**

> 验收标准: 文件存在，5 个内部类（Message, MemoryCreateRequest, SearchRequest, SearchResponse, Mem0Result）全部包含，package 正确

- [ ] **Step 3: 编译验证**

```bash
cd ai-agent-study-app && mvn compile -pl . -q
```

> 验收标准: exit code = 0，无编译错误

- [ ] **Step 4: Commit**

```bash
git add ai-agent-study-app/src/main/java/denny/ai/agent/config/mem0/dto/Mem0Dtos.java
git commit -m "feat: add Mem0 REST API DTOs"
```

---

## Task 2: 创建 Mem0RestClient 类

> **前置条件:** Task 1 完成并提交

**Files:**
- Create: `ai-agent-study-app/src/main/java/denny/ai/agent/config/mem0/Mem0RestClient.java`
- Modify: `ai-agent-study-app/src/main/java/denny/ai/agent/config/AiAgentConfig.java:76-97`（替换 Bean 定义）

**核心设计原则:** 新 Client 的方法签名与原 `Mem0ServiceClient` 保持一致（参数名/类型/返回值均对齐），使调用方无需改动调用代码，只需替换注入类型和 import。

原 `Mem0ServiceClient` 方法签名（需对齐）:

```java
// addMemory - 原实现 void addMemory(MemoryCreate request)
public void addMemory(MemoryCreate request) {
    // POST /memories, body: {messages, user_id, agent_id, run_id, metadata}
}

// searchMemories - 原实现 Mem0ServerResp searchMemories(SearchRequest request)
public Mem0ServerResp searchMemories(SearchRequest request) {
    // POST /search, body: {query, user_id, run_id, agent_id, filters, limit}
    // 返回 Mem0ServerResp { List<Mem0Results> results }
}

// getAllMemories - 原实现 Object getAllMemories(String userId, String agentId, String runId)
public Object getAllMemories(String userId, String agentId, String runId) {
    // GET /memories?user_id=xxx&agent_id=xxx&run_id=xxx
}
```

**Mem0RestClient.java:**

```java
package denny.ai.agent.config.mem0;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.config.mem0.dto.Mem0Dtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自研 Mem0 HTTP Client，替换 spring-ai-alibaba-starter-memory-mem0 的 Mem0ServiceClient。
 * <p>
 * 直接调用 Mem0 REST API（http://127.0.0.1:8889），无任何 Spring AI 依赖。
 * </p>
 */
@Slf4j
public class Mem0RestClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final ObjectMapper objectMapper;

    public Mem0RestClient(RestTemplate restTemplate, String baseUrl, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入记忆到 Mem0
     * 对应原 Mem0ServiceClient.addMemory(MemoryCreate)
     */
    @SuppressWarnings("unchecked")
    public void addMemory(MemoryCreate request) {
        Map<String, Object> body = new HashMap<>();
        if (request.getMessages() != null) {
            body.put("messages", request.getMessages());
        }
        if (request.getUser_id() != null) body.put("user_id", request.getUser_id());
        if (request.getAgent_id() != null) body.put("agent_id", request.getAgent_id());
        if (request.getRun_id() != null) body.put("run_id", request.getRun_id());
        if (request.getMetadata() != null) body.put("metadata", request.getMetadata());

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body);
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    baseUrl + "/memories",
                    HttpMethod.POST,
                    entity,
                    new TypeReference<Map<String, Object>>() {}
            );
            log.info("Mem0 addMemory 成功, userId={}", request.getUser_id());
        } catch (HttpClientErrorException e) {
            log.error("Mem0 addMemory 失败, userId={}, status={}", request.getUser_id(), e.getStatusCode());
            throw new RuntimeException("Mem0 addMemory failed: " + e.getStatusCode(), e);
        }
    }

    /**
     * 语义搜索记忆
     * 对应原 Mem0ServiceClient.searchMemories(SearchRequest)
     */
    public Mem0ServerResp searchMemories(SearchRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("query", request.getQuery());
        if (request.getUser_id() != null) body.put("user_id", request.getUser_id());
        if (request.getRun_id() != null) body.put("run_id", request.getRun_id());
        if (request.getAgent_id() != null) body.put("agent_id", request.getAgent_id());
        if (request.getFilters() != null) body.put("filters", request.getFilters());
        if (request.getLimit() != null) body.put("limit", request.getLimit());

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body);
            ResponseEntity<Mem0Dtos.SearchResponse> resp = restTemplate.exchange(
                    baseUrl + "/search",
                    HttpMethod.POST,
                    entity,
                    Mem0Dtos.SearchResponse.class
            );
            return Mem0ServerResp.fromSearchResponse(resp.getBody());
        } catch (HttpClientErrorException e) {
            log.error("Mem0 searchMemories 失败, userId={}, status={}", request.getUser_id(), e.getStatusCode());
            throw new RuntimeException("Mem0 searchMemories failed: " + e.getStatusCode(), e);
        }
    }

    /**
     * 查询用户所有记忆
     * 对应原 Mem0ServiceClient.getAllMemories(String, String, String)
     */
    public Object getAllMemories(String userId, String agentId, String runId) {
        StringBuilder url = new StringBuilder(baseUrl).append("/memories?");
        if (userId != null) url.append("user_id=").append(userId).append("&");
        if (agentId != null) url.append("agent_id=").append(agentId).append("&");
        if (runId != null) url.append("run_id=").append(runId).append("&");
        String urlStr = url.toString().replaceAll("[&?]$", "");

        try {
            ResponseEntity<Object> resp = restTemplate.getForEntity(urlStr, Object.class);
            return resp.getBody();
        } catch (HttpClientErrorException e) {
            log.error("Mem0 getAllMemories 失败, userId={}, status={}", userId, e.getStatusCode());
            throw new RuntimeException("Mem0 getAllMemories failed: " + e.getStatusCode(), e);
        }
    }

    // ========== 内部类，与原 Mem0ServiceClient 签名对齐 ==========

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MemoryCreate {
        private List<Mem0Dtos.Message> messages;
        private String user_id;
        private String agent_id;
        private String run_id;
        private Map<String, Object> metadata;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SearchRequest {
        private String query;
        private String user_id;
        private String run_id;
        private String agent_id;
        private Map<String, Object> filters;
        private Integer limit;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Mem0ServerResp {
        private List<Mem0Results> results;

        public List<Mem0Results> getResults() {
            return results;
        }

        @lombok.Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class Mem0Results {
            private String memory;
            private Map<String, Object> metadata;
            private Double score;
        }

        public static Mem0ServerResp fromSearchResponse(Mem0Dtos.SearchResponse resp) {
            if (resp == null || resp.getResults() == null) {
                return new Mem0ServerResp(Collections.emptyList());
            }
            List<Mem0Results> results = resp.getResults().stream()
                    .map(r -> new Mem0Results(r.getMemory(), r.getMetadata(), r.getScore()))
                    .toList();
            return new Mem0ServerResp(results);
        }
    }
}
```

> **注意:** 原代码中 `Mem0ServerRequest.MemoryCreate` 构造时使用 builder pattern，字段名为 camelCase（`userId`、`agentId`、`runId`）。新 Client 内部类统一用下划线风格（`user_id`、`agent_id`、`run_id`）以匹配 Mem0 REST API JSON 格式，内部做转换。

- [ ] **Step 1: 创建 Mem0RestClient.java**

```bash
touch ai-agent-study-app/src/main/java/denny/ai/agent/config/mem0/Mem0RestClient.java
```

- [ ] **Step 2: 写入 Mem0RestClient.java 代码**

> 验收标准: 文件存在，addMemory / searchMemories / getAllMemories 三个方法全部存在，方法签名与原接口对齐

- [ ] **Step 3: 替换 AiAgentConfig.java 中 Mem0 Bean 定义**

原 AiAgentConfig.java 第 76-97 行 Bean 定义（Mem0Client / Mem0Server / Mem0ServiceClient）删除，新增 RestTemplate Bean 和 Mem0RestClient Bean：

替换 `AiAgentConfig.java` 中以下代码块（第 76-97 行）:

```java
// ========== 原代码：spring-ai-alibaba 依赖的 Mem0 Client ==========

@Bean
public Mem0Client mem0Client(
        @Value("${spring.ai.alibaba.mem0.client.base-url}") String baseUrl,
        @Value("${spring.ai.alibaba.mem0.client.timeout-seconds:120}") int timeoutSeconds) {
    return Mem0Client.builder()
            .baseUrl(baseUrl)
            .timeoutSeconds(timeoutSeconds)
            .build();
}

@Bean
public Mem0Server mem0Server(
        @Value("${spring.ai.alibaba.mem0.server.version}") String version) {
    return Mem0Server.builder()
            .version(version)
            .build();
}

@Bean
public Mem0ServiceClient mem0ServiceClient(Mem0Client mem0Client, Mem0Server mem0Server) {
    return new Mem0ServiceClient(mem0Client, mem0Server, new DefaultResourceLoader());
}
```

替换为（正确写法，避免循环依赖）:

```java
// ========== 自研 Mem0RestClient（无 Spring AI 依赖）==========
import denny.ai.agent.config.mem0.Mem0RestClient;
import org.springframework.web.client.RestTemplate;

// 删除 spring-ai-alibaba 依赖的 import（Mem0Client / Mem0Server / Mem0ServiceClient）
// 删除 DefaultResourceLoader import（不再需要）

@Bean
public RestTemplate mem0RestTemplate(
        @Value("${spring.ai.alibaba.mem0.client.timeout-seconds:120}") int timeoutSeconds) {
    RestTemplate rt = new RestTemplate();
    rt.getInterceptors().add((request, body, execution) -> {
        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return execution.execute(request, body);
    });
    return rt;
}

@Bean
public Mem0RestClient mem0RestClient(
        @Value("${spring.ai.alibaba.mem0.client.base-url}") String baseUrl,
        RestTemplate mem0RestTemplate) {
    return new Mem0RestClient(mem0RestTemplate, baseUrl, new ObjectMapper());
}
```

> **注意:** `mem0RestTemplate` Bean 仅注入 timeoutSeconds（baseUrl 在 `mem0RestClient` 中单独注入）；`mem0RestClient` 通过方法参数注入已有 `mem0RestTemplate` Bean，避免循环依赖。错误写法示例（勿用）：直接调用 `mem0RestTemplate(null, 120)` 会导致 NPE。

同时删除 AiAgentConfig.java 文件头部的三个 import：
```java
// 删除
import com.alibaba.cloud.ai.memory.mem0.core.Mem0Client;
import com.alibaba.cloud.ai.memory.mem0.core.Mem0Server;
import com.alibaba.cloud.ai.memory.mem0.core.Mem0ServiceClient;
// 删除
import org.springframework.core.io.DefaultResourceLoader;
```

并新增：
```java
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
```

> **重要:** `AiAgentConfig.mem0RestTemplate()` 依赖自身的 Bean（循环），需要拆成两个方法。`mem0RestClient` Bean 方法中直接 new `RestTemplate`，或者将 `RestTemplate` 单独声明为一个 `@Bean` 方法。参考实现：

```java
@Bean
public RestTemplate mem0RestTemplate(
        @Value("${spring.ai.alibaba.mem0.client.timeout-seconds:120}") int timeoutSeconds) {
    RestTemplate rt = new RestTemplate();
    rt.getInterceptors().add((request, body, execution) -> {
        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return execution.execute(request, body);
    });
    return rt;
}

@Bean
public Mem0RestClient mem0RestClient(
        @Value("${spring.ai.alibaba.mem0.client.base-url}") String baseUrl,
        RestTemplate mem0RestTemplate) {
    return new Mem0RestClient(mem0RestTemplate, baseUrl, new ObjectMapper());
}
```

- [ ] **Step 4: 编译验证**

```bash
cd ai-agent-study-app && mvn compile -pl . -q
```

> 验收标准: exit code = 0，无编译错误（Mem0RestClient、Mem0Dtos 编译通过，AiAgentConfig 替换后编译通过）

- [ ] **Step 5: Commit**

```bash
git add ai-agent-study-app/src/main/java/denny/ai/agent/config/mem0/
git add ai-agent-study-app/src/main/java/denny/ai/agent/config/AiAgentConfig.java
git commit -m "feat: replace spring-ai-alibaba Mem0ServiceClient with self-developed Mem0RestClient"
```

---

## Task 3: 替换 ChatSessionMemorySyncService 注入

> **前置条件:** Task 2 完成并提交

**Files:**
- Modify: `ai-agent-study-app/src/main/java/denny/ai/agent/infrastructure/service/ChatSessionMemorySyncService.java`

**改动说明:** 将 `Mem0ServiceClient` 替换为 `Mem0RestClient`，同时将原 builder 风格的 `Mem0ServerRequest.MemoryCreate` 替换为新 Client 的 `Mem0RestClient.MemoryCreate`。

**具体改动:**

1. 删除 import:
```java
import com.alibaba.cloud.ai.memory.mem0.core.Mem0ServiceClient;
import com.alibaba.cloud.ai.memory.mem0.model.Mem0ServerRequest;
```

2. 新增 import:
```java
import denny.ai.agent.config.mem0.Mem0RestClient;
```

3. 字段声明替换:
```java
// 原
@Resource
private Mem0ServiceClient mem0ServiceClient;

// 改
@Resource
private Mem0RestClient mem0RestClient;
```

4. 方法调用替换（syncSessionToMemory 方法内，第 51-69 行）:

原代码:
```java
List<Mem0ServerRequest.Message> messages = unsyncedSessions.stream()
        .flatMap(session -> {
            Mem0ServerRequest.Message userMsg = new Mem0ServerRequest.Message("user", session.getFirstQuery());
            Mem0ServerRequest.Message assistantMsg = new Mem0ServerRequest.Message("assistant", session.getLastResponse());
            return Stream.of(userMsg, assistantMsg);
        })
        .collect(Collectors.toList());

mem0ServiceClient.addMemory(
        Mem0ServerRequest.MemoryCreate.builder()
                .userId(userId)
                .agentId(agentId)
                .runId(sessionId)
                .messages(messages)
                .build()
);
```

替换为:
```java
List<Mem0RestClient.MemoryCreate.Message> messages = unsyncedSessions.stream()
        .flatMap(session -> {
            Mem0RestClient.MemoryCreate.Message userMsg =
                    new Mem0RestClient.MemoryCreate.Message("user", session.getFirstQuery());
            Mem0RestClient.MemoryCreate.Message assistantMsg =
                    new Mem0RestClient.MemoryCreate.Message("assistant", session.getLastResponse());
            return Stream.of(userMsg, assistantMsg);
        })
        .collect(Collectors.toList());

mem0RestClient.addMemory(
        Mem0RestClient.MemoryCreate.builder()
                .user_id(userId)
                .agent_id(agentId)
                .run_id(sessionId)
                .messages(messages)
                .build()
);
```

注意: 原 `Mem0ServerRequest.Message` 和 `Mem0ServerRequest.MemoryCreate.builder()` 的字段名是 camelCase（`userId`），新 `Mem0RestClient.MemoryCreate` 内部字段名也是 `user_id`（下划线）。

> **注意:** `agent_id` 字段虽然原调用代码中查了但未显式使用（`String agentId = unsyncedSessions.get(0).getAgentId()` 后直接传入 builder），但仍需在 builder 中传递，与 Mem0 REST API 字段对齐。

- [ ] **Step 1: 执行上述 import 和代码替换**
- [ ] **Step 2: 编译验证**

```bash
cd ai-agent-study-app && mvn compile -pl . -q
```

> 验收标准: exit code = 0，无编译错误

- [ ] **Step 3: Commit**

```bash
git add ai-agent-study-app/src/main/java/denny/ai/agent/infrastructure/service/ChatSessionMemorySyncService.java
git commit -m "refactor: replace Mem0ServiceClient with Mem0RestClient in ChatSessionMemorySyncService"
```

---

## Task 4: 替换 CrossSessionMemoryCacheServiceImpl 注入

> **前置条件:** Task 2 完成并提交

**Files:**
- Modify: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/crossmemory/CrossSessionMemoryCacheServiceImpl.java`

**具体改动:**

1. 删除 import:
```java
import com.alibaba.cloud.ai.memory.mem0.core.Mem0ServiceClient;
import com.alibaba.cloud.ai.memory.mem0.model.Mem0ServerRequest;
import com.alibaba.cloud.ai.memory.mem0.model.Mem0ServerResp;
```

2. 新增 import:
```java
import denny.ai.agent.config.mem0.Mem0RestClient;
```

3. 字段声明替换:
```java
// 原
@Resource
private Mem0ServiceClient mem0ServiceClient;

// 改
@Resource
private Mem0RestClient mem0RestClient;
```

4. queryFromMem0 方法替换（第 85-98 行）:

原代码:
```java
private String queryFromMem0(String userId) {
    try {
        Mem0ServerRequest.SearchRequest searchRequest = Mem0ServerRequest.SearchRequest.mem0Builder()
                .query("用户相关信息和偏好")
                .userId(userId)
                .topK(crossSessionMemoryProperties.getCrossSessionMemoryTopK())
                .build();
        Mem0ServerResp resp = mem0ServiceClient.searchMemories(searchRequest);
        return formatMem0Result(resp);
    } catch (Exception e) {
        log.warn("Mem0 查询跨会话记忆失败，降级返回空, userId={}, error={}", userId, e.getMessage());
        return "";
    }
}
```

替换为:
```java
private String queryFromMem0(String userId) {
    try {
        Mem0RestClient.SearchRequest searchRequest = Mem0RestClient.SearchRequest.builder()
                .query("用户相关信息和偏好")
                .user_id(userId)
                .limit(crossSessionMemoryProperties.getCrossSessionMemoryTopK())
                .build();
        Mem0RestClient.Mem0ServerResp resp = mem0RestClient.searchMemories(searchRequest);
        return formatMem0Result(resp);
    } catch (Exception e) {
        log.warn("Mem0 查询跨会话记忆失败，降级返回空, userId={}, error={}", userId, e.getMessage());
        return "";
    }
}
```

5. formatMem0Result 方法签名替换（第 103-117 行）:

原代码:
```java
private String formatMem0Result(Mem0ServerResp resp) {
    if (resp == null || resp.getResults() == null || resp.getResults().isEmpty()) {
        return "";
    }
    StringBuilder sb = new StringBuilder("\n\n[用户跨会话长期记忆]\n");
    int i = 1;
    for (Mem0ServerResp.Mem0Results item : resp.getResults()) {
        sb.append(i++).append(". ").append(item.getMemory());
        if (item.getMetadata() != null && !item.getMetadata().isEmpty()) {
            sb.append(" (metadata: ").append(item.getMetadata()).append(")");
        }
        sb.append("\n");
    }
    return sb.toString();
}
```

替换为:
```java
private String formatMem0Result(Mem0RestClient.Mem0ServerResp resp) {
    if (resp == null || resp.getResults() == null || resp.getResults().isEmpty()) {
        return "";
    }
    StringBuilder sb = new StringBuilder("\n\n[用户跨会话长期记忆]\n");
    int i = 1;
    for (Mem0RestClient.Mem0ServerResp.Mem0Results item : resp.getResults()) {
        sb.append(i++).append(". ").append(item.getMemory());
        if (item.getMetadata() != null && !item.getMetadata().isEmpty()) {
            sb.append(" (metadata: ").append(item.getMetadata()).append(")");
        }
        sb.append("\n");
    }
    return sb.toString();
}
```

- [ ] **Step 1: 执行上述 import 和代码替换**
- [ ] **Step 2: 编译验证**

```bash
cd ai-agent-study-infrastructure && mvn compile -pl ai-agent-study-infrastructure -q
```

> 验收标准: exit code = 0，无编译错误；**特别注意：`SearchRequest.builder().limit()` 必须设置（原调用方传了 `topK`，对应 limit，不可省略）**

- [ ] **Step 3: Commit**

```bash
git add ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/crossmemory/CrossSessionMemoryCacheServiceImpl.java
git commit -m "refactor: replace Mem0ServiceClient with Mem0RestClient in CrossSessionMemoryCacheServiceImpl"
```

---

## Task 5: 替换 Mem0MemoryController 注入

> **前置条件:** Task 2 完成并提交

**Files:**
- Modify: `ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/Mem0MemoryController.java`

**具体改动:**

1. 删除 import:
```java
import com.alibaba.cloud.ai.memory.mem0.core.Mem0ServiceClient;
import com.alibaba.cloud.ai.memory.mem0.model.Mem0ServerRequest;
import com.alibaba.cloud.ai.memory.mem0.model.Mem0ServerResp;
```

2. 新增 import:
```java
import denny.ai.agent.config.mem0.Mem0RestClient;
```

3. 构造函数替换:
```java
// 原
private final Mem0ServiceClient mem0ServiceClient;
public Mem0MemoryController(Mem0ServiceClient mem0ServiceClient) {
    this.mem0ServiceClient = mem0ServiceClient;
}

// 改
private final Mem0RestClient mem0RestClient;
public Mem0MemoryController(Mem0RestClient mem0RestClient) {
    this.mem0RestClient = mem0RestClient;
}
```

4. @ConditionalOnBean 替换:
```java
// 原
@ConditionalOnBean(Mem0ServiceClient.class)
// 改
@ConditionalOnBean(Mem0RestClient.class)
```

5. addMemory 方法（第 46-58 行）替换:
```java
// 原
mem0ServiceClient.addMemory(
        Mem0ServerRequest.MemoryCreate.builder()
                .userId(request.getUserId())
                .agentId(request.getAgentId())
                .messages(List.of(new Mem0ServerRequest.Message("user", request.getContent())))
                .build()
);

// 改
mem0RestClient.addMemory(
        Mem0RestClient.MemoryCreate.builder()
                .user_id(request.getUserId())
                .agent_id(request.getAgentId())
                .messages(List.of(new Mem0RestClient.MemoryCreate.Message("user", request.getContent())))
                .build()
);
```

6. getMemories 方法（第 63-75 行）替换:
```java
// 原
Object resp = mem0ServiceClient.getAllMemories(userId, agentId, null);

// 改
Object resp = mem0RestClient.getAllMemories(userId, agentId, null);
```

7. searchMemory 方法（第 80-95 行）替换:
```java
// 原
Mem0ServerRequest.SearchRequest request = Mem0ServerRequest.SearchRequest.mem0Builder()
        .query(query)
        .userId(userId)
        .runId(sessionId != null && !sessionId.isBlank() ? sessionId : null)
        .topK(limit)
        .build();
Mem0ServerResp result = mem0ServiceClient.searchMemories(request);
return Response.ok(result);

// 改
Mem0RestClient.SearchRequest request = Mem0RestClient.SearchRequest.builder()
        .query(query)
        .user_id(userId)
        .run_id(sessionId != null && !sessionId.isBlank() ? sessionId : null)
        .limit(limit)
        .build();
Mem0RestClient.Mem0ServerResp result = mem0RestClient.searchMemories(request);
return Response.ok(result);
```

- [ ] **Step 1: 执行上述 import 和代码替换**
- [ ] **Step 2: 编译验证**

```bash
cd ai-agent-study-trigger && mvn compile -pl ai-agent-study-trigger -q
```

> 验收标准: exit code = 0，无编译错误；**特别注意：`SearchRequest.builder().limit(limit)` 必须设置（原调用方传了 `topK`，对应 limit，不可省略）**

- [ ] **Step 3: Commit**

```bash
git add ai-agent-study-trigger/src/main/java/denny/ai/agent/trigger/http/Mem0MemoryController.java
git commit -m "refactor: replace Mem0ServiceClient with Mem0RestClient in Mem0MemoryController"
```

---

## Task 6: 删除 pom.xml 依赖

> **前置条件:** Task 3, 4, 5 全部完成并提交

**Files:**
- Modify: `pom.xml`（根 pom）
- Modify: `ai-agent-study-domain/pom.xml`

**pom.xml 第 129-134 行（根 pom）:**

删除:
```xml
<!-- Spring AI Alibaba -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-memory-mem0</artifactId>
    <version>1.1.2.2</version>
</dependency>
```

**ai-agent-study-domain/pom.xml 第 84-88 行:**

删除:
```xml
<!-- Mem0 记忆组件 -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-memory-mem0</artifactId>
</dependency>
```

- [ ] **Step 1: 删除根 pom.xml 中依赖**

- [ ] **Step 2: 删除 ai-agent-study-domain/pom.xml 中依赖**

- [ ] **Step 3: 全量编译验证**

```bash
cd ai-agent-study && mvn compile -q
```

> 验收标准: exit code = 0，spring-ai-alibaba-starter-memory-mem0 不在 classpath 中，全项目编译通过

- [ ] **Step 4: Commit**

```bash
git add pom.xml ai-agent-study-domain/pom.xml
git commit -m "chore: remove spring-ai-alibaba-starter-memory-mem0 dependency"
```

---

## Task 6.5: 现有集成测试验证

> **前置条件:** Task 6 完成并提交，全项目编译通过，Mem0 Server 在线（localhost:8889）

**说明:** 验证 `Mem0MemoryControllerTest` 集成测试在 Bean 替换后能正常通过。该测试通过 HTTP 层覆盖 `addMemory`、`getMemories`、`searchMemories` 完整链路，是最核心的端到端验证。

**Files:**
- Test: `ai-agent-study-app/src/test/java/denny/ai/agent/test/trigger/http/Mem0MemoryControllerTest.java`

- [ ] **Step 1: 确认 Mem0 Server 在线**

```bash
curl -s http://127.0.0.1:8889/ -w "%{http_code}" -o /dev/null
```

> 验收标准: 输出 `200`

- [ ] **Step 2: 运行现有集成测试**

```bash
cd ai-agent-study-app && mvn test -Dtest=Mem0MemoryControllerTest -q
```

> 验收标准: exit code = 0，输出包含 `BUILD SUCCESS`，5 个测试全部 PASSED（testAddAndGetMemory、testGetMemories_NoData、testSearchMemory、testSearchMemory_WithSessionId、testConfigure）

- [ ] **Step 3: 无需 commit（验证步骤，不改代码）**

---

## Task 7: 单元测试验证

> **前置条件:** Task 6 完成并编译通过

**Files:**
- Create: `ai-agent-study-app/src/test/java/denny/ai/agent/config/mem0/Mem0RestClientTest.java`

**Mem0RestClientTest.java:**

```java
package denny.ai.agent.config.mem0;

import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.config.mem0.dto.Mem0Dtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class Mem0RestClientTest {

    private Mem0RestClient client;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        client = new Mem0RestClient(restTemplate, "http://localhost:8889", new ObjectMapper());
    }

    @Test
    void addMemory_shouldSendCorrectRequest() {
        mockServer.expect(requestTo("http://localhost:8889/memories"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withStatus(HttpStatus.OK));

        Mem0RestClient.MemoryCreate mc = Mem0RestClient.MemoryCreate.builder()
                .user_id("user-001")
                .agent_id("agent-001")
                .run_id("session-001")
                .messages(List.of(new Mem0Dtos.Message("user", "你好")))
                .build();

        assertDoesNotThrow(() -> client.addMemory(mc));
        mockServer.verify();
    }

    @Test
    void searchMemories_shouldReturnFormattedResults() {
        String json = """
            {"results": [
                {"memory": "用户喜欢喝咖啡", "metadata": {"source": "chat"}, "score": 0.95}
            ]}
            """;
        mockServer.expect(requestTo("http://localhost:8889/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"query\":\"用户信息\"}"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        Mem0RestClient.SearchRequest req = Mem0RestClient.SearchRequest.builder()
                .query("用户信息")
                .user_id("user-001")
                .limit(10)
                .build();

        Mem0RestClient.Mem0ServerResp resp = client.searchMemories(req);

        assertNotNull(resp);
        assertEquals(1, resp.getResults().size());
        assertEquals("用户喜欢喝咖啡", resp.getResults().get(0).getMemory());
        assertEquals(0.95, resp.getResults().get(0).getScore());
        mockServer.verify();
    }

    @Test
    void searchMemories_whenNoResults_shouldReturnEmptyList() {
        mockServer.expect(requestTo("http://localhost:8889/search"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"results\": []}", MediaType.APPLICATION_JSON));

        Mem0RestClient.SearchRequest req = Mem0RestClient.SearchRequest.builder()
                .query("test")
                .build();

        Mem0RestClient.Mem0ServerResp resp = client.searchMemories(req);

        assertNotNull(resp);
        assertTrue(resp.getResults().isEmpty());
        mockServer.verify();
    }

    @Test
    void getAllMemories_shouldBuildCorrectUrl() {
        mockServer.expect(requestTo("http://localhost:8889/memories?user_id=user-001&agent_id=agent-001&"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"memories\": []}", MediaType.APPLICATION_JSON));

        Object result = client.getAllMemories("user-001", "agent-001", null);

        assertNotNull(result);
        mockServer.verify();
    }
}
```

- [ ] **Step 1: 创建测试目录和文件**

```bash
mkdir -p ai-agent-study-app/src/test/java/denny/ai/agent/config/mem0
touch ai-agent-study-app/src/test/java/denny/ai/agent/config/mem0/Mem0RestClientTest.java
```

- [ ] **Step 2: 写入测试代码**

> 验收标准: 测试类存在，4 个测试方法（addMemory、searchMemories 返回结果、searchMemories 空结果、getAllMemories）全部包含

- [ ] **Step 3: 运行测试**

```bash
cd ai-agent-study-app && mvn test -Dtest=Mem0RestClientTest -q
```

> 验收标准: exit code = 0，4 个测试全部 PASSED，无 FAILED 或 ERROR

- [ ] **Step 4: Commit**

```bash
git add ai-agent-study-app/src/test/java/denny/ai/agent/config/mem0/Mem0RestClientTest.java
git commit -m "test: add Mem0RestClient unit tests"
```

---

## Task 8 (可选): 删除 MEM0_MEMORY 废弃枚举值

> **前置条件:** Task 6 完成并编译通过
> **前提确认:** 执行前需通过代码搜索确认无前端或外部调用方引用 `MEM0_MEMORY` 枚举值。如有引用则跳过本 Task。
>
> **方案选择:**
> - 方案 A（推荐）：确认无引用后删除枚举值和 import
> - 方案 B（保守）：保留枚举值和 import，编译时忽略"未使用"警告

**Files:**
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/enums/AiClientAdvisorTypeEnumVO.java`

删除 `MEM0_MEMORY` 枚举值（第 47-55 行）及其相关 import `com.alibaba.cloud.ai.memory.mem0.advisor.Mem0ChatMemoryAdvisor`。

删除后 `AiClientAdvisorTypeEnumVO` 应保留 `CHAT_MEMORY`、`RAG_ANSWER`、`OBSERVABILITY` 三个枚举值。

- [ ] **Step 1: 删除 MEM0_MEMORY 枚举值**

删除 `MEM0_MEMORY("Mem0Memory", "Mem0 长期记忆") { ... }` 整体块（约第 47-55 行）。

删除文件头部 import：
```java
// 删除
import com.alibaba.cloud.ai.memory.mem0.advisor.Mem0ChatMemoryAdvisor;
```

- [ ] **Step 2: 编译验证**

```bash
cd ai-agent-study-domain && mvn compile -pl ai-agent-study-domain -q
```

> 验收标准: exit code = 0，无 `com.alibaba.cloud.ai.memory.mem0` 相关编译错误

- [ ] **Step 3: Commit**

```bash
git add ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/enums/AiClientAdvisorTypeEnumVO.java
git commit -m "chore: remove unused MEM0_MEMORY enum value and Mem0ChatMemoryAdvisor import"
```

---

## 自审清单

- [ ] Task 1-7 全部完成（7 个提交）
- [ ] 全量编译 `mvn compile -q` exit code = 0
- [ ] 单元测试 `mvn test -Dtest=Mem0RestClientTest` 全部通过
- [ ] 集成测试 `mvn test -Dtest=Mem0MemoryControllerTest` 全部通过
- [ ] `spring-ai-alibaba-starter-memory-mem0` 不在 classpath 中
- [ ] `com.alibaba.cloud.ai.memory.mem0` import 不存在于任何 Java 源文件
- [ ] spring-ai BOM 版本可以独立升级，不再受 mem0 包约束
- [ ] `MEM0_MEMORY` 枚举值已删除（Task 8 执行后）或确认无引用（Task 8 跳过）
