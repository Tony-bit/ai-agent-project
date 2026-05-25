# Mem0 情景记忆搜索功能设计方案

**Metadata:**
- 状态: v2（review后更新）
- 预估工时: 2h（含重构）
- 日期: 2026-05-25
- 版本: v2（整合review反馈）

> **For agentic workers:** Use superpowers:subagent-driven-development to implement this plan.

---

## 任务状态跟踪

| 序号 | 任务项 | 状态 |
|------|--------|------|
| 1 | 重命名 CrossSessionMemoryProperties → MemoryProperties | pending |
| 2 | 重命名 ICrossSessionMemoryCacheService → IUserPersonaCacheService | pending |
| 3 | 更新所有引用文件 | pending |
| 4 | 新增 IEpisodicMemoryService | pending |
| 5 | 新增 EpisodicMemoryServiceImpl | pending |
| 6 | 新增 AbstractToolCallback 公共类 | pending |
| 7 | 新增 EpisodicMemoryToolCallbacks | pending |
| 8 | 新增 EpisodicMemoryToolCallbackProvider | pending |
| 9 | 修改 GeneralChatNode 注入 Tool | pending |
| 10 | 更新配置文件 application-*.yml | pending |
| 11 | 删除旧文件 | pending |
| 12 | 编写单测 | pending |
| 13 | 编译验证 | pending |

---

**目标：**
1. 新增情景记忆搜索能力，以 Tool/Skill 形式暴露给通用 Agent
2. LLM 自主判断用户 query 是否需要搜索情景记忆，按需调用
3. 重命名 `ICrossSessionMemoryCacheService` → `IUserPersonaCacheService`
4. 重命名 `CrossSessionMemoryProperties` → `MemoryProperties`（统一所有 memory 配置）

**架构决策：**
- **Tool/Skill 调用模式** — 业界主流（AutoGPT、LangChain、Toolformer），LLM 自主决策按需调用
- **不放在 PE Agent** — 情景记忆搜索是"检索+返回"模式，无需复杂的多步推理流程
- **放在通用 Agent** — 通用对话节点 + Tool，按需调用
- 复用已有 `Mem0RestClient.searchMemories()`，**无需 Redis 缓存**
- **userId 获取方式** — 通过 `dynamicContext` 注入 systemPrompt，LLM 透传给 Tool
- **配置统一** — `MemoryProperties` 包含所有 memory 相关配置（persona + episodic）

---

## 1. 现状分析

### 1.1 现有架构

```
IntentRoutingNode
       │
       ├── PE_REASONING/CALCULATION/RETRIEVAL → Step1AnalyzerNode (PE链路)
       │                                             └── 用户画像已在 PE 链路注入
       │
       └── GENERAL_CHAT/AMBIGUOUS → GeneralChatNode (通用对话)
                                             └── 当前无 Tool 能力
```

### 1.2 目标架构

```
IntentRoutingNode
       │
       └── GENERAL_CHAT/AMBIGUOUS → GeneralChatNode (通用对话)
                                             │
                                             └── ChatClient + search_episodic_memory Tool
                                                       │
                                                       ├── LLM 分析 query
                                                       │         ↓
                                                       │   "用户问的是历史事件，需要调用 search_episodic_memory"
                                                       │         ↓
                                                       ├── Tool: search_episodic_memory(userId, query)
                                                       │         ↓
                                                       └── LLM 基于检索结果回答
```

### 1.3 为什么用 Tool/Skill 模式

| 维度 | 必查模式 | **Tool/Skill 模式** |
|------|---------|-------------------|
| 调用策略 | 每次都查 | **按需调用** |
| LLM 自主性 | 无 | **自主决策** |
| 资源消耗 | 高 | **低** |
| 业界认可度 | 低 | **主流**（AutoGPT、LangChain、Toolformer） |
| 扩展性 | 差 | **好**（可扩展其他 Tool） |

**情景记忆搜索的本质是"按需检索"任务**：
- 用户不主动询问历史 → 不调用 Tool
- 用户主动询问历史 → LLM 自动调用 Tool
- 完全由 LLM 自主决策，无需规则判断

---

## 2. 现状与问题

### 2.1 已实现能力

| 能力 | 方法 | 状态 |
|------|------|------|
| 用户画像获取 | `Mem0RestClient.getPersona()` | 已实现，已在 PE 链路注入 |
| 情景记忆语义搜索 | `Mem0RestClient.searchMemories()` | **已实现但未被业务调用** |

### 2.2 需要解决的问题

| 问题 | 描述 |
|------|------|
| 功能缺失 | 用户询问历史事件时无法检索相关情景记忆 |
| searchMemories 未接入业务 | 虽有 HTTP 接口，但 Agent 业务链路未调用 |
| 缺少 Tool 封装 | 需新增 `IEpisodicMemoryService` + ToolCallback |
| 命名不一致 | `ICrossSessionMemoryCacheService` 实际只返回"用户画像"，名字与业务不符 |

---

## 3. 改动总览

### 3.1 完整引用文件清单（需更新）

> **重要**：执行前需确认以下所有文件已更新，重命名遗漏将导致编译失败。

#### 3.1.1 重命名：ICrossSessionMemoryCacheService → IUserPersonaCacheService

| 序号 | 原文件 | 新文件 | 改动类型 |
|------|--------|--------|----------|
| 1 | `ai-agent-study-domain/.../service/crossmemory/ICrossSessionMemoryCacheService.java` | `IUserPersonaCacheService.java` (迁至 `persona/` 包) | 重命名+迁移 |
| 2 | `ai-agent-study-infrastructure/.../service/crossmemory/CrossSessionMemoryCacheServiceImpl.java` | `UserPersonaCacheServiceImpl.java` (迁至 `persona/` 包) | 重命名+迁移 |
| 3 | `ai-agent-study-infrastructure/.../service/crossmemory/CrossSessionMemoryCacheServiceImplTest.java` | `UserPersonaCacheServiceImplTest.java` | 重命名+迁移 |

#### 3.1.2 重命名：CrossSessionMemoryProperties → MemoryProperties

| 序号 | 文件 | 改动类型 |
|------|------|----------|
| 4 | `ai-agent-study-domain/.../model/valobj/CrossSessionMemoryProperties.java` | 重命名 + 新增 episodicMemoryLimit 字段 |
| 5 | `ai-agent-study-domain/.../service/auto/step/AbstractExecuteSupport.java` | 更新 import 和字段名 |
| 6 | `ai-agent-study-domain/.../test/.../AbstractExecuteSupportTest.java` | 更新 import 和字段引用 |
| 7 | `ai-agent-study-domain/.../test/.../Step1AnalyzerNodeTest.java` | 更新 import 和断言 |
| 8 | `ai-agent-study-infrastructure/.../service/persona/UserPersonaCacheServiceImpl.java` | 更新 import 和字段名 |
| 9 | `ai-agent-study-infrastructure/.../service/persona/UserPersonaCacheServiceImplTest.java` | 更新 import 和字段引用 |

#### 3.1.3 新增功能（情景记忆搜索 Tool）

| 序号 | 文件 | 改动类型 | 说明 |
|------|------|----------|------|
| 10 | `ai-agent-study-domain/.../service/episodicmemory/IEpisodicMemoryService.java` | 新建 | 情景记忆服务接口 |
| 11 | `ai-agent-study-infrastructure/.../service/episodicmemory/EpisodicMemoryServiceImpl.java` | 新建 | 情景记忆服务实现 |
| 12 | `ai-agent-study-infrastructure/.../tools/AbstractToolCallback.java` | 新建 | Tool 回调抽象基类（公共类） |
| 13 | `ai-agent-study-infrastructure/.../tools/EpisodicMemoryToolCallbacks.java` | 新建 | 情景记忆 Tool 回调实现 |
| 14 | `ai-agent-study-infrastructure/.../tools/EpisodicMemoryToolCallbackProvider.java` | 新建 | Tool 注册配置类 |
| 15 | `ai-agent-study-domain/.../step/chat/GeneralChatNode.java` | 修改 | 注入 Tool 到 ChatClient |
| 16 | `application-*.yml` (3个文件) | 修改 | 更新配置 key |

### 3.2 需删除的旧文件

| 文件 | 原因 |
|------|------|
| `ai-agent-study-domain/.../service/crossmemory/ICrossSessionMemoryCacheService.java` | 已迁移到 `persona/IUserPersonaCacheService.java` |
| `ai-agent-study-infrastructure/.../service/crossmemory/CrossSessionMemoryCacheServiceImpl.java` | 已迁移到 `persona/UserPersonaCacheServiceImpl.java` |
| `ai-agent-study-infrastructure/.../service/crossmemory/CrossSessionMemoryCacheServiceImplTest.java` | 已迁移到 `persona/UserPersonaCacheServiceImplTest.java` |

---

## 4. 详细改动

### 4.1 重命名：IUserPersonaCacheService.java（新建）

**文件路径：** `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/persona/IUserPersonaCacheService.java`

将原 `ICrossSessionMemoryCacheService` 重命名并迁移到 `persona` 包：

```java
package denny.ai.agent.domain.service.persona;

/**
 * 用户画像缓存服务接口
 * <p>
 * 提供用户画像的缓存查询能力，优先从 Redis 取缓存，未命中时查 Mem0 并回填缓存。
 * 缓存 key = mem0:persona:{userId}，TTL = 30 分钟。
 * </p>
 *
 * @author denny
 */
public interface IUserPersonaCacheService {

    String CACHE_KEY_PREFIX = "mem0:persona:";

    /**
     * 获取用户画像
     *
     * @param userId 用户ID
     * @return 格式化后的用户画像字符串，无画像时返回空字符串
     */
    String getUserPersona(String userId);

    /**
     * 刷新缓存 TTL
     *
     * @param userId 用户ID
     */
    void refreshTtl(String userId);
}
```

### 4.3 重命名：UserPersonaCacheServiceImpl.java（新建）

**文件路径：** `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/persona/UserPersonaCacheServiceImpl.java`

将原 `CrossSessionMemoryCacheServiceImpl` 重命名并迁移到 `persona` 包，更新配置引用为 `MemoryProperties`：

```java
package denny.ai.agent.infrastructure.service.persona;

import denny.ai.agent.domain.model.valobj.MemoryProperties;
import denny.ai.agent.domain.service.persona.IUserPersonaCacheService;
import denny.ai.agent.infrastructure.mem0.Mem0RestClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 用户画像缓存服务实现
 * <p>
 * 缓存策略：Redis 命中则直接返回，未命中则查 Mem0 → 回填 Redis（TTL=5分钟）。
 * </p>
 *
 * @author denny
 */
@Slf4j
@Service
public class UserPersonaCacheServiceImpl implements IUserPersonaCacheService {

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Mem0RestClient mem0RestClient;

    @Resource
    private MemoryProperties memoryProperties;

    @Override
    public String getUserPersona(String userId) {
        if (stringRedisTemplate == null) {
            return queryFromMem0(userId);
        }

        String cacheKey = CACHE_KEY_PREFIX + userId;
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                // 固定TTL，不刷新
                log.debug("用户画像命中缓存, userId={}", userId);
                return cached;
            }

            String result = queryFromMem0(userId);
            if (result != null && !result.isEmpty()) {
                stringRedisTemplate.opsForValue().set(cacheKey, result,
                        memoryProperties.getPersonaTtlMinutes(), TimeUnit.MINUTES);
            }
            return result != null ? result : "";

        } catch (Exception e) {
            log.warn("用户画像缓存异常，降级查 Mem0, userId={}, error={}", userId, e.getMessage());
            return queryFromMem0(userId);
        }
    }

    private String queryFromMem0(String userId) {
        try {
            String persona = mem0RestClient.getPersona(userId);
            return persona != null ? persona : "";
        } catch (Exception e) {
            log.warn("Mem0 查询用户画像失败，降级返回空, userId={}, error={}", userId, e.getMessage());
            return "";
        }
    }
}
```

### 4.4 更新：AbstractExecuteSupport.java

**文件路径：** `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/AbstractExecuteSupport.java`

**改动说明：** 更新 import 和字段名，配置引用从 `crossSessionMemoryProperties` 改为 `memoryProperties`

```java
// 更新 import
-import denny.ai.agent.domain.model.valobj.CrossSessionMemoryProperties;
+import denny.ai.agent.domain.model.valobj.MemoryProperties;

-import denny.ai.agent.domain.service.crossmemory.ICrossSessionMemoryCacheService;
+import denny.ai.agent.domain.service.persona.IUserPersonaCacheService;

// 更新字段名
-@Resource
-private ICrossSessionMemoryCacheService crossSessionMemoryCacheService;
+@Resource
+private IUserPersonaCacheService userPersonaCacheService;

-@Resource
-private CrossSessionMemoryProperties crossSessionMemoryProperties;
+@Resource
+private MemoryProperties memoryProperties;

// injectPersonaContext 方法中更新引用
-if (crossSessionMemoryProperties == null) { ... }
-if (!crossSessionMemoryProperties.isInjectCrossSessionMemory()) { ... }
-if (crossSessionMemoryCacheService == null) { ... }
-String memories = crossSessionMemoryCacheService.getCrossSessionMemories(request.getUserId());
+if (memoryProperties == null) { ... }
+if (!memoryProperties.isInjectPersona()) { ... }
+if (userPersonaCacheService == null) { ... }
+String memories = userPersonaCacheService.getUserPersona(request.getUserId());
```

### 4.5 新建：IEpisodicMemoryService.java

**文件路径：** `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/episodicmemory/IEpisodicMemoryService.java`

```java
package denny.ai.agent.domain.service.episodicmemory;

/**
 * 情景记忆搜索服务接口
 * <p>
 * 提供跨会话情景记忆的语义搜索能力，直接调用 Mem0 searchMemories 接口，
 * 查询结果格式化后返回。无需缓存（每次 query 不同，缓存命中极低）。
 * </p>
 *
 * @author denny
 */
public interface IEpisodicMemoryService {

    int DEFAULT_LIMIT = 5;

    /**
     * 搜索情景记忆
     * <p>
     * 直接调用 Mem0 searchMemories 接口，返回格式化后的记忆列表。
     * </p>
     *
     * @param userId 用户ID
     * @param query  搜索关键词（直接使用用户原始消息）
     * @param limit  返回数量上限，默认 DEFAULT_LIMIT
     * @return 格式化后的记忆字符串，无结果时返回友好提示
     */
    String searchEpisodicMemories(String userId, String query, int limit);

    default String searchEpisodicMemories(String userId, String query) {
        return searchEpisodicMemories(userId, query, DEFAULT_LIMIT);
    }
}
```

### 4.6 新建：EpisodicMemoryServiceImpl.java

**文件路径：** `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/service/episodicmemory/EpisodicMemoryServiceImpl.java`

```java
package denny.ai.agent.infrastructure.service.episodicmemory;

import denny.ai.agent.domain.model.valobj.MemoryProperties;
import denny.ai.agent.domain.service.episodicmemory.IEpisodicMemoryService;
import denny.ai.agent.infrastructure.mem0.Mem0RestClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 情景记忆搜索服务实现
 * <p>
 * 直接调用 Mem0 searchMemories 接口，返回格式化后的记忆列表。
 * 无 Redis 缓存：每次用户消息的 query 不同，缓存命中极低。
 * </p>
 *
 * @author denny
 */
@Slf4j
@Service
public class EpisodicMemoryServiceImpl implements IEpisodicMemoryService {

    @Resource
    private Mem0RestClient mem0RestClient;

    @Resource
    private MemoryProperties memoryProperties;

    @Override
    public String searchEpisodicMemories(String userId, String query, int limit) {
        if (query == null || query.isBlank()) {
            log.debug("query 为空，跳过情景记忆搜索, userId={}", userId);
            return "";
        }

        try {
            Mem0RestClient.SearchRequest request = Mem0RestClient.SearchRequest.builder()
                    .query(query)
                    .user_id(userId)
                    .limit(limit)
                    .build();

            Mem0RestClient.Mem0ServerResp resp = mem0RestClient.searchMemories(request);
            String result = formatSearchResults(resp);

            if (!result.isEmpty()) {
                log.info("情景记忆搜索成功, userId={}, queryLen={}, resultLen={}",
                        userId, query.length(), result.length());
            }
            return result;

        } catch (Exception e) {
            log.warn("情景记忆搜索失败，降级返回空, userId={}, queryLen={}, error={}",
                    userId, query.length(), e.getMessage());
            return "";
        }
    }

    private String formatSearchResults(Mem0RestClient.Mem0ServerResp resp) {
        if (resp == null || resp.getResults() == null || resp.getResults().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n[情景记忆]\n");

        int idx = 1;
        for (Mem0RestClient.Mem0Results result : resp.getResults()) {
            String scoreStr = result.getScore() != null
                    ? String.format(" (相似度: %.2f)", result.getScore())
                    : "";
            sb.append(idx++).append(". ")
              .append(result.getMemory())
              .append(scoreStr)
              .append("\n");
        }

        return sb.toString();
    }
}
```

### 4.2 重命名：MemoryProperties.java（统一 memory 配置）

**文件路径：** `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/MemoryProperties.java`

将 `CrossSessionMemoryProperties` 重命名为 `MemoryProperties`，统一所有 memory 相关配置：

```java
package denny.ai.agent.domain.model.valobj;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 记忆配置属性
 * <p>
 * 统一管理所有 memory 相关配置：
 * - persona: 用户画像（跨会话长期记忆）
 * - episodic: 情景记忆（按需搜索）
 * </p>
 *
 * @author denny
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.memory")
public class MemoryProperties {

    /**
     * 是否在会话初始化时注入用户画像
     */
    private boolean injectPersona = true;

    /**
     * 用户画像默认查询条数
     */
    private int personaTopK = 5;

    /**
     * 用户画像 Redis 缓存 TTL（分钟），默认 5 分钟
     */
    private int personaTtlMinutes = 5;

    /**
     * 情景记忆搜索结果上限
     */
    private int episodicMemoryLimit = 5;
}
```

> **配置 key 变更**：`chat.memory` → `ai.memory`（需同步修改 application-*.yml）

### 4.7 新建：AbstractToolCallback.java（公共类）

**文件路径：** `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/tools/AbstractToolCallback.java`

**设计说明**：
- 提取为公共类，供所有 ToolCallback 复用
- `call()` 接口与 `TradingToolCallbacks` 保持一致（参数为 `String functionInput`）
- 内部使用 `ObjectMapper` 解析 JSON

```java
package denny.ai.agent.infrastructure.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinitionBuilder;

import java.util.Map;

/**
 * Tool 回调抽象基类
 * <p>
 * 参考 TradingToolCallbacks 模式，提供统一的 ToolCallback 实现模板。
 * </p>
 *
 * @author denny
 */
@Slf4j
public abstract class AbstractToolCallback implements ToolCallback {

    private final String name;
    private final String description;
    private final String inputSchema;
    private final ObjectMapper objectMapper;

    protected AbstractToolCallback(String name, String description, String inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinitionBuilder.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
    }

    @Override
    public String call(String functionInput) {
        try {
            Map<String, Object> input = objectMapper.readValue(
                    functionInput, new TypeReference<Map<String, Object>>() {});
            return doExecute(input);
        } catch (Exception e) {
            log.error("Tool[{}] 执行失败: input={}, error={}", name, functionInput, e.getMessage(), e);
            return "工具执行失败: " + e.getMessage();
        }
    }

    protected abstract String doExecute(Map<String, Object> input) throws Exception;

    protected int parseInteger(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("无法将值 '{}' 解析为整数，使用默认值 {}", value, defaultValue);
            return defaultValue;
        }
    }
}
```

### 4.8 新建：EpisodicMemoryToolCallbacks.java

**文件路径：** `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/tools/EpisodicMemoryToolCallbacks.java`

**设计说明**：
- `userId` 由 LLM 通过 systemPrompt 注入，Tool 只接收 `query` 参数
- 无结果时返回友好提示，引导 LLM 基于其他信息回答

```java
package denny.ai.agent.infrastructure.tools;

import denny.ai.agent.domain.model.valobj.MemoryProperties;
import denny.ai.agent.domain.service.episodicmemory.IEpisodicMemoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 情景记忆搜索 Tool 回调实现
 * <p>
 * 将 IEpisodicMemoryService 包装为 ToolCallback，供 Agent 通过 Function Calling 调用。
 * </p>
 *
 * @author denny
 */
@Slf4j
@Component
public class EpisodicMemoryToolCallbacks {

    @Resource
    private IEpisodicMemoryService episodicMemoryService;

    @Resource
    private MemoryProperties memoryProperties;

    /**
     * 搜索情景记忆 Tool
     * <p>
     * userId 由 LLM 通过 systemPrompt 上下文获取并透传。
     * 当用户询问历史事件、之前讨论的内容时调用此工具。
     * </p>
     */
    public ToolCallback searchEpisodicMemoryCallback() {
        return new AbstractToolCallback(
                "search_episodic_memory",
                "搜索用户的跨会话情景记忆。当用户询问之前讨论过的话题、历史事件、之前说过的话时，必须调用此工具获取相关记忆。\n" +
                "注意：只有用户明确在询问历史内容时才调用。",
                buildInputSchema(
                        "query", "搜索关键词，使用用户的原始问题或关键词，如'上次讨论的项目'、'之前说过的计划'",
                        "userId", "用户ID，从对话上下文中获取") {
            @Override
            protected String doExecute(Map<String, Object> input) throws Exception {
                String query = (String) input.get("query");
                String userId = (String) input.get("userId");
                int limit = memoryProperties.getEpisodicMemoryLimit();

                if (query == null || query.isBlank()) {
                    return "搜索关键词为空，无法进行情景记忆搜索";
                }

                log.info("搜索情景记忆: userId={}, query={}, limit={}", userId, query, limit);

                String result = episodicMemoryService.searchEpisodicMemories(userId, query, limit);

                if (result.isEmpty()) {
                    return "未找到相关情景记忆，建议基于当前对话内容回答用户问题";
                }
                return result;
            }
        };
    }

    private String buildInputSchema(String... pairs) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, java.util.Map<String, String>> properties = new java.util.LinkedHashMap<>();
            java.util.List<String> required = new java.util.ArrayList<>();
            for (int i = 0; i < pairs.length; i += 2) {
                String name = pairs[i];
                String desc = pairs[i + 1];
                properties.put(name, java.util.Map.of("type", "string", "description", desc));
                required.add(name);
            }
            java.util.Map<String, Object> schema = java.util.Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", required
            );
            return om.writeValueAsString(schema);
        } catch (Exception e) {
            log.error("构建 inputSchema 失败: {}", e.getMessage());
            return "{}";
        }
    }
}
```

### 4.9 新建：EpisodicMemoryToolCallbackProvider.java

**文件路径：** `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/tools/EpisodicMemoryToolCallbackProvider.java`

```java
package denny.ai.agent.infrastructure.tools;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 情景记忆搜索 Tool 注册配置
 * <p>
 * 将 search_episodic_memory Tool 注册为 Bean，注入到 ChatClient。
 * </p>
 *
 * @author denny
 */
@Slf4j
@Configuration
public class EpisodicMemoryToolCallbackProvider {

    @Resource
    private EpisodicMemoryToolCallbacks episodicMemoryToolCallbacks;

    @Bean
    public ToolCallback searchEpisodicMemoryCallback() {
        return episodicMemoryToolCallbacks.searchEpisodicMemoryCallback();
    }
}
```

### 4.10 修改：GeneralChatNode.java

**文件路径：** `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/chat/GeneralChatNode.java`

**设计说明**：
- `userId` 通过 `dynamicContext` 获取（已在 PE 链路注入）
- systemPrompt 包含 `userId` 上下文，LLM 透传给 Tool

#### 4.10.1 新增字段注入

```java
// 新增 import
+ import org.springframework.ai.tool.ToolCallback;
+ import java.util.List;

// 新增字段注入
+ @org.springframework.beans.factory.annotation.Autowired(required = false)
+ private List<ToolCallback> searchEpisodicMemoryCallbacks;
```

#### 4.10.2 修改 doTextApply 方法（注入 Tool + userId 上下文）

```java
private String doTextApply(ExecuteCommandEntity request,
                          DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
    IntentTypeEnum recognizedIntent = dynamicContext.getValue(RECOGNIZED_INTENT_KEY);

    sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
            .type("system")
            .subType("general_chat_start")
            .content("正在思考...")
            .completed(false)
            .timestamp(System.currentTimeMillis())
            .build());

    // 获取 userId 上下文（从 dynamicContext 中获取，PE 链路已注入）
    String userId = dynamicContext.getValue("userId");
    String systemPromptWithContext = buildSystemPrompt(recognizedIntent, userId);

    ChatClient chatClient = getChatClientByClientId("3001", 0);

    // 构建 ChatClient PromptBuilder
    var promptBuilder = chatClient.prompt()
            .system(systemPromptWithContext)
            .user(request.getMessage())
            .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, request.getSessionId())
                    .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 1024));

    // 注入情景记忆 Tool（如果配置了）
    if (searchEpisodicMemoryCallbacks != null && !searchEpisodicMemoryCallbacks.isEmpty()) {
        promptBuilder.tools(searchEpisodicMemoryCallbacks.toArray(new ToolCallback[0]));
        log.info("通用对话已注入情景记忆 Tool, toolCount={}", searchEpisodicMemoryCallbacks.size());
    }

    String response = promptBuilder.call().content();

    sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
            .type("content")
            .subType("general_chat_response")
            .content(response)
            .completed(true)
            .timestamp(System.currentTimeMillis())
            .build());

    dynamicContext.setCompleted(true);
    dynamicContext.setValue("generalChatResponse", response);

    sendCompleteResult(dynamicContext, request.getSessionId());

    log.info("通用对话完成: intent={}, responseLength={}", recognizedIntent, response.length());
    return response;
}

// 新增辅助方法
private String buildSystemPrompt(IntentTypeEnum recognizedIntent, String userId) {
    String basePrompt = resolveSystemPrompt(recognizedIntent);
    if (userId != null && !userId.isBlank()) {
        return basePrompt + String.format("\n\n[上下文] 当前用户ID: %s", userId);
    }
    return basePrompt;
}
```

#### 4.10.3 修改 doMultimodalApply 方法（同样注入 Tool）

```java
// Step 4: 调用多模态对话（同样注入 Tool）
var promptBuilder = chatClient.prompt()
        .system(GENERAL_CHAT_SYSTEM_PROMPT)
        .user(multimodalMessage)
        .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, request.getSessionId())
                .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 0));

// 注入情景记忆 Tool（如果配置了）
if (searchEpisodicMemoryCallbacks != null && !searchEpisodicMemoryCallbacks.isEmpty()) {
    promptBuilder.tools(searchEpisodicMemoryCallbacks.toArray(new ToolCallback[0]));
}

String response = promptBuilder.call().content();
```

### 4.11 配置文件修改

#### application-dev.yml

```yaml
ai:
  memory:
    inject-persona: true        # 原 chat.memory.inject-cross-session-memory
    persona-top-k: 5          # 原 chat.memory.cross-session-memory-top-k
    persona-ttl-minutes: 5    # 原 chat.memory.cross-session-memory-ttl-minutes
    episodic-memory-limit: 5  # 新增
```

#### application-test.yml

```yaml
ai:
  memory:
    inject-persona: true
    persona-top-k: 5
    persona-ttl-minutes: 5
    episodic-memory-limit: 5
```

#### application-prod.yml

```yaml
ai:
  memory:
    inject-persona: true
    persona-top-k: 5
    persona-ttl-minutes: 5
    episodic-memory-limit: 5
```

---

## 5. 文件删除清单

重命名后需删除旧文件：

| 文件 | 原因 |
|------|------|
| `ai-agent-study-domain/.../service/crossmemory/ICrossSessionMemoryCacheService.java` | 已迁移到 `persona/IUserPersonaCacheService.java` |
| `ai-agent-study-infrastructure/.../service/crossmemory/CrossSessionMemoryCacheServiceImpl.java` | 已迁移到 `persona/UserPersonaCacheServiceImpl.java` |
| `ai-agent-study-infrastructure/.../service/crossmemory/CrossSessionMemoryCacheServiceImplTest.java` | 已迁移到 `persona/UserPersonaCacheServiceImplTest.java` |
| `ai-agent-study-domain/.../model/valobj/CrossSessionMemoryProperties.java` | 已迁移到 `valobj/MemoryProperties.java` |

---

## 6. LLM Tool 调用流程

### 6.1 userId 上下文传递机制

```
用户请求 → PE链路/IntentRoutingNode → dynamicContext.setValue("userId", userId)
                                                      ↓
                        GeneralChatNode.doTextApply()
                                                      ↓
                        buildSystemPrompt() 添加上下文
                                                      ↓
                        systemPrompt = "你是一个友好的AI助手...\n\n[上下文] 当前用户ID: xxx"
                                                      ↓
                        ChatClient.prompt().system(systemPrompt).tools(...).call()
                                                      ↓
                        LLM 识别需要搜索历史 → 调用 search_episodic_memory({query, userId})
```

### 6.2 完整调用示例

```
用户: "上次我们讨论的那个项目进展怎么样了？"

LLM 分析:
  → 检测到关键词"上次"、"讨论"
  → 需要搜索情景记忆
  → 读取 systemPrompt 中的 userId 上下文

LLM 调用 Tool:
  → search_episodic_memory({
      "query": "上次我们讨论的项目",
      "userId": "user-12345"
    })

Tool 执行:
  → Mem0RestClient.searchMemories(query, userId)
  → 返回格式化结果或友好提示

LLM 回答:
  → 基于检索结果/友好提示回答用户问题
```

---

## 7. 验收标准

| 验收项 | 判定条件 |
|--------|----------|
| `ICrossSessionMemoryCacheService` 相关文件已删除 | 无残留 |
| `CrossSessionMemoryProperties` 已删除 | 无残留 |
| `IUserPersonaCacheService` 正常工作 | 单测通过 |
| `MemoryProperties` 正常工作 | 单测通过 |
| `IEpisodicMemoryService.searchEpisodicMemories()` 正常返回格式化结果 | 单测通过 |
| `search_episodic_memory` Tool 正常注册 | Spring 容器启动成功 |
| `GeneralChatNode` 正常注入 Tool | 日志显示 Tool 注入成功 |
| LLM 按需调用 Tool | 集成测试通过 |
| 通用对话功能不受影响 | 回归测试通过 |
| 编译通过 | Maven 编译无 error |
| 配置 key 已从 `chat.memory` 迁移到 `ai.memory` | application-*.yml 已更新 |

---

## 8. 关键技术决策

| 决策 | 方案 | 理由 |
|------|------|------|
| Tool/Skill 调用模式 | LLM 自主决策 | 业界主流（AutoGPT、LangChain、Toolformer） |
| 放在通用 Agent | `GeneralChatNode` | 通用对话 + Tool，按需调用 |
| 无 Redis 缓存 | 直接查 Mem0 | 每次 query 不同，缓存命中极低 |
| Tool 注册方式 | Spring Bean | 复用现有 `TradingToolCallbackProvider` 模式 |
| 异常降级 | 返回空字符串 | 不阻塞 LLM 回答 |
| userId 获取方式 | 通过 dynamicContext 注入 systemPrompt | 复用 PE 链路的 userId 注入逻辑 |
| 配置统一 | `MemoryProperties` | 包含 persona + episodic 所有配置 |
| AbstractToolCallback | 公共类 | 与 `TradingToolCallbacks` 保持一致的 `call(String)` 接口 |
| 无结果提示 | 友好提示 | 引导 LLM 基于其他信息回答 |

---

## 9. 测试用例设计

### 9.1 测试范围总览

| 序号 | 测试文件 | 测试对象 | 用例数 | 优先级 |
|------|----------|----------|--------|--------|
| 1 | `CrossSessionMemoryPropertiesTest.java` | 配置类 | 10 | P0 |
| 2 | `CrossSessionMemoryPropertiesConfigTest.java` | 配置加载 | 7 | P1 |
| 3 | `IEpisodicMemoryServiceTest.java` | 情景记忆接口 | 8 | P0 |
| 4 | `EpisodicMemoryServiceImplTest.java` | 情景记忆实现 | 18 | P0 |
| 5 | `AbstractToolCallbackTest.java` | Tool回调基类 | 15 | P0 |
| 6 | `EpisodicMemoryToolCallbacksTest.java` | 情景记忆Tool | 20 | P0 |
| 7 | `EpisodicMemoryToolCallbackProviderTest.java` | Tool注册器 | 9 | P1 |
| 8 | `UserPersonaCacheServiceImplTest.java` | 用户画像服务 | 16 | P0 |
| 9 | `GeneralChatNodeToolInjectionTest.java` | Tool注入 | 13 | P1 |

**总计：143 个测试用例**（原116 + 新增27）

### 9.2 核心功能测试用例（新增）

> **说明**：Mem0 单接口测试（Mem0RestClientTest、Mem0MemoryControllerTest）已有覆盖，本节只设计新增功能的测试

#### 9.2.1 IEpisodicMemoryServiceTest.java

| 用例ID | 用例名称 | 验证点 | 预期结果 |
|--------|----------|--------|----------|
| TC-EpiSrv-001 | searchEpisodicMemories调用Mem0 | 注入mock Mem0RestClient | 调用searchMemories |
| TC-EpiSrv-002 | 结果格式化 | Mem0返回结果 | 返回`[情景记忆]\n{记忆内容}`格式 |
| TC-EpiSrv-003 | 空结果处理 | Mem0返回空 | 返回空字符串 |
| TC-EpiSrv-004 | limit参数传递 | 调用时检查limit | 传递给Mem0的limit正确 |
| TC-EpiSrv-005 | userId为null | userId=null | 正常处理不抛异常 |
| TC-EpiSrv-006 | query为空白 | query="   " | 返回空字符串 |
| TC-EpiSrv-007 | 异常降级 | Mem0抛异常 | 捕获异常返回空字符串 |

#### 9.2.2 EpisodicMemoryToolCallbacksTest.java

| 用例ID | 用例名称 | 验证点 | 预期结果 |
|--------|----------|--------|----------|
| TC-EpiTool-001 | Tool名称正确 | getToolDefinition().name() | 等于"search_episodic_memory" |
| TC-EpiTool-002 | Tool描述包含关键信息 | description内容 | 包含"情景记忆"、"历史"关键词 |
| TC-EpiTool-003 | inputSchema包含query参数 | inputSchema解析 | 包含query和userId参数 |
| TC-EpiTool-004 | call方法正确调用Service | 注入mock Service | Service被调用一次 |
| TC-EpiTool-005 | call方法参数解析 | 输入JSON | 正确解析query和userId |
| TC-EpiTool-006 | 空query返回友好提示 | query="" | 返回"搜索关键词为空" |
| TC-EpiTool-007 | Service返回空返回友好提示 | Service返回"" | 返回"未找到相关情景记忆" |
| TC-EpiTool-008 | Service返回正常结果透传 | Service返回"xxx" | 直接返回"xxx" |
| TC-EpiTool-009 | 异常捕获不抛异常 | Service抛异常 | 返回"工具执行失败"消息 |
| TC-EpiTool-010 | implements ToolCallback | instanceof检查 | 是ToolCallback |

#### 9.2.3 GeneralChatNodeToolInjectionTest.java

| 用例ID | 用例名称 | 验证点 | 预期结果 |
|--------|----------|--------|----------|
| TC-GenChat-001 | Tool字段存在 | 字段类型 | List<ToolCallback> searchEpisodicMemoryCallbacks |
| TC-GenChat-002 | Tool注入时创建promptBuilder | 注入非空list | promptBuilder.tools()被调用 |
| TC-GenChat-003 | Tool未注入时不调用 | list为null或empty | promptBuilder.tools()不被调用 |
| TC-GenChat-004 | userId正确注入systemPrompt | userId不为null | systemPrompt包含"当前用户ID" |
| TC-GenChat-005 | userId为null不添加上下文 | userId=null | systemPrompt不包含上下文 |
| TC-GenChat-006 | 多个Tool正确注册 | list有2个元素 | promptBuilder.tools(2个Tool) |
| TC-GenChat-007 | doTextApply和doMultimodalApply都注入 | 两种方法 | 都注入Tool |

#### 9.2.4 E2E端到端测试用例（真实Mem0数据）

> **前置条件**：Mem0 Server 运行中，数据库中已存储测试数据

| 用例ID | 用例名称 | 入参 | 预期结果 |
|--------|----------|------|----------|
| TC-E2E-001 | 情景记忆搜索-精确匹配 | userId=test-user-f52b2ed0, query=喜欢吃什么 | 返回"喜欢吃鱼" |
| TC-E2E-002 | 情景记忆搜索-语义匹配 | userId=test-user-f52b2ed0, query=用户的口味偏好 | 返回"喜欢吃鱼" |
| TC-E2E-003 | 情景记忆搜索-不存在记忆 | userId=test-user-f52b2ed0, query=完全不相关的关键词 | 返回空或友好提示 |

**测试数据**：
```json
{
  "user_id": "test-user-f52b2ed0",
  "memory": "喜欢吃鱼",
  "hash": "e8081c03f225108d408367ce7ae9c383"
}
```

**E2E测试执行方式**：
1. 启动应用服务
2. 调用 Agent 对话接口，userId=test-user-f52b2ed0, message="我之前喜欢吃什么？"
3. 验证 LLM 调用了 search_episodic_memory Tool
4. 验证 Tool 返回包含"喜欢吃鱼"
5. 验证 LLM 基于记忆正确回答

### 9.3 测试执行顺序

```
阶段1: 配置类测试 (P0)
├── CrossSessionMemoryPropertiesTest.java
└── CrossSessionMemoryPropertiesConfigTest.java

阶段2: 接口测试 (P0)
└── IEpisodicMemoryServiceTest.java

阶段3: 服务实现测试 (P0)
└── EpisodicMemoryServiceImplTest.java

阶段4: Tool相关测试 (P0)
├── AbstractToolCallbackTest.java
├── EpisodicMemoryToolCallbacksTest.java
└── EpisodicMemoryToolCallbackProviderTest.java

阶段5: 集成测试 (P0)
├── UserPersonaCacheServiceImplTest.java
└── GeneralChatNodeToolInjectionTest.java
```

### 9.4 测试文件详细用例

#### 9.3.1 CrossSessionMemoryPropertiesTest.java

**路径:** `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/model/valobj/`

| 用例ID | 用例名称 | 验证点 | 预期结果 |
|--------|----------|--------|----------|
| TC-MemProps-001 | 默认配置值正确 | injectCrossSessionMemory=true, crossSessionMemoryTopK=5, crossSessionMemoryTtlMinutes=5 | 所有默认值正确 |
| TC-MemProps-002 | 配置前缀为chat.memory | @ConfigurationProperties(prefix="chat.memory") | 前缀正确 |
| TC-MemProps-003 | episodicMemoryLimit字段存在 | 检查字段和getter/setter | 实现后字段存在 |
| TC-MemProps-004 | injectCrossSessionMemory字段可配置 | setInjectCrossSessionMemory(false) | 配置生效 |
| TC-MemProps-005 | crossSessionMemoryTopK字段可配置 | setCrossSessionMemoryTopK(10) | 配置生效 |
| TC-MemProps-006 | crossSessionMemoryTtlMinutes字段可配置 | setCrossSessionMemoryTtlMinutes(30) | 配置生效 |
| TC-MemProps-007 | @Component注解存在 | 检查注解 | 存在 |
| TC-MemProps-008 | @Data注解存在 | 检查注解 | 存在 |
| TC-MemProps-009 | 所有字段都有getter/setter | 反射检查方法 | 方法存在 |
| TC-MemProps-010 | 配置值可以正常设置和获取 | 链式设置和获取 | 值一致 |

> **Task:** 实现完成后，将类重命名为 `MemoryProperties`，字段重命名（injectCrossSessionMemory → injectPersona 等），并同步更新此测试文件。

#### 9.3.2 CrossSessionMemoryPropertiesConfigTest.java

| 用例ID | 用例名称 | 验证点 | 预期结果 |
|--------|----------|--------|----------|
| TC-Config-001 | YAML配置正确解析memory节点 | 解析chat.memory.* | 结构正确 |
| TC-Config-002 | 配置正确映射到Java属性 | 属性值对应 | 值一致 |
| TC-Config-003 | 默认配置值正确 | 未配置时的默认值 | 默认值正确 |
| TC-Config-004 | SnakeYAML正确解析嵌套配置 | 多层嵌套 | 解析正确 |
| TC-Config-005 | YAML注释不影响解析 | 包含注释的YAML | 解析正确 |
| TC-Config-006 | 配置可序列化 | 属性设置和获取 | 可正常使用 |
| TC-Config-007 | 空配置处理 | 空YAML字符串 | 返回null |

#### 9.3.3 IEpisodicMemoryServiceTest.java

| 用例ID | 用例名称 | 验证点 | 预期结果 |
|--------|----------|--------|----------|
| TC-IEpiSvc-001 | 接口方法签名正确 | searchEpisodicMemories(String, String, int) | 方法签名正确 |
| TC-IEpiSvc-002 | 默认方法使用DEFAULT_LIMIT | searchEpisodicMemories(String, String) | 使用5作为limit |
| TC-IEpiSvc-003 | DEFAULT_LIMIT=5正确 | 接口常量 | 值为5 |
| TC-IEpiSvc-004 | 接口是公共的 | Modifier检查 | public接口 |
| TC-IEpiSvc-005 | 方法参数名称正确 | 参数名userId,query,limit | 名称正确 |
| TC-IEpiSvc-006 | 方法参数数量正确 | 参数数量 | 3个和2个 |
| TC-IEpiSvc-007 | 默认方法不抛异常 | 调用默认方法 | 不抛异常 |
| TC-IEpiSvc-008 | 方法签名允许异常 | Exception声明 | 允许实现类抛异常 |

#### 9.3.4 EpisodicMemoryServiceImplTest.java

| 用例ID | 用例名称 | 验证点 | 预期结果 |
|--------|----------|--------|----------|
| TC-EpiSvc-001 | 正常返回格式化情景记忆 | 返回包含[情景记忆]标题、序号、相似度 | 格式化正确 |
| TC-EpiSvc-002 | 多条结果格式化正确 | 3条结果 | 序号1/2/3正确 |
| TC-EpiSvc-003 | 单条结果格式化正确 | 1条结果 | 只有序号1 |
| TC-EpiSvc-004 | 相似度为null不显示 | score=null | 不显示相似度 |
| TC-EpiSvc-005 | 空结果返回空字符串 | Mem0返回[] | 返回"" |
| TC-EpiSvc-006 | null响应返回空字符串 | Mem0返回null | 返回"" |
| TC-EpiSvc-007 | Mem0异常降级返回空 | throw RuntimeException | 返回""不抛异常 |
| TC-EpiSvc-008 | query空字符串返回空 | query="" | 返回""不调Mem0 |
| TC-EpiSvc-009 | query为null返回空 | query=null | 返回""不调Mem0 |
| TC-EpiSvc-010 | query纯空白返回空 | query="   " | 返回""不调Mem0 |
| TC-EpiSvc-011 | limit参数正确传递 | limit=10 | Mem0收到10 |
| TC-EpiSvc-012 | userId正确传递 | userId="test-123" | Mem0收到正确值 |
| TC-EpiSvc-013 | query参数正确传递 | query="测试" | Mem0收到正确值 |
| TC-EpiSvc-014 | 默认limit被使用 | 调用2参数方法 | 使用DEFAULT_LIMIT |
| TC-EpiSvc-015 | 格式化包含换行符 | 格式化结果 | 包含\n |
| TC-EpiSvc-016 | metadata为null不影响 | metadata=null | 正常显示 |
| TC-EpiSvc-017 | 相似度保留两位小数 | score=0.95321 | 显示0.95 |
| TC-EpiSvc-018 | results为null返回空 | results=null | 返回"" |

#### 9.3.5 AbstractToolCallbackTest.java

| 用例ID | 用例名称 | 验证点 | 预期结果 |
|--------|----------|--------|----------|
| TC-AbstractTool-001 | Tool定义正确创建 | name/description/inputSchema | 正确设置 |
| TC-AbstractTool-002 | JSON输入正确解析 | {"query":"测试"} | 解析为Map |
| TC-AbstractTool-003 | 输入解析失败返回错误 | { invalid } | 返回友好错误 |
| TC-AbstractTool-004 | parseInteger解析数字 | Integer/Long/Double | 正确转换 |
| TC-AbstractTool-005 | parseInteger处理字符串数字 | "123" | 解析为123 |
| TC-AbstractTool-006 | parseInteger异常返回默认 | null/"非数字"/"" | 返回默认值 |
| TC-AbstractTool-007 | call正确调用doExecute | 调用链 | doExecute被调用 |
| TC-AbstractTool-008 | doExecute异常返回错误 | throw Exception | 返回友好错误 |
| TC-AbstractTool-009 | ObjectMapper配置正确 | 复杂JSON解析 | 正常解析 |
| TC-AbstractTool-010 | ToolCallback接口正确实现 | instanceof ToolCallback | 是ToolCallback |
| TC-AbstractTool-011 | 空对象输入处理 | {} | 正常处理 |
| TC-AbstractTool-012 | 特殊字符处理 | !@#$%^&*() | 正常处理 |
| TC-AbstractTool-013 | Unicode输入处理 | 中文/日文/韩文 | 正常处理 |
| TC-AbstractTool-014 | 构造函数参数存储 | name/description/schema | 正确存储 |
| TC-AbstractTool-015 | 多次调用状态正确 | 两次调用 | 状态独立 |

#### 9.3.6 EpisodicMemoryToolCallbacksTest.java

| 用例ID | 用例名称 | 验证点 | 预期结果 |
|--------|----------|--------|----------|
| TC-EpiTool-001 | Tool名称为search_episodic_memory | getToolDefinition().name() | 等于search_episodic_memory |
| TC-EpiTool-002 | Tool描述包含"情景记忆" | description内容 | 包含关键词 |
| TC-EpiTool-003 | inputSchema包含query和userId | schema内容 | 包含两个参数 |
| TC-EpiTool-004 | query参数为必填 | required字段 | query在required中 |
| TC-EpiTool-005 | userId参数为必填 | required字段 | userId在required中 |
| TC-EpiTool-006 | 正常执行返回格式化结果 | call()返回service结果 | 结果正确 |
| TC-EpiTool-007 | service返回空时友好提示 | 返回空时 | "未找到相关情景记忆" |
| TC-EpiTool-008 | service异常返回错误信息 | throw Exception | "工具执行失败" |
| TC-EpiTool-009 | query为空返回提示 | query="" | "搜索关键词为空" |
| TC-EpiTool-010 | 使用配置的episodicMemoryLimit | 修改配置 | 使用配置值 |
| TC-EpiTool-011 | userId参数格式支持 | 各种userId格式 | 正常解析 |
| TC-EpiTool-012 | JSON格式错误返回错误 | { invalid } | 返回友好错误 |
| TC-EpiTool-013 | 描述包含调用场景 | 描述内容 | 包含使用说明 |
| TC-EpiTool-014 | query为null处理 | 缺少query参数 | 返回提示 |
| TC-EpiTool-015 | userId为null不崩溃 | userId=null | 正常处理 |
| TC-EpiTool-016 | userId为空字符串不崩溃 | userId="" | 正常处理 |
| TC-EpiTool-017 | Tool定义可正确获取 | getToolDefinition() | 返回正确定义 |
| TC-EpiTool-018 | 连续调用结果一致 | 相同输入两次 | 结果相同 |
| TC-EpiTool-019 | query纯空白返回提示 | query="   " | 返回提示 |
| TC-EpiTool-020 | 实现ToolCallback接口 | instanceof | 是ToolCallback |

#### 9.3.7 EpisodicMemoryToolCallbackProviderTest.java

| 用例ID | 用例名称 | 验证点 | 预期结果 |
|--------|----------|--------|----------|
| TC-EpiProvider-001 | Bean方法返回非null | searchEpisodicMemoryCallback() | 返回非null |
| TC-EpiProvider-002 | 返回类型为ToolCallback | instanceof检查 | 是ToolCallback |
| TC-EpiProvider-003 | 正确调用Callbacks | 验证调用次数 | 调用1次 |
| TC-EpiProvider-004 | Tool名称正确 | getToolDefinition().name() | search_episodic_memory |
| TC-EpiProvider-005 | 多次调用各创建实例 | 两次调用 | 返回不同实例 |
| TC-EpiProvider-006 | 依赖为null时抛NPE | 注入null | 抛出NullPointerException |
| TC-EpiProvider-007 | @Configuration注解存在 | 检查注解 | 存在 |
| TC-EpiProvider-008 | @Bean注解存在 | 检查方法注解 | 存在 |
| TC-EpiProvider-009 | 返回的Tool功能正常 | call()方法 | 可正常调用 |

#### 9.3.8 UserPersonaCacheServiceImplTest.java

| 用例ID | 用例名称 | 验证点 | 预期结果 |
|--------|----------|--------|----------|
| TC-Persona-001 | 缓存key前缀为mem0:persona: | CACHE_KEY_PREFIX常量 | 等于mem0:persona: |
| TC-Persona-002 | Redis命中返回缓存值 | 缓存存在 | 直接返回不调Mem0 |
| TC-Persona-003 | Redis未命中调用Mem0回填 | 缓存不存在 | 调用Mem0并回填 |
| TC-Persona-004 | Mem0返回null不缓存 | Mem0返回null | 返回""不写缓存 |
| TC-Persona-005 | Mem0异常降级返回空 | Mem0抛异常 | 返回"" |
| TC-Persona-006 | Redis未配置直接查Mem0 | stringRedisTemplate=null | 直接调Mem0 |
| TC-Persona-007 | 命中缓存不刷新TTL | 缓存命中 | 不调用expire() |
| TC-Persona-008 | 命中空字符串返回空 | 缓存="" | 返回"" |
| TC-Persona-009 | 方法名为getUserPersona | 方法名检查 | 存在getUserPersona |
| TC-Persona-010 | 使用MemoryProperties配置 | 修改TTL配置 | 使用配置值 |
| TC-Persona-011 | Mem0返回空字符串不缓存 | Mem0返回"" | 返回""不缓存 |
| TC-Persona-012 | 缓存key格式正确 | key格式 | mem0:persona:{userId} |
| TC-Persona-013 | Redis操作异常时降级 | Redis抛异常 | 降级到Mem0 |
| TC-Persona-014 | 接口常量CACHE_KEY_PREFIX正确 | 常量值 | mem0:persona: |
| TC-Persona-015 | userId为null不抛异常 | userId=null | 正常处理 |
| TC-Persona-016 | 空userId处理正确 | userId="" | 正常处理 |

> **Task:** 实现完成后，将类从 `CrossSessionMemoryCacheServiceImplTest` 重命名为 `UserPersonaCacheServiceImplTest`，将方法从 `getCrossSessionMemories` 改为 `getUserPersona`，将配置引用从 `CrossSessionMemoryProperties` 改为 `MemoryProperties`，然后同步更新此测试文件。

#### 9.3.9 GeneralChatNodeToolInjectionTest.java

| 用例ID | 用例名称 | 验证点 | 预期结果 |
|--------|----------|--------|----------|
| TC-GeneralChat-001 | searchEpisodicMemoryCallbacks字段存在 | 字段类型检查 | List<ToolCallback> |
| TC-GeneralChat-002 | buildSystemPrompt正确拼接userId | userId不为null | 包含上下文 |
| TC-GeneralChat-003 | userId为null不添加上下文 | userId=null | 不添加 |
| TC-GeneralChat-004 | userId为空字符串不添加上下文 | userId="" | 不添加 |
| TC-GeneralChat-005 | systemPrompt基础内容正确 | resolveSystemPrompt() | 返回正确提示词 |
| TC-GeneralChat-006 | Tool列表可正常注入 | 反射注入 | 注入成功 |
| TC-GeneralChat-007 | @Autowired(required=false)支持 | 注解检查 | required=false |
| TC-GeneralChat-008 | doTextApply方法可接收Tool | 方法存在 | 存在 |
| TC-GeneralChat-009 | doMultimodalApply存在 | 方法存在 | 存在 |
| TC-GeneralChat-010 | 继承AbstractExecuteSupport | 父类检查 | 继承正确 |
| TC-GeneralChat-011 | @Service注解存在 | Bean名称检查 | generalChatNode |
| TC-GeneralChat-012 | RECOGNIZED_INTENT_KEY常量存在 | 常量检查 | 存在 |
| TC-GeneralChat-013 | sendSseResult方法可访问 | 方法检查 | 可访问 |

> **Task:** 实现完成后，在 `GeneralChatNode` 中添加 `searchEpisodicMemoryCallbacks` 字段和 `buildSystemPrompt` 方法，然后运行此测试文件验证。

### 9.4 Mock策略

根据项目规范"测试用例应mock掉中间件，只测试本层代码逻辑"：

| 依赖组件 | Mock方式 |
|----------|----------|
| Mem0RestClient | Mockito Mock |
| StringRedisTemplate | Mockito Mock |
| IStockDataProvider | Mockito Mock |
| ChatClient | 不在单元测试范围 |
| OSSUploadService | 不在单元测试范围 |

### 9.5 验收标准

所有测试文件编写完成后，需满足：

1. **编译通过**: `mvn compile test-compile` 无错误
2. **测试通过**: `mvn test` 所有用例通过
3. **覆盖率目标**: 核心业务逻辑覆盖率 > 80%
4. **命名规范**: 测试方法以 `test` 开头，用例ID作为注释

### 9.6 测试文件清单

```
ai-agent-study-domain/src/test/java/denny/ai/agent/domain/
├── model/valobj/
│   ├── CrossSessionMemoryPropertiesTest.java      [待创建]
│   └── CrossSessionMemoryPropertiesConfigTest.java [待创建]
├── service/
│   └── episodicmemory/
│       └── IEpisodicMemoryServiceTest.java       [待创建]
└── service/auto/step/chat/
    └── GeneralChatNodeToolInjectionTest.java     [待创建]

ai-agent-study-infrastructure/src/test/java/denny/ai/agent/infrastructure/
├── service/
│   ├── episodicmemory/
│   │   └── EpisodicMemoryServiceImplTest.java   [待创建]
│   └── persona/
│       └── UserPersonaCacheServiceImplTest.java [待创建]
└── tools/
    ├── AbstractToolCallbackTest.java              [待创建]
    ├── EpisodicMemoryToolCallbacksTest.java      [待创建]
    └── EpisodicMemoryToolCallbackProviderTest.java [待创建]
```

### 9.7 常用测试数据

```java
// 用户ID
private static final String TEST_USER_ID = "user-001";
private static final String TEST_USER_ID_2 = "user-002";

// 查询关键词
private static final String TEST_QUERY = "上次讨论的项目";
private static final String EMPTY_QUERY = "";
private static final String NULL_QUERY = null;

// 情景记忆结果
private static final String MEMORY_CONTENT = "上次讨论的结果是...";
private static final double TEST_SCORE = 0.95;

// 配置值
private static final int DEFAULT_LIMIT = 5;
private static final int TTL_MINUTES = 5;
```

---

## 10. 风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| LLM 不调用 Tool | 功能失效 | 在 Tool 描述中强调调用场景 |
| Tool 调用过多 | 延迟增加 | 配置 limit=5，Mem0 自身有截断 |
| 重命名遗漏引用 | 编译失败 | 遍历全部 Java 文件，确保 import 和引用全部更新 |
| 异常阻塞 LLM | 回答失败 | Tool 异常时返回友好错误信息，不抛异常 |

---

## 11. 后续优化方向（可选）

| 优化项 | 描述 | 优先级 |
|--------|------|--------|
| Tool 选择性注入 | 按 Client ID 选择是否注入 Tool | P2 |
| Tool 调用次数限制 | 防止 LLM 过度调用 | P2 |
| 相似度阈值过滤 | 过滤 score < 0.7 的结果 | P2 |

---

## 12. 后续维护

1. **重命名阶段**: 实现完成后，类重命名时需同步更新测试文件中的类名引用
2. **字段变更**: 如有字段增减，需同步更新相关测试用例
3. **回归测试**: 每次代码变更后运行完整测试套件
