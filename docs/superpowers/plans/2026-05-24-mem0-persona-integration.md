# Mem0 Persona 个人画像接入方案

**Metadata:**
- 状态: implemented
- 预估工时: 2h
- 日期: 2026-05-24
- 完成日期: 2026-05-24

## 任务状态

| 任务 | 状态 |
|------|------|
| 8. 测试用例设计 | pass |

> **For agentic workers:** Use superpowers:subagent-driven-development to implement this plan.

**目标：** 用 Mem0 Persona 画像替代跨会话情景记忆，作为会话初始化的上下文来源。

---

## 1. 现状分析

### 1.1 现有架构

```
Step1AnalyzerNode
       │
       ├── 跨会话情景记忆 (ICrossSessionMemoryCacheService)
       │         │
       │         └── mem0:cross-session:{userId} (Redis)
       │                   │
       │                   └── Mem0RestClient.searchMemories()
       │
       └── 注入 dynamicContext.setValue("crossSessionMemories", ...)
```

### 1.2 目标架构

```
Step1AnalyzerNode
       │
       ├── 用户画像 (ICrossSessionMemoryCacheService) ← 复用，改造查询逻辑
       │         │
       │         └── mem0:persona:{userId} (Redis) ← 改 key 前缀
       │                   │
       │                   └── Mem0RestClient.getPersona() ← 新增
       │
       └── 注入 dynamicContext.setValue("persona", ...)
```

---

## 2. 改动清单

| 序号 | 文件 | 改动 |
|------|------|------|
| 1 | `ai-agent-study-domain/.../service/crossmemory/ICrossSessionMemoryCacheService.java` | 修改 CACHE_KEY_PREFIX |
| 2 | `ai-agent-study-infrastructure/.../crossmemory/CrossSessionMemoryCacheServiceImpl.java` | 改造 queryFromMem0() |
| 3 | `ai-agent-study-infrastructure/.../mem0/Mem0RestClient.java` | 新增 getPersona() |
| 4 | `ai-agent-study-domain/.../step/pe/Step1AnalyzerNode.java` | 修改上下文 key |

---

## 3. 详细改动

### 3.1 ICrossSessionMemoryCacheService.java

修改缓存 key 前缀：

```java
// 第 17 行
- String CACHE_KEY_PREFIX = "mem0:cross-session:";
+ String CACHE_KEY_PREFIX = "mem0:persona:";
```

### 3.2 CrossSessionMemoryCacheServiceImpl.java

#### 3.2.1 修改 queryFromMem0() 方法

将语义搜索改为调用 `getPersona()`：

```java
// 第 83-96 行，替换原有 queryFromMem0 方法
private String queryFromMem0(String userId) {
    try {
        String persona = mem0RestClient.getPersona(userId);
        if (persona != null && !persona.isEmpty()) {
            return formatPersonaResult(persona);
        }
        return "";
    } catch (Exception e) {
        log.warn("Mem0 查询用户画像失败，降级返回空, userId={}, error={}", userId, e.getMessage());
        return "";
    }
}

private String formatPersonaResult(String persona) {
    if (persona == null || persona.isEmpty()) {
        return "";
    }
    return "\n\n[用户画像]\n" + persona;
}
```

#### 3.2.2 删除不需要的方法

删除 `formatMem0Result()` 方法（不再需要语义搜索结果格式化）。

### 3.3 Mem0RestClient.java

新增 `getPersona()` 方法：

```java
// 第 82 行后新增
/**
 * 获取用户画像
 *
 * @param userId 用户ID
 * @return 画像文本，无画像时返回 null
 */
public String getPersona(String userId) {
    try {
        String url = baseUrl + "/mem0/persona/" + userId;
        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
        if (response.getBody() != null) {
            Object data = response.getBody().get("data");
            return data != null ? data.toString() : null;
        }
        return null;
    } catch (Exception e) {
        log.warn("Mem0 getPersona 失败, userId={}, error={}", userId, e.getMessage());
        return null;
    }
}
```

### 3.4 Step1AnalyzerNode.java

#### 3.4.1 修改上下文注入（第 55 行）

```java
// 第 55 行
- dynamicContext.setValue("crossSessionMemories", formattedMemories);
+ dynamicContext.setValue("persona", memories);
```

#### 3.4.2 修改 Prompt 占位符（第 90 行）

```java
// 第 90 行
- dynamicContext.getValue("crossSessionMemories")
+ dynamicContext.getValue("persona")
```

#### 3.4.3 修改日志输出（第 56-57 行）

```java
// 第 56-57 行
- log.info("已注入跨会话记忆到上下文, userId={}, hasMemory={}", ...);
+ log.info("已注入用户画像到上下文, userId={}, hasPersona={}", ...);
```

---

## 4. 配置文件

复用现有 `chat.memory` 配置，无需新增：

```yaml
chat:
  memory:
    enabled: true
    redis-ttl-hours: 24
    max-cache-size: 20
```

---

## 5. Redis 数据迁移（如需要）

如需迁移旧数据，可执行：

```bash
# 方式1：重命名 key（Redis 6.2+）
redis-cli RENAME mem0:cross-session:{userId} mem0:persona:{userId}

# 方式2：TTL 不变，直接删除旧 key
redis-cli DEL mem0:cross-session:{userId}
```

---

## 6. 验收标准

- [ ] `Mem0RestClient.getPersona()` 方法正常返回画像文本
- [ ] Redis 缓存 key 格式为 `mem0:persona:{userId}`
- [ ] `Step1AnalyzerNode` 注入 `persona` 到上下文
- [ ] Prompt 占位符正确引用 `persona`
- [ ] 编译通过

---

## 7. 关键技术决策

| 决策 | 方案 | 理由 |
|------|------|------|
| 复用现有服务 | ICrossSessionMemoryCacheService | 减少新建文件，利用现有缓存逻辑 |
| 查询接口 | `GET /mem0/persona/{user_id}` | Mem0 官方提供 O(1) 获取 |
| 缓存策略 | 复用现有 Redis 缓存 | 仅修改 key 前缀 |

---

## 8. 测试用例设计

### 8.1 测试范围

| 序号 | 文件 | 改动类型 | 需要测试 |
|------|------|----------|----------|
| 1 | `ICrossSessionMemoryCacheService.java` | 修改常量 | 缓存 key 格式验证 |
| 2 | `CrossSessionMemoryCacheServiceImpl.java` | 重构方法 | 画像查询、格式化、缓存逻辑 |
| 3 | `Mem0RestClient.java` | 新增方法 | `getPersona()` |
| 4 | `Step1AnalyzerNode.java` | 上下文 key 变更 | 上下文注入验证 |

### 8.2 新增测试用例

#### 8.2.1 Mem0RestClient.getPersona() 测试

**测试文件**: `ai-agent-study-infrastructure/src/test/java/denny/ai/agent/infrastructure/mem0/Mem0RestClientTest.java`

```java
/**
 * TC-Mem0-001: getPersona 正常返回画像数据
 */
@Test
void getPersonaShouldReturnPersonaData() {
    // Given: Mock Mem0 服务返回画像数据
    mockServer.expect(requestTo("http://localhost:8889/mem0/persona/user-001"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                    {"data": "用户画像: 喜欢咖啡, 工作狂, 常用上海地点"}
                    """, MediaType.APPLICATION_JSON));

    // When
    String persona = client.getPersona("user-001");

    // Then
    assertNotNull(persona);
    assertEquals("用户画像: 喜欢咖啡, 工作狂, 常用上海地点", persona);
    mockServer.verify();
}

/**
 * TC-Mem0-002: getPersona 无画像时返回 null
 */
@Test
void getPersonaShouldReturnNullWhenNoData() {
    mockServer.expect(requestTo("http://localhost:8889/mem0/persona/user-new"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    String persona = client.getPersona("user-new");

    assertNull(persona);
    mockServer.verify();
}

/**
 * TC-Mem0-003: getPersona Mem0 服务异常时返回 null（不抛异常）
 */
@Test
void getPersonaShouldReturnNullOnError() {
    mockServer.expect(requestTo("http://localhost:8889/mem0/persona/user-error"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withServerError());

    String persona = client.getPersona("user-error");

    assertNull(persona);
}

/**
 * TC-Mem0-004: getPersona 响应体无 data 字段时返回 null
 */
@Test
void getPersonaShouldReturnNullWhenDataFieldMissing() {
    mockServer.expect(requestTo("http://localhost:8889/mem0/persona/user-no-data"))
            .andRespond(withSuccess("""
                    {"status": "ok"}
                    """, MediaType.APPLICATION_JSON));

    String persona = client.getPersona("user-no-data");

    assertNull(persona);
}
```

#### 8.2.2 CrossSessionMemoryCacheServiceImpl 测试

**测试文件**: `ai-agent-study-infrastructure/src/test/java/denny/ai/agent/infrastructure/service/crossmemory/CrossSessionMemoryCacheServiceImplTest.java` (新建)

```java
/**
 * TC-Cache-001: 缓存 key 前缀为 mem0:persona:
 */
@Test
void testCacheKeyPrefix_IsPersona() {
    assertEquals("mem0:persona:", ICrossSessionMemoryCacheService.CACHE_KEY_PREFIX);
}

/**
 * TC-Cache-002: Redis 命中时返回格式化后的画像
 */
@Test
void testCacheHit_ReturnsFormattedPersona() {
    when(stringRedisTemplate.opsForValue().get("mem0:persona:user-001"))
            .thenReturn("用户画像: 喜欢咖啡");

    String result = service.getCrossSessionMemories("user-001");

    assertEquals("用户画像: 喜欢咖啡", result);
    verify(stringRedisTemplate).expire(eq("mem0:persona:user-001"), 
            eq(30L), eq(TimeUnit.MINUTES));
}

/**
 * TC-Cache-003: Redis 未命中时调用 Mem0 并回填缓存
 */
@Test
void testCacheMiss_QueriesMem0AndFillsCache() {
    when(stringRedisTemplate.opsForValue().get(anyString())).thenReturn(null);
    when(mem0RestClient.getPersona("user-002")).thenReturn("用户画像: 工作狂");

    String result = service.getCrossSessionMemories("user-002");

    assertEquals("用户画像: 工作狂", result);
    verify(stringRedisTemplate).opsForValue().set(
            eq("mem0:persona:user-002"), 
            eq("用户画像: 工作狂"), 
            eq(30L), 
            eq(TimeUnit.MINUTES));
}

/**
 * TC-Cache-004: Mem0 返回空时返回空字符串，不写入缓存
 */
@Test
void testMem0ReturnsNull_ReturnsEmptyAndNotCache() {
    when(stringRedisTemplate.opsForValue().get(anyString())).thenReturn(null);
    when(mem0RestClient.getPersona("user-new")).thenReturn(null);

    String result = service.getCrossSessionMemories("user-new");

    assertEquals("", result);
    verify(stringRedisTemplate, never()).opsForValue().set(
            anyString(), anyString(), anyLong(), any());
}

/**
 * TC-Cache-005: Mem0 查询异常时降级返回空字符串
 */
@Test
void testMem0Exception_DegradesGracefully() {
    when(stringRedisTemplate.opsForValue().get(anyString())).thenReturn(null);
    when(mem0RestClient.getPersona("user-error"))
            .thenThrow(new RuntimeException("Network error"));

    String result = service.getCrossSessionMemories("user-error");

    assertEquals("", result);
}

/**
 * TC-Cache-006: Redis 未配置时直接查 Mem0
 */
@Test
void testRedisNotConfigured_QueriesMem0Directly() throws Exception {
    setField(service, "stringRedisTemplate", null);
    when(mem0RestClient.getPersona("user-offline")).thenReturn("离线用户画像");

    String result = service.getCrossSessionMemories("user-offline");

    assertEquals("离线用户画像", result);
}

/**
 * TC-Cache-007: refreshTtl 正常刷新 TTL
 */
@Test
void testRefreshTtl_RefreshesSuccessfully() {
    service.refreshTtl("user-001");

    verify(stringRedisTemplate).expire(eq("mem0:persona:user-001"), 
            eq(30L), eq(TimeUnit.MINUTES));
}
```

#### 8.2.3 Step1AnalyzerNode 测试

**测试文件**: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/pe/Step1AnalyzerNodeTest.java` (新建)

```java
/**
 * TC-Pe-001: 验证上下文注入 key 为 "persona"
 */
@Test
void testContextInjection_UsesPersonaKey() throws Exception {
    setupMocks("user-persona", "用户画像: 咖啡爱好者");
    ExecuteCommandEntity request = buildRequest();

    node.process(request, dynamicContext);

    assertNotNull(dynamicContext.getValue("persona"));
    assertEquals("用户画像: 咖啡爱好者", dynamicContext.getValue("persona"));
}

/**
 * TC-Pe-002: 无用户画像时注入空字符串
 */
@Test
void testNoPersona_InjectsEmptyString() throws Exception {
    setupMocks("user-no-persona", "");
    ExecuteCommandEntity request = buildRequest();

    node.process(request, dynamicContext);

    assertEquals("", dynamicContext.getValue("persona"));
}

/**
 * TC-Pe-003: 验证日志输出包含 "用户画像"
 */
@Test
void testLogOutput_ContainsPersonaKeyword() throws Exception {
    setupMocks("user-log", "测试画像");

    node.process(buildRequest(), dynamicContext);

    assertTrue(logOutput.getFormattedMessage().contains("已注入用户画像到上下文"));
}
```

### 8.3 回归测试用例

以下现有测试用例需要**重新执行**以验证改造不会破坏现有功能：

| 测试文件 | 测试用例 | 验证目标 |
|----------|----------|----------|
| `Mem0RestClientTest.java` | `addMemoryShouldPostMemoryPayload` | addMemory 方法不受影响 |
| `Mem0RestClientTest.java` | `searchMemoriesShouldPostSearchPayloadAndMapResults` | searchMemories 方法不受影响 |
| `Mem0RestClientTest.java` | `getAllMemoriesShouldBuildUrlWithNonNullQueryParams` | getAllMemories 方法不受影响 |
| `ChatMemoryPersistenceServiceTest.java` | `test01_PersistConversation_NewSession` | 会话持久化不受影响 |
| `ChatMemoryPersistenceServiceTest.java` | `test03_GetConversationHistory_FromRedis` | Redis 缓存读取不受影响 |
| `RootNodeTest.java` | `testExplicitPEAgent_routesToStep1Analyzer` | 路由逻辑不受影响 |

### 8.4 测试执行计划

| 阶段 | 测试内容 | 优先级 |
|------|----------|--------|
| 1 | 新增 `Mem0RestClientTest.getPersona*()` 4个用例 | P0 |
| 2 | 新增 `CrossSessionMemoryCacheServiceImplTest` 7个用例 | P0 |
| 3 | 新增/扩展 `Step1AnalyzerNodeTest` 3个用例 | P1 |
| 4 | 执行全部回归测试 | P0 |
| 5 | 集成测试（启动应用，完整对话流程） | P1 |

### 8.5 Mock 策略

```java
// Mem0RestClient mock
Mem0RestClient mem0Client = mock(Mem0RestClient.class);
when(mem0Client.getPersona(anyString())).thenReturn("mocked persona");

// Redis mock
StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
when(redisTemplate.opsForValue().get(anyString())).thenReturn(null);
```
