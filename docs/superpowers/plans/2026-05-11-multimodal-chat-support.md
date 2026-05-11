# 多模态对话支持 (Multimodal Chat Support) 功能开发

> **创建时间:** 2026-05-11
> **功能概述:** 在现有对话架构上增加图片输入支持，用户上传图片到 OSS 后，通过 Qwen VL 模型解析图片内容，结合用户问题返回多模态理解结果
> **架构决策:** 复用现有 ChatClient 架构，通过 inputType=1 判断图片输入，在 GeneralChatNode 中增加图片分支，使用 Spring AI 原生多模态 API 注入 OSS URL

---

## 1. 背景

**当前架构:**
- `ExecuteCommandEntity` 已有 `inputType`(0=text 1=image) 和 `file`(MultipartFile) 字段
- 图片上传走 `OSSUploadService` 到 OSS，返回公网 URL
- `QwenService` 已实现图片识别（但用的是 Base64 方式）
- ChatClient 通过 `getChatClientByClientId(clientId, taskType)` 获取
- 数据库 `ai_client` / `ai_client_model` 表配置 clientId 与模型的绑定关系

**目标架构:**

```
用户请求（带图片）
    │
    ▼
RootNode → IntentRoutingNode（无 aiAgentId 时）
    │         或
    │    Step1AnalyzerNode（有 aiAgentId 时）
    │
    ▼
GeneralChatNode
    │
    ├─ inputType == 0 ──→ 纯文本对话（现有逻辑）
    │
    └─ inputType == 1 ──→ 多模态对话
                              │
                              ▼
                         1. OSSUploadService.upload(file) → ossUrl
                              │
                              ▼
                         2. getChatClientByClientId("multimodal", 0)
                              │
                              ▼
                         3. chatClient.prompt()
                               .user(u -> u.text(message).image(ossUrl))
                               .call() → 多模态响应
```

---

## 2. 核心设计

### 2.1 图片输入判断

在 `GeneralChatNode.doApply()` 入口增加判断：

```java
if (request.getInputType() != null && request.getInputType() == 1 && request.getFile() != null) {
    return doMultimodalApply(request, dynamicContext);
}
return doTextApply(request, dynamicContext);
```

### 2.2 多模态消息构建

使用 Spring AI 原生的 `user(text).image(url)` API：

```java
String response = chatClient.prompt()
        .system(systemPrompt)
        .user(u -> u.text(userMessage).image(ossUrl))
        .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, request.getSessionId())
                .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 1024))
        .call().content();
```

### 2.3 ChatClient 获取

复用现有方式，根据配置获取支持多模态的 ChatClient：

```java
// 方式1：从 dynamicContext 的 flowConfig 获取 clientId
Map<String, AiAgentClientFlowConfigVO> flowConfigMap = dynamicContext.getAiAgentClientFlowConfigVOMap();
AiAgentClientFlowConfigVO flowConfig = flowConfigMap.get("multimodal"); // 或根据 intent 路由
String clientId = (flowConfig != null) ? flowConfig.getClientId() : "default";

// 方式2：直接从 ArmoryObjectRegistry 获取（硬编码 clientId 方式）
ChatClient chatClient = getChatClientByClientId("multimodal", 0);
```

### 2.4 OSS URL 获取

```java
String ossUrl = ossUploadService.upload(request.getFile());
if (ossUrl == null || ossUrl.isEmpty()) {
    throw new RuntimeException("图片上传 OSS 失败");
}
```

### 2.5 图片上传安全校验（REVIEW-1: 已确认，方案 A）

**问题**: 当前 `OSSUploadService.upload()` 无文件大小和类型校验，存在安全风险：
- 无文件大小限制，可上传 GB 级文件耗尽 OSS 资源
- 无文件类型白名单，可上传危险文件（`.jsp`、`.exe` 等）
- 文件名直接作为 OSS key，存在路径穿越风险

**确认方案**: 方案 A — 增强现有 `OSSUploadService`，增加校验层

**实现要点**:
1. **文件大小限制**: 最大 10MB（`MAX_FILE_SIZE = 10 * 1024 * 1024`）
2. **文件类型白名单**: `image/jpeg`、`image/png`、`image/gif`、`image/webp`
3. **文件名安全**: 使用 UUID 生成文件名，原扩展名仅用于类型标识
4. **异常信息**: 校验失败时抛出明确 `IllegalArgumentException`

```java
private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

public String upload(MultipartFile file) {
    if (file.isEmpty()) {
        throw new IllegalArgumentException("上传文件为空");
    }
    if (file.getSize() > MAX_FILE_SIZE) {
        throw new IllegalArgumentException("文件大小超过限制，最大支持 10MB");
    }
    String contentType = file.getContentType();
    if (!ALLOWED_TYPES.contains(contentType)) {
        throw new IllegalArgumentException("不支持的文件类型，仅支持 jpeg/png/gif/webp");
    }
    String originalFilename = file.getOriginalFilename();
    String extension = originalFilename != null && originalFilename.contains(".")
        ? originalFilename.substring(originalFilename.lastIndexOf("."))
        : "";
    String key = UUID.randomUUID().toString().replace("-", "") + extension;
    // ... 后续上传逻辑不变
}
```

**验收标准**:
- 上传超过 10MB 的文件，返回 `IllegalArgumentException("文件大小超过限制，最大支持 10MB")`
- 上传非图片类型文件（如 `.html`、`.exe`），返回 `IllegalArgumentException("不支持的文件类型，仅支持 jpeg/png/gif/webp")`
- 上传正常图片，返回 OSS URL

---

## 3. 文件变更清单

### 3.1 修改文件 (2个)

| # | 文件 | 改动 |
|---|------|------|
| 1 | `domain/.../service/auto/step/chat/GeneralChatNode.java` | 注入 OSSUploadService，增加 `inputType==1` 图片分支，抽取 `doTextApply()` 方法 |
| 2 | `domain/.../service/oss/OSSUploadService.java` | 增加文件大小校验（最大10MB）、类型白名单校验、UUID文件名生成 |

### 3.2 新增文件 (1个)

| # | 文件 | 职责 |
|---|------|------|
| 1 | `domain/.../service/auto/step/chat/MultimodalChatNode.java` | 多模态对话专用节点（可复用 GeneralChatNode 的图片逻辑，也可独立） |

### 3.3 数据库配置 (DDL)

| # | 表 | 改动 |
|---|------|------|
| 1 | `ai_client` | 新增多模态客户端记录 `multimodal` |
| 2 | `ai_client_model` | 新增多模态模型记录（如 qwen-vl-plus） |
| 3 | `ai_client_config` | 建立 client-model 关联 |
| 4 | `ai_agent_flow_config` | 将 `multimodal` clientId 绑定到 agent（可选） |

---

## 4. 任务列表

### Task 1: 修改 GeneralChatNode，注入 OSSUploadService 并增加图片分支

**文件:**
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/chat/GeneralChatNode.java`

**Step 1: 注入 OSSUploadService**

在 `GeneralChatNode` 类中增加依赖注入：

```java
import denny.ai.agent.domain.service.oss.OSSUploadService;

@Slf4j
@Service("generalChatNode")
public class GeneralChatNode extends AbstractExecuteSupport {

    @Resource
    private OSSUploadService ossUploadService;

    // ... 原有代码保持不变 ...
```

**Step 2: 修改 doApply 方法，增加图片判断分支**

将原有的 `doApply` 方法内逻辑抽取为 `doTextApply()`，新增 `doMultimodalApply()`：

```java
@Override
protected String doApply(ExecuteCommandEntity request,
                        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
    // 判断是否有图片输入
    if (request.getInputType() != null && request.getInputType() == 1 && request.getFile() != null) {
        return doMultimodalApply(request, dynamicContext);
    }
    return doTextApply(request, dynamicContext);
}
```

**Step 3: 新增 doTextApply 方法（抽取自原 doApply）**

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

    String systemPrompt = resolveSystemPrompt(recognizedIntent);
    ChatClient chatClient = getChatClientByClientId("default", 0);

    String response = chatClient.prompt()
            .system(systemPrompt)
            .user(request.getMessage())
            .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, request.getSessionId())
                    .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 0))  // 多模态禁用 ChatMemory（spring-ai-alibaba Mem0 不支持多模态消息）
            .call().content();

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
```

**Step 4: 新增 doMultimodalApply 方法**

```java
private String doMultimodalApply(ExecuteCommandEntity request,
                                  DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
    log.info("=== 多模态对话开始 ===");

    sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
            .type("system")
            .subType("multimodal_start")
            .content("正在识别图片...")
            .completed(false)
            .timestamp(System.currentTimeMillis())
            .build());

    // Step 1: 上传图片到 OSS，获取 URL
    String ossUrl = ossUploadService.upload(request.getFile());
    if (ossUrl == null || ossUrl.isEmpty()) {
        throw new RuntimeException("图片上传 OSS 失败");
    }
    log.info("图片上传成功，OSS URL: {}", ossUrl);

    // Step 2: 获取 ChatClient（从 dynamicContext 的 flowConfig 获取，或硬编码 clientId）
    Map<String, AiAgentClientFlowConfigVO> flowConfigMap = dynamicContext.getAiAgentClientFlowConfigVOMap();
    String clientId = "multimodal"; // 默认值，可根据配置动态获取
    if (flowConfigMap != null && flowConfigMap.containsKey("multimodal")) {
        clientId = flowConfigMap.get("multimodal").getClientId();
    }

    ChatClient chatClient = getChatClientByClientId(clientId, 0);

    // Step 3: 构建用户消息
    String userMessage = request.getMessage() != null ? request.getMessage() : "请描述这张图片的内容";

    // Step 4: 调用多模态对话
    String response = chatClient.prompt()
            .system(GENERAL_CHAT_SYSTEM_PROMPT)
            .user(u -> u.text(userMessage).image(ossUrl))
            .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, request.getSessionId())
                    .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 0))  // 多模态禁用 ChatMemory（spring-ai-alibaba Mem0 不支持多模态消息）
            .call().content();

    sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
            .type("content")
            .subType("multimodal_response")
            .content(response)
            .completed(true)
            .timestamp(System.currentTimeMillis())
            .build());

    dynamicContext.setCompleted(true);
    dynamicContext.setValue("generalChatResponse", response);

    sendCompleteResult(dynamicContext, request.getSessionId());

    log.info("多模态对话完成: ossUrl={}, responseLength={}", ossUrl, response.length());
    return response;
}
```

**Step 5: 新增 import**

```java
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import java.util.Map;
```

**Step 6: 编译验证**

运行: `mvn compile -pl ai-agent-study-domain -q`
预期: 编译成功，无 error

---

### Task 2: 数据库配置多模态客户端

**文件:**
- `docs/dev-ops/mysql/sql/ai-agent-station-study.sql`（或提供独立 DDL）

**Step 1: 新增多模态客户端记录**

```sql
-- 检查是否已存在
SELECT * FROM ai_client WHERE client_id = 'multimodal';

-- 新增多模态客户端（如果不存在）
INSERT INTO ai_client (client_id, client_name, description, status, create_time, update_time)
SELECT 'multimodal', '多模态对话客户端', '支持图片输入的对话客户端', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ai_client WHERE client_id = 'multimodal');
```

**Step 2: 新增多模态模型记录**

```sql
-- 检查是否已存在
SELECT * FROM ai_client_model WHERE model_id = 'qwen_vl_plus';

-- 新增多模态模型（qwen-vl-plus，支持 URL 方式传图）
INSERT INTO ai_client_model (id, model_id, api_id, model_name, model_type, status, create_time, update_time)
SELECT 100, 'qwen_vl_plus', 'dashscope', 'qwen-vl-plus', 'openai', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ai_client_model WHERE model_id = 'qwen_vl_plus');
```

**Step 3: 建立 client-model 关联**

```sql
-- 检查是否已存在
SELECT * FROM ai_client_config WHERE source_type = 'client' AND source_id = 'multimodal' AND config_type = 'model';

-- 关联 client 与 model
INSERT INTO ai_client_config (id, source_type, source_id, config_type, config_id, create_time, update_time)
SELECT 200, 'client', 'multimodal', 'model', 'qwen_vl_plus', NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM ai_client_config 
    WHERE source_type = 'client' AND source_id = 'multimodal' AND config_type = 'model'
);
```

**Step 4: 绑定到 agent flow config（可选）**

```sql
-- 将 multimodal clientId 绑定到默认 agent
INSERT INTO ai_agent_flow_config (id, agent_id, client_id, client_name, client_type, sequence, step_prompt, create_time, update_time)
SELECT 300, 'default', 'multimodal', '多模态对话', 'multimodal', 1, NULL, NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM ai_agent_flow_config 
    WHERE agent_id = 'default' AND client_id = 'multimodal'
);
```

---

### Task 3: 单元测试

**文件:**
- Create: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/chat/MultimodalChatNodeTest.java`

**Step 1: 编写多模态对话测试**

```java
package denny.ai.agent.domain.service.auto.step.chat;

import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.oss.OSSUploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MultimodalChatNodeTest {

    @Mock
    private OSSUploadService ossUploadService;

    @InjectMocks
    private GeneralChatNode generalChatNode;

    @Test
    void testDoMultimodalApply_withImage_success() throws Exception {
        // given: Mock OSS 返回 URL
        String expectedOssUrl = "https://oss.example.com/ai-agent/test-image.png";
        when(ossUploadService.upload(any())).thenReturn(expectedOssUrl);

        // given: 构建图片请求
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "test.png", "image/png", "fake image content".getBytes()
        );
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .message("这张图片里有什么？")
                .sessionId("test-session-001")
                .inputType(1)  // 图片输入
                .file(mockFile)
                .userId("test-user")
                .build();

        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setSessionId("test-session-001");

        // when: 验证 OSS 上传被调用
        verify(ossUploadService, times(1)).upload(any());
    }

    @Test
    void testDoMultimodalApply_withOssUrl_failure() {
        // given: Mock OSS 返回 null（上传失败）
        when(ossUploadService.upload(any())).thenReturn(null);

        // given: 构建图片请求
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "test.png", "image/png", "fake image content".getBytes()
        );
        ExecuteCommandEntity request = ExecuteCommandEntity.builder()
                .message("这张图片里有什么？")
                .sessionId("test-session-001")
                .inputType(1)
                .file(mockFile)
                .userId("test-user")
                .build();

        // when & then: 验证抛出异常
        assertThrows(RuntimeException.class, () -> {
            // 模拟 doMultimodalApply 逻辑
            String ossUrl = ossUploadService.upload(request.getFile());
            if (ossUrl == null || ossUrl.isEmpty()) {
                throw new RuntimeException("图片上传 OSS 失败");
            }
        });
    }
}
```

**Step 2: 运行测试**

运行: `mvn test -pl ai-agent-study-domain -Dtest=MultimodalChatNodeTest -q`
预期: 测试通过

---

### Task 4: 编译验证

**Step 1: 全量编译**

运行: `mvn compile -pl ai-agent-study-domain -am -q`
预期: 编译成功，无 error

**Step 2: 运行相关单元测试**

运行: `mvn test -pl ai-agent-study-domain -Dtest=GeneralChatNodeTest -q`
预期: 测试通过

---

## 5. 配置说明

### 5.1 模型配置要求

`ai_client_model` 表中 `model_name` 必须配置为支持多模态的模型：

| 模型 | model_name | 支持 URL 传图 | 备注 |
|------|-----------|-------------|------|
| 通义千问 VL | `qwen-vl-plus` | ✅ | 阿里云 DashScope |
| 通义千问 VL | `qwen-vl-max` | ✅ | 阿里云 DashScope |
| GPT-4o | `gpt-4o` | ✅ | OpenAI |
| GPT-4 Vision | `gpt-4-vision-preview` | ✅ | OpenAI |
| Claude 3 Sonnet | `claude-3-sonnet-20240229` | ✅ | Anthropic |

### 5.2 API 配置要求

`ai_client_api` 表中 `base_url` 和 `api_key` 必须正确配置：

```sql
-- DashScope API 配置示例
INSERT INTO ai_client_api (id, api_id, api_name, base_url, api_key, status, create_time, update_time)
SELECT 50, 'dashscope', '阿里云 DashScope', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '${DASHSCOPE_API_KEY}', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ai_client_api WHERE api_id = 'dashscope');
```

---

## 6. 验收标准

| # | 标准 | 验证方式 |
|---|------|---------|
| 1 | `inputType=1` 且带图片时，走多模态分支 | 单元测试 Mock OSSUploadService 验证分支走向 |
| 2 | `inputType=0` 或无图片时，走纯文本分支 | 单元测试验证调用 `doTextApply` |
| 3 | OSS 上传失败时抛出明确异常 | 单元测试验证 `RuntimeException("图片上传 OSS 失败")` |
| 4 | ChatClient 调用使用 `.user(u -> u.text().image())` 格式 | 代码审查或集成测试 |
| 5 | 数据库配置 `multimodal` clientId 与 `qwen-vl-plus` modelId 关联正确 | SQL 查询验证 |

---

## 7. 可选优化项

- [ ] **意图路由识别图片**: 在 `IntentRoutingService` 中识别 `inputType=1`，自动路由到多模态节点
- [ ] **多图支持**: 扩展为 `List<MultipartFile>`，支持一次上传多张图片
- [ ] **图片压缩**: 在上传 OSS 前压缩图片，减少传输时间
- [ ] **意图识别扩展**: 在 `IntentTypeEnum` 中增加 `MULTIMODAL_CHAT` 枚举值

---

## 8. REVIEW 决策记录

| # | 问题 | 确认方案 | 状态 |
|---|------|---------|------|
| REVIEW-1 | 文件安全校验缺失 | 方案 A — 增强 OSSUploadService，增加大小/类型校验和 UUID 文件名 | ✅ 已确认 |
| REVIEW-2 | Memory 兼容性 | 方案 A — 多模态场景禁用 ChatMemory（`CHAT_MEMORY_RETRIEVE_SIZE_KEY = 0`） | ✅ 已确认 |
| REVIEW-3 | 意图路由未处理 inputType=1 | 方案 A — 在 IntentRoutingNode 中优先判断 inputType=1，优先于文本意图识别 | ✅ 已确认 | `docs/superpowers/plans/2026-05-11-multimodal-chat-support.md`
