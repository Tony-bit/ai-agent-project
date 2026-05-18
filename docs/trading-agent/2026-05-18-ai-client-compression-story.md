# Story: AI Client 上下文压缩能力

## 1. 背景与目标

当前 `RetryChatModel` 已实现 LLM 调用重试能力，但遇到 **1261 错误（Prompt 超长）** 时，退避重试无法解决问题。

在多轮对话场景中，Prompt 可能达到数十万 tokens，参考 Claude Code 的 Auto-Compact，实现 **LLM Summarization** 压缩能力。

### 目标

| 触发方式 | 条件 | 处理 |
|----------|------|------|
| **主动压缩** | Prompt token > 160,000（200,000 × 80%） | 先压缩再调用 |
| **被动压缩** | 收到 1261 错误 | 先压缩再重试 |

---

## 2. 技术方案

### 2.1 架构设计

```
用户请求
    ↓
TokenCountUtils.estimate(prompt) > 160,000（200,000 × 80%） ？
    ↓
┌─ 是 ─→ 设置 DynamicContext → 抛出 CompressionRequiredException
│         → 路由到 CompressionContextNode
│         → 生成摘要 + 截断（如需要）
│         → 存入 compressedPrompt → 路由回原节点继续执行
│
└─ 否 ─→ 继续正常流程
    ↓
delegate.call(prompt)
    ↓
┌─ 成功 ─→ 返回结果
│
└─ 失败 (1261) → 触发压缩流程（与主动压缩相同）
```

### 2.2 滚动压缩策略（核心设计）

参考 Claude Code Auto-Compact，压缩是**递归滚动**的过程：

```
┌──────────────────────────────────────────────────────────────────┐
│                        滚动压缩流程                                │
├──────────────────────────────────────────────────────────────────┤
│ 1. 获取消息列表（优先读 Redis，fallback MySQL）                    │
│ 2. 调用压缩模型生成摘要（输入：全部消息 → 输出：摘要文本）          │
│ 3. 取最新 2 轮对话拼在摘要后面                                    │
│ 4. 构建压缩后 Prompt：摘要 + [最近对话]                           │
│ 5. 如果仍超限：减少 [最近对话] 轮数（2→1→0）                      │
│ 6. 存入 DynamicContext.compressedPrompt，路由回原节点             │
│                                                                  │
│ ★ 原始消息存储（MySQL/Redis）保持不变                             │
│ ★ 下轮请求进来，摘要会作为输入再次参与压缩                        │
└──────────────────────────────────────────────────────────────────┘
```

**压缩后的 Prompt 结构**：

```
[压缩边界] 以下是之前对话的摘要（原始约 {tokens} tokens）：

{摘要内容}

[最近对话]  ← 最新 2 轮，可被截断
user: {content}
assistant: {content}
user: {content}
assistant: {content}

[压缩边界结束]
```

**截断策略（压缩后仍超限）**：

| 剩余 token 超出量 | 策略 |
|-------------------|------|
| < 20% 阈值 | 保留 2 轮对话 |
| 20%~50% | 减少到 1 轮对话 |
| > 50% | 移除所有历史对话，仅保留摘要 |

**边界情况处理**：

| 场景 | 处理方式 |
|------|----------|
| 消息轮数 < 2 | 全量压缩，仅保留摘要（不报错） |
| 消息轮数 = 2 | 保留全部 2 轮 |
| 消息轮数 > 2 | 按上述截断策略递减 |

> **注意**：`getRecentRounds()` 方法应做防御性判断，当消息数量不足时返回全部消息，而非抛异常。

**滚动压缩示例**：

```
第 1 轮压缩：100轮 → 摘要 + 2轮（假设摘要10k tokens）
第 2 轮压缩：摘要(10k) + 2轮 + 新增98轮 → 新摘要 + 2轮
  → 摘要会包含"上一轮的摘要作为输入的一部分"
第 3 轮压缩（边界情况）：
  - 如果当前只有1轮消息 → 仅压缩成摘要，无[最近对话]部分
```

### 2.3 DynamicContext 扩展

```java
public class DynamicContext {
    private boolean compressionRequired = false;  // 是否需要压缩
    private String returnNode;                    // 压缩完成后返回的节点
    private Prompt originalPrompt;               // 压缩前的原始 Prompt
    private Prompt compressedPrompt;             // 压缩后的 Prompt
    private Map<String, AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOMap;
}
```

### 2.4 压缩节点设计

`CompressionContextNode` 继承 `AbstractArmorySupport`，核心流程：

1. 获取压缩助手配置
2. 获取消息历史（用于压缩）
3. 调用压缩模型生成摘要
4. 构建压缩后的 Prompt（摘要 + 最新2轮）
5. **滚动截断策略**：压缩后仍超限则递减保留轮数
6. 路由回原节点

### 2.5 压缩提示词

采用中文结构，生成 9 部分摘要：

```
<分析>[思考过程]</分析>
<摘要>
1. 主要请求和意图
2. 关键技术概念
3. 文件和代码片段（完整保留）
4. 错误和修复
5. 问题解决过程
6. 所有用户消息
7. 待处理任务
8. 当前工作
9. 可选的下一步
</摘要>
```

**滚动压缩提示词**（用于后续轮次压缩）：

```
你是上下文压缩专家。你的任务是将对话历史压缩成简洁的摘要。

压缩规则：
- 保留关键决策和结论
- 保留所有代码修改（带文件名）
- 保留所有错误和解决方案
- 压缩重复操作和调试过程
- 保留待处理任务和下一步计划

请严格按以下格式输出（仅返回文本，不要调用任何工具）：
<分析>[简要分析哪些内容是重要的]</分析>
<摘要>
1. 主要请求和意图：[一句话概括用户目标]
2. 关键技术概念：[涉及的技术栈、框架、工具]
3. 文件和代码片段：[完整保留重要代码，格式：文件名:行号 代码内容]
4. 错误和修复：[遇到的问题及解决方案]
5. 问题解决过程：[关键步骤和思路转变]
6. 所有用户消息：[压缩后的用户请求列表]
7. 待处理任务：[还未完成的工作]
8. 当前工作：[当前正在进行的任务]
9. 下一步计划：[推荐的后续行动]
</摘要>
```

### 2.7 压缩后 Prompt 格式

```text
[压缩边界] 以下是之前对话的摘要（原始约 {tokens} tokens）：

{摘要内容}

[最近对话]  ← 最新 2 轮，可被滚动截断
user: {content}
assistant: {content}
user: {content}
assistant: {content}

[压缩边界结束]
```

### 2.8 滚动压缩状态持久化

为支持滚动压缩，原始消息存储保持不变，仅 `compressedPrompt` 在运行时生效：

| 存储位置 | 内容 | 用途 |
|---------|------|------|
| MySQL | 原始消息列表 | 持久化历史 |
| Redis | 原始消息缓存 | 快速读取 |
| DynamicContext | 压缩后的 Prompt | 本次调用 |

**下轮请求进入时**：
1. 重新构建 Prompt（从 Redis/MySQL 读取原始消息）
2. 将压缩后的摘要作为上下文输入给压缩模型
3. 生成新的摘要（包含历史摘要 + 新的对话）
4. 再次拼接最新 2 轮

这样确保每次压缩都是**增量式**的，摘要会随着对话轮次增长而逐步完善。

### 2.9 核心类设计

#### CompressionRequiredException.java

```java
public class CompressionRequiredException extends RuntimeException {
    private final Prompt originalPrompt;
    private final String returnNode;

    public CompressionRequiredException(Prompt originalPrompt, String returnNode) {
        super("Compression required, will route to compression node");
        this.originalPrompt = originalPrompt;
        this.returnNode = returnNode;
    }

    public Prompt getOriginalPrompt() { return originalPrompt; }
    public String getReturnNode() { return returnNode; }
}
```

#### CompressionContextNode.java

```java
@Slf4j
@Service
public class CompressionContextNode extends AbstractArmorySupport {

    private static final int DEFAULT_KEEP_ROUNDS = 2;  // 默认保留最新2轮对话

    @Resource
    private ChatMemoryPersistenceService chatMemoryService;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        // 1. 获取压缩助手配置
        Map<String, AiAgentClientFlowConfigVO> flowConfigMap = dynamicContext.getAiAgentClientFlowConfigVOMap();
        AiAgentClientFlowConfigVO compressionConfig = flowConfigMap.get(AiClientTypeEnumVO.COMPRESSION_ASSISTANT.getCode());

        // 2. 获取原始 Prompt
        Prompt originalPrompt = dynamicContext.getOriginalPrompt();
        String promptText = extractPromptText(originalPrompt);
        int originalTokenCount = TokenCountUtils.estimate(promptText);

        // 3. 获取消息历史用于压缩
        String sessionId = requestParameter.getSessionId();
        List<ChatMessageEntity> messages = chatMemoryService.getConversationHistory(sessionId);

        // 4. 调用压缩模型生成摘要
        String compressionPromptTemplate = getCompressionPromptTemplate(dynamicContext);
        String compressionRequest = buildCompressionRequest(compressionPromptTemplate, messages,
                compressionConfig.getMaxSummaryTokens());
        ChatClient chatClient = getChatClientByClientId("3202", 0);
        String summary = chatClient.prompt(compressionRequest).call().content();

        // 5. 格式化摘要
        String formattedSummary = formatSummary(summary);
        int summaryTokens = TokenCountUtils.estimate(formattedSummary);
        int threshold = getCompressionThreshold(dynamicContext);

        // 6. 构建压缩 Prompt（滚动截断策略）
        String compressedText = buildCompressedPromptWithTruncation(
                formattedSummary, messages, originalTokenCount, summaryTokens, threshold);

        // 7. 存入 DynamicContext 并路由回原节点
        dynamicContext.setCompressedPrompt(new Prompt(compressedText));
        dynamicContext.setCompressionRequired(false);
        return router(requestParameter, dynamicContext);
    }

    // === 辅助方法 ===

    /**
     * 滚动截断：优先保留最新2轮，必要时递减
     */
    private String buildCompressedPromptWithTruncation(String summary, List<ChatMessageEntity> messages,
                                                       int originalTokens, int summaryTokens, int threshold) {
        // 尝试保留 2 轮对话
        String compressedText = buildCompressedPrompt(summary, messages, originalTokens, DEFAULT_KEEP_ROUNDS);
        int compressedTokens = TokenCountUtils.estimate(compressedText);

        if (compressedTokens <= threshold) {
            return compressedText;
        }

        // 压缩后仍超限，递减保留轮数
        log.info("压缩后仍超限，尝试减少保留轮数: currentTokens={}, threshold={}", compressedTokens, threshold);

        // 尝试保留 1 轮
        compressedText = buildCompressedPrompt(summary, messages, originalTokens, 1);
        compressedTokens = TokenCountUtils.estimate(compressedText);
        if (compressedTokens <= threshold) {
            return compressedText;
        }

        // 尝试保留 0 轮（仅摘要）
        return buildCompressedPrompt(summary, null, originalTokens, 0);
    }

    private String buildCompressedPrompt(String summary, List<ChatMessageEntity> messages,
                                         int originalTokens, int keepRounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("[压缩边界] 以下是之前对话的摘要（原始约 ").append(originalTokens).append(" tokens）：\n\n");
        sb.append(summary);

        // 添加最近 N 轮对话
        if (messages != null && !messages.isEmpty() && keepRounds > 0) {
            sb.append("\n\n[最近对话]\n");
            List<ChatMessageEntity> recentMessages = getRecentRounds(messages, keepRounds);
            for (ChatMessageEntity msg : recentMessages) {
                sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n\n");
            }
        }

        sb.append("[压缩边界结束]");
        return sb.toString();
    }

    /**
     * 获取最近 N 轮对话（1轮=user+assistant=2条消息）
     * 防御性处理：消息不足时返回全部消息，不抛异常
     */
    private List<ChatMessageEntity> getRecentRounds(List<ChatMessageEntity> messages, int keepRounds) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int totalSize = messages.size();
        int targetSize = keepRounds * 2;
        // 消息不足时，返回全部消息
        if (totalSize <= targetSize) {
            return messages;
        }
        int startIndex = totalSize - targetSize;
        return messages.subList(startIndex, totalSize);
    }

    private String getCompressionPromptTemplate(DynamicContext dynamicContext) {
        Map<String, AiClientSystemPromptVO> systemPromptMap = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName());
        AiClientSystemPromptVO compressionPrompt = systemPromptMap != null ? systemPromptMap.get("7001") : null;
        return compressionPrompt != null ? compressionPrompt.getPromptContent()
                : getDefaultCompressionPromptTemplate();
    }

    private String getDefaultCompressionPromptTemplate() {
        return "重要：请仅返回文本，不要调用任何工具...\n[完整模板见2.5节]";
    }

    private String buildCompressionRequest(String template, List<ChatMessageEntity> messages, int maxTokens) {
        StringBuilder sb = new StringBuilder();
        sb.append(template).append("\n\n");
        sb.append("[待压缩对话内容]\n");
        for (ChatMessageEntity msg : messages) {
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n\n");
        }
        sb.append("[/待压缩对话内容]\n\n");
        sb.append("请生成不超过 ").append(maxTokens).append(" tokens 的摘要。");
        return sb.toString();
    }

    private String formatSummary(String rawSummary) {
        String formatted = rawSummary.replaceAll("(?i)<分析>[\\s\\S]*?</分析>", "");
        java.util.regex.Matcher matcher = Pattern.compile("(?i)<摘要>([\\s\\S]*?)</摘要>").matcher(formatted);
        if (matcher.find()) formatted = matcher.group(1).trim();
        return formatted.replaceAll("\n{3,}", "\n\n").trim();
    }

    private int getCompressionThreshold(DynamicContext dynamicContext) {
        AiAgentClientFlowConfigVO config = dynamicContext.getAiAgentClientFlowConfigVOMap()
                .get(AiClientTypeEnumVO.COMPRESSION_ASSISTANT.getCode());
        return config != null && config.getMaxSummaryTokens() > 0 ? config.getMaxSummaryTokens() : 160000;
    }

    private String extractPromptText(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (org.springframework.ai.chat.model.Message message : prompt.getInstructions()) {
            sb.append(message.getContent().toString()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
```

#### AiClientModelVO.CompressionConfig

```java
// RetryConfig 类内新增
private CompressionConfig compressionConfig;

@Data
@Builder
public static class CompressionConfig {
    private boolean enabled = false;
    private String compressionModelId;
    @Builder.Default
    private int proactiveThresholdTokens = 160000;  // 200,000 × 80%
    @Builder.Default
    private int maxCompressionAttempts = 3;
    @Builder.Default
    private int maxSummaryTokens = 2000;
}
```

#### RetryChatModel 关键改动

```java
// 新增字段
private DynamicContext dynamicContext;

// call() 方法新增主动压缩检查
if (compressionConfig != null && compressionConfig.isEnabled()) {
    int tokenCount = TokenCountUtils.estimate(prompt);
    if (tokenCount > compressionConfig.getProactiveThresholdTokens()) {
        triggerCompression(prompt);  // 抛出 CompressionRequiredException
    }
}

// 被动压缩：1261 错误时
if ("1261".equals(errorCode) && dynamicContext != null) {
    triggerCompression(prompt);
}

// stream() 方法：超阈值降级到 call()
if (tokenCount > threshold) {
    return Flux.just(call(prompt));
}

// triggerCompression 方法
private void triggerCompression(Prompt prompt) {
    if (dynamicContext != null && !dynamicContext.isCompressionRequired()) {
        dynamicContext.setOriginalPrompt(prompt);
        dynamicContext.setCompressionRequired(true);
        dynamicContext.setReturnNode("aiClientModelNode");
        throw new CompressionRequiredException(prompt, "aiClientModelNode");
    }
}
```

---

## 3. 变更计划

### 3.1 数据库变更

```sql
-- ai_client 表：新增压缩助手 (clientId=3202)
INSERT INTO `ai_client` VALUES (11, '3202', '压缩助手', '上下文压缩服务', 1, '2026-05-18 11:47:00', '2026-05-18 11:47:00');

-- ai_agent_flow_config 表：关联 Agent 3 → 压缩助手
INSERT INTO `ai_agent_flow_config` VALUES (2, '3', '3202', '压缩助手', 'COMPRESSION_ASSISTANT', 1, NULL, '2026-05-18 11:47:00');

-- ai_client_config 表：关联 clientId=3202 → advisor/model/prompt
INSERT INTO `ai_client_config` (`id`, `source_type`, `source_id`, `target_type`, `target_id`, `ext_param`, `status`, ...)
VALUES
    (9, 'client', '3202', 'advisor', '4001', '{}', 1, ...),
    (10, 'client', '3202', 'advisor', '4003', '{}', 1, ...),
    (11, 'client', '3202', 'model', '2003', '{}', 1, ...),
    (12, 'client', '3202', 'prompt', '7001', '{}', 1, ...);
```

### 3.2 系统提示词

```sql
-- ai_client_system_prompt 表：prompt_id=7001
-- prompt_content：完整模板见第 2.5 节
INSERT INTO `ai_client_system_prompt` (`id`, `prompt_id`, `prompt_name`, `prompt_content`, `prompt_type`, ...)
VALUES (10, '7001', '上下文压缩提示词', '<见2.5节完整模板>', 1, ...);
```

### 3.3 枚举变更

```java
// AiClientTypeEnumVO.java 末尾追加
COMPRESSION_ASSISTANT("COMPRESSION_ASSISTANT", "压缩助手")
```

---

### 3.4 新建/修改文件

| 文件 | 改动说明 |
|------|----------|
| `CompressionRequiredException.java` | 新建：压缩触发异常 |
| `CompressionContextNode.java` | 新建：压缩节点处理类（滚动截断策略） |
| `DynamicContext.java` | 修改：新增压缩相关字段 |
| `AiClientTypeEnumVO.java` | 修改：新增 COMPRESSION_ASSISTANT 枚举 |
| `AiClientModelVO.java` | 修改：RetryConfig 内新增 CompressionConfig |
| `RetryChatModel.java` | 修改：新增压缩检查逻辑 |
| `AiClientModelNode.java` | 修改：捕获压缩异常，路由到压缩节点 |

> **注意**：原始消息存储（MySQL/Redis）保持不变，不新增 rebuildRedisCache 方法。

---

## 4. 任务列表

| 序号 | 任务 | 状态 |
|------|------|------|
| 1 | `DynamicContext.java` 新增压缩相关字段 | pass |
| 2 | 新建 `CompressionRequiredException.java` 压缩触发异常 | pass |
| 3 | DML 新增 `ai_client` 表压缩助手数据 (clientId=3202) | pass |
| 4 | DML 新增 `ai_agent_flow_config` 表关联 (agentId=3 → clientId=3202) | pass |
| 5 | DML 新增 `ai_client_config` 表关联 (clientId=3202 → advisor/model) | pass |
| 6 | `AiClientTypeEnumVO.java` 新增 `COMPRESSION_ASSISTANT` 枚举值 | pass |
| 7 | 新建 `CompressionContextNode.java` 压缩节点处理类（滚动截断） | pass |
| 8 | `AiClientModelNode.java` 修改：检测压缩触发条件并抛出异常路由 | pass |
| 8.5 | `RetryChatModel.java` stream() 方法：入口检查降级到 call() | pass |
| 9 | `AiClientModelVO.java` 新增 CompressionConfig 配置类 | pass |
| 10 | 编译验证（`mvn compile`） | pass |
| 11 | 单元测试编写 | pass |

执行记录：

| 序号 | 任务 | 状态 | 执行时间 | 备注 |
|------|------|------|---------|------|
| 1 | DynamicContext.java 新增压缩相关字段 | pass | 2026-05-18 15:34 | |
| 2 | CompressionRequiredException.java | pass | 2026-05-18 15:35 | |
| 3-5 | DML 数据 | pass | 2026-05-18 15:36 | |
| 6 | AiClientTypeEnumVO | pass | 2026-05-18 15:37 | |
| 7 | CompressionContextNode.java | pass | 2026-05-18 15:40 | |
| 8-8.5 | RetryChatModel + AiClientModelNode | pass | 2026-05-18 15:45 | |
| 9 | AiClientModelVO.CompressionConfig | pass | 2026-05-18 15:46 | |
| 10 | 编译验证 | pass | 2026-05-18 15:50 | 首次编译失败，修复后通过 |
| 11 | 单元测试 | pass | 2026-05-18 15:52 | 32个测试用例全部通过 |

---

## 5. 配置示例

`ai_client_model` 表的 `ext_param` 字段：

```json
{
  "enabled": true,
  "maxAttempts": 3,
  "compressionConfig": {
    "enabled": true,
    "proactiveThresholdTokens": 160000,
    "maxCompressionAttempts": 3,
    "maxSummaryTokens": 2000
  }
}
```

---

## 6. 测试计划

### 6.1 测试用例汇总

#### 一、CompressionRequiredException 异常测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-Except-001 | 正常构造异常 | `originalPrompt`, `returnNode` | 异常正确包含原始 prompt 和返回节点 | 正常 |
| TC-Except-002 | 获取原始 Prompt | `exception.getOriginalPrompt()` | 返回正确的 Prompt 对象 | 正常 |
| TC-Except-003 | 获取返回节点 | `exception.getReturnNode()` | 返回正确的节点名称 | 正常 |
| TC-Except-004 | 异常消息验证 | `getMessage()` | 包含 "Compression required" | 正常 |
| TC-Except-005 | null Prompt 构造 | `originalPrompt = null` | 允许 null（被动压缩场景） | 边界 |

#### 二、RetryChatModel 主动压缩触发测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-RCL-001 | 主动压缩触发 - 超阈值 | token = 160001, threshold = 160000 | 抛出 `CompressionRequiredException` | 正常 |
| TC-RCL-002 | 主动压缩不触发 - 恰好阈值 | token = 160000, threshold = 160000 | 不抛出异常，正常执行 | 边界 |
| TC-RCL-003 | 主动压缩不触发 - 低于阈值 | token = 159999, threshold = 160000 | 不抛出异常，正常执行 | 边界 |
| TC-RCL-004 | 接近阈值 - 差1 | token = 159999, threshold = 160000 | 不触发压缩 | 边界 |
| TC-RCL-005 | 接近阈值 - 差100 | token = 159900, threshold = 160000 | 不触发压缩 | 边界 |
| TC-RCL-006 | 大幅超阈值 | token = 200000, threshold = 160000 | 触发压缩 | 正常 |

#### 三、RetryChatModel 被动压缩与配置测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-RCL-007 | 压缩未启用 | `compressionConfig.enabled = false` | 不检查阈值，正常执行 | 正常 |
| TC-RCL-008 | compressionConfig 为 null | `compressionConfig = null` | 跳过压缩检查 | 边界 |
| TC-RCL-009 | 被动压缩触发 - 1261 | 收到 1261 错误 + DynamicContext | 抛出 `CompressionRequiredException` | 正常 |
| TC-RCL-010 | 非 1261 错误 | 收到 500 错误 | 不触发压缩，走纯重试 | 正常 |
| TC-RCL-011 | 1261 但 DynamicContext 为 null | 1261 + dynamicContext = null | 不触发压缩，指数退避重试 | 边界 |
| TC-RCL-012 | 1261 但 compressionRequired 已为 true | 1261 + 已压缩 | 跳过压缩，避免重复触发 | Corner |
| TC-RCL-013 | DynamicContext 字段设置验证 | 触发压缩时 | 设置 originalPrompt、compressionRequired=true、returnNode | 正常 |

#### 四、CompressionContextNode 节点测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-Node-001 | 获取压缩助手配置 | DynamicContext 含 flowConfigMap | 正确获取 COMPRESSION_ASSISTANT 配置 | 正常 |
| TC-Node-002 | 配置缺失处理 | flowConfigMap 为空 | 使用默认值，不抛异常 | 边界 |
| TC-Node-003 | 获取原始 Prompt | dynamicContext.getOriginalPrompt() | 正确提取 prompt 文本 | 正常 |
| TC-Node-004 | 获取压缩提示词模板 | 从 DynamicContext 读取 | 正确获取 prompt_id=7001 的模板 | 正常 |
| TC-Node-005 | 压缩提示词缺失 | 未配置 prompt 7001 | 使用默认模板 | 边界 |
| TC-Node-006 | 调用压缩模型 | 正确配置 compressionModelId | 成功调用并获取摘要 | 正常 |
| TC-Node-007 | 压缩模型调用失败 | 压缩模型抛异常 | 异常向上传播 | 异常 |
| TC-Node-008 | 压缩成功完成 | 摘要生成成功 | 正确格式化，设置 compressedPrompt | 正常 |
| TC-Node-009 | 压缩后路由回原节点 | 压缩完成后 | 路由到 returnNode 指定的节点 | 正常 |
| TC-Node-010 | 获取会话历史 | sessionId | 正确从 Redis/MySQL 获取消息列表 | 正常 |

#### 五、滚动截断策略测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-Roll-001 | 默认保留 2 轮对话 | 压缩后 < 160K | 构建 prompt 含摘要 + 2 轮对话 | 正常 |
| TC-Roll-002 | 截断-递减到 1 轮 | 压缩后 160K~240K（20%~50% 超限） | 减少到 1 轮对话 | 正常 |
| TC-Roll-003 | 截断-递减到 0 轮 | 压缩后 > 240K（> 50% 超限） | 仅保留摘要，无 [最近对话] | 正常 |
| TC-Roll-004 | 临界点 - 20% | 压缩后恰好 160K | 保留 2 轮对话 | 边界 |
| TC-Roll-005 | 临界点 - 50% | 压缩后恰好 240K | 保留 1 轮对话 | 边界 |
| TC-Roll-006 | 临界点 - 略超 50% | 压缩后 240001 | 仅保留摘要 | 边界 |
| TC-Roll-007 | 消息轮数 < 2 | 仅 1 轮消息 | 全量压缩，仅保留摘要（不报错） | 边界 |
| TC-Roll-008 | 消息轮数 = 2 | 恰好 2 轮消息 | 保留全部 2 轮对话 | 边界 |
| TC-Roll-009 | 消息轮数 > 2 | 多轮消息 | 按截断策略递减 | 正常 |
| TC-Roll-010 | 空消息列表 | messages = [] | 仅保留摘要 | 边界 |
| TC-Roll-011 | null 消息列表 | messages = null | 仅保留摘要 | 边界 |
| TC-Roll-012 | 截断后 token 验证 | 截断完成 | 截断后 token <= 160K | 正常 |

#### 六、getRecentRounds 防御性测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-Recent-001 | 消息数量 <= 目标数量 | total=3, target=4 (2轮) | 返回全部 3 条消息 | 正常 |
| TC-Recent-002 | 消息数量 > 目标数量 | total=10, target=4 (2轮) | 返回最后 4 条消息 | 正常 |
| TC-Recent-003 | 消息数量恰好等于目标 | total=4, target=4 | 返回全部消息 | 边界 |
| TC-Recent-004 | 空消息列表 | messages = [] | 返回空 List，不抛异常 | 边界 |
| TC-Recent-005 | null 消息列表 | messages = null | 返回空 List，不抛异常 | 边界 |
| TC-Recent-006 | 单条消息 | total=1, target=4 | 返回全部 1 条消息 | 边界 |
| TC-Recent-007 | 3条消息 2轮目标 | total=3, target=4 | 返回全部 3 条消息 | Corner |
| TC-Recent-008 | subList 索引计算 | 正确计算 startIndex | 返回正确的消息范围 | 正常 |

#### 七、摘要格式化测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-Fmt-001 | 正常格式解析 | 包含 `<分析>` 和 `<摘要>` | 去除 `<分析>`，提取 `<摘要>` 内容 | 正常 |
| TC-Fmt-002 | 仅含 `<分析>` | 无 `<摘要>` 标签 | 使用 `<分析>` 内容作为摘要 | Corner |
| TC-Fmt-003 | 无任何标签 | 纯文本摘要 | 直接返回原文本 | Corner |
| TC-Fmt-004 | 嵌套标签 | `<分析>` 中含 `<摘要>` | 匹配最外层标签 | Corner |
| TC-Fmt-005 | 大小写不敏感 | `<摘要>` vs `<摘要>` | 正则支持大小写匹配 | Corner |
| TC-Fmt-006 | 多余空行清理 | 含 3+ 个连续空行 | 合并为 2 个空行 | Corner |
| TC-Fmt-007 | 首尾空白清理 | 含前后空格/换行 | trim 处理 | Corner |
| TC-Fmt-008 | 空字符串输入 | `""` | 返回空字符串 | 边界 |
| TC-Fmt-009 | null 输入 | `null` | 抛出 NPE 或返回空 | 边界 |

#### 八、压缩 Prompt 构建测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-Bld-001 | 正常构建含2轮 | summary + 2轮消息 | 包含边界标记、摘要、[最近对话] | 正常 |
| TC-Bld-002 | 构建含1轮 | keepRounds=1 | 包含 `[最近对话]` + 1轮消息 | 正常 |
| TC-Bld-003 | 构建无对话 | keepRounds=0 或 messages=null | 仅包含边界 + 摘要，无 `[最近对话]` | 正常 |
| TC-Bld-004 | 原始 token 数记录 | originalTokens = 200000 | 边界中包含 "原始约 200000 tokens" | 正常 |
| TC-Bld-005 | 消息角色格式 | user/assistant 消息 | 格式为 "user: {content}" | 正常 |
| TC-Bld-006 | token 数为 0 | originalTokens = 0 | 边界中显示 "原始约 0 tokens" | 边界 |

#### 九、DynamicContext 压缩字段测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-DC-001 | compressionRequired 默认值 | 新建 DynamicContext | 默认应为 false | 正常 |
| TC-DC-002 | 设置 compressionRequired | `setCompressionRequired(true)` | 正确设置标志 | 正常 |
| TC-DC-003 | 设置 returnNode | `setReturnNode("aiClientModelNode")` | 正确保存返回节点 | 正常 |
| TC-DC-004 | 设置 originalPrompt | `setOriginalPrompt(prompt)` | 正确保存原始 Prompt | 正常 |
| TC-DC-005 | 设置 compressedPrompt | `setCompressedPrompt(compressed)` | 正确保存压缩后 Prompt | 正常 |
| TC-DC-006 | 压缩完成后重置 | `setCompressionRequired(false)` | 正确重置标志 | 正常 |
| TC-DC-007 | 压缩后 Prompt 覆盖 | 压缩完成后 | 使用 compressedPrompt 而非 originalPrompt | 正常 |
| TC-DC-008 | aiAgentClientFlowConfigVOMap | flowConfigMap 设置 | 正确存储配置映射 | 正常 |

#### 十、熔断器测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-CB-001 | 连续压缩失败达到阈值 | 失败次数 = maxCompressionAttempts | 触发熔断，不再尝试压缩 | 正常 |
| TC-CB-002 | 熔断后 1261 错误 | 熔断状态 + 1261 | 跳过压缩，指数退避重试 | 正常 |
| TC-CB-003 | 熔断后主动压缩阈值超 | token > threshold + 熔断 | 跳过压缩 | 正常 |
| TC-CB-004 | 熔断阈值边界 | 失败次数 = max - 1 | 还未熔断，可继续压缩 | 边界 |
| TC-CB-005 | 熔断后普通错误 | 非 1261 错误 | 纯指数退避重试 | 正常 |
| TC-CB-006 | 熔断器跟随实例生命周期 | 新建实例 | 新实例熔断状态为 false | 正常 |
| TC-CB-007 | maxCompressionAttempts = 0 | 配置为 0 | 立即熔断，不尝试压缩 | 边界 |
| TC-CB-008 | maxCompressionAttempts = 1 | 配置为 1 | 首次失败即熔断 | 边界 |
| TC-CB-009 | 压缩成功清除失败计数 | 成功后收到错误 | 重置熔断计数器 | 正常 |

#### 十一、流式调用降级测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-Str-001 | stream 超阈值降级 | token > 160000 | 降级到 call() 方法 | 正常 |
| TC-Str-002 | stream 未超阈值 | token <= 160000 | 正常流式调用 | 正常 |
| TC-Str-003 | stream 压缩触发 | token > 160000 | 抛出 CompressionRequiredException | 正常 |
| TC-Str-004 | stream 恰好阈值 | token = 160000 | 不触发降级，正常流式 | 边界 |
| TC-Str-005 | stream 返回类型 | 降级后 | 返回 Flux<ChatResponse> | 正常 |

#### 十二、配置测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-Cfg-001 | 默认阈值 | 不配置 proactiveThresholdTokens | 使用默认值 **160000** | 正常 |
| TC-Cfg-002 | 自定义阈值 | proactiveThresholdTokens = 100000 | 使用 100000 | 正常 |
| TC-Cfg-003 | 阈值为 0 | proactiveThresholdTokens = 0 | 使用默认值 160000 | 边界 |
| TC-Cfg-004 | 阈值为负数 | proactiveThresholdTokens = -1 | 使用默认值 160000 | 边界 |
| TC-Cfg-005 | 阈值为 200000 | proactiveThresholdTokens = 200000 | 使用 200000（覆盖默认值） | 边界 |
| TC-Cfg-006 | 默认 maxSummaryTokens | 不配置 | 使用默认值 2000 | 正常 |
| TC-Cfg-007 | 自定义 maxSummaryTokens | maxSummaryTokens = 5000 | 使用 5000 | 正常 |
| TC-Cfg-008 | 压缩未启用 | enabled = false | 跳过所有压缩检查 | 正常 |

#### 十三、滚动压缩-消息历史测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-Hist-001 | 首次压缩 | 全新消息历史 | 压缩全部消息生成摘要 | 正常 |
| TC-Hist-002 | 二次压缩（摘要再次参与） | 上轮摘要 + 新消息 | 摘要作为输入参与压缩 | Corner |
| TC-Hist-003 | 多次滚动压缩 | 持续多轮对话 | 摘要逐轮完善 | Corner |
| TC-Hist-004 | 原始存储保持不变 | 压缩过程中 | MySQL/Redis 数据不变 | 正常 |
| TC-Hist-005 | 压缩后新消息追加 | 压缩后新对话 | 新消息正常追加到原始存储 | 正常 |

#### 十四、压缩请求构建测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-Req-001 | 构建压缩请求 | 模板 + 消息列表 | 包含模板 + [待压缩对话内容] + 消息 | 正常 |
| TC-Req-002 | 请求包含 token 限制 | maxTokens = 2000 | 末尾包含 "请生成不超过 2000 tokens 的摘要" | 正常 |
| TC-Req-003 | 消息格式化 | user/assistant 消息 | 格式为 "role: content" | 正常 |
| TC-Req-004 | 空消息列表 | messages = [] | 只包含模板 + token 限制 | 边界 |
| TC-Req-005 | null 消息 | messages 含 null | 正确处理 null 元素 | 边界 |

#### 十五、异常场景测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-Err-001 | 压缩模型超时 | 抛出 TimeoutException | 异常向上传播 | 异常 |
| TC-Err-002 | 压缩模型返回空 | 返回 "" | 处理空响应 | 边界 |
| TC-Err-003 | 压缩模型抛出业务异常 | 自定义异常 | 异常传播 | 异常 |
| TC-Err-004 | 会话不存在 | sessionId 不存在 | 返回空消息列表 | 边界 |
| TC-Err-005 | Redis 连接失败 | Redis 异常 | 回退到 MySQL | 容错 |
| TC-Err-006 | MySQL 连接失败 | MySQL 异常 | 抛出异常或返回空 | 异常 |
| TC-Err-007 | TokenCountUtils 异常 | 输入 null | 正确处理 | 边界 |
| TC-Err-008 | ChatMessageEntity 为 null | messages 含 null | 过滤 null 元素 | 边界 |

#### 十六、日志验证测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-Log-001 | 压缩触发日志 | 抛出 CompressionRequiredException | 记录压缩触发日志 | 正常 |
| TC-Log-002 | 压缩节点开始日志 | doApply() 开始 | 记录开始处理日志 | 正常 |
| TC-Log-003 | 滚动截断日志 | 压缩后仍超限 | 记录截断操作日志 | 正常 |
| TC-Log-004 | 路由日志 | 路由回原节点 | 记录路由目标节点 | 正常 |
| TC-Log-005 | 截断递减日志 | 递减保留轮数 | 记录递减过程 | 正常 |

#### 十七、集成流程测试

| 测试ID | 测试场景 | 输入 | 预期结果 | 测试类型 |
|--------|----------|------|----------|----------|
| TC-Int-001 | 完整主动压缩流程 | token > 160K | 触发 → 路由 → 压缩 → 摘要 → 截断 → 返回 | 正常 |
| TC-Int-002 | 完整被动压缩流程 | 1261 错误 | 捕获 → 触发 → 路由 → 压缩 → 返回 | 正常 |
| TC-Int-003 | 主动压缩-2轮保留 | 压缩后 < 160K | 压缩 + 保留2轮 + 返回 | 正常 |
| TC-Int-004 | 主动压缩-递减1轮 | 压缩后 160K~240K | 压缩 + 递减到1轮 + 返回 | Corner |
| TC-Int-005 | 主动压缩-递减0轮 | 压缩后 > 240K | 压缩 + 仅保留摘要 + 返回 | Corner |
| TC-Int-006 | 消息不足场景 | < 2 轮消息 | 全量压缩 + 仅保留摘要 | Corner |
| TC-Int-007 | AiClientModelNode 捕获异常 | CompressionRequiredException | 正确路由到压缩节点 | 正常 |
| TC-Int-008 | 压缩节点路由回来 | 压缩完成 | 回到 aiClientModelNode 继续执行 | 正常 |
| TC-Int-009 | 滚动压缩场景 | 多次压缩请求 | 摘要增量式完善 | Corner |
| TC-Int-010 | 压缩后指数退避 | 压缩后收到其他错误 | 指数退避重试（不压缩） | Corner |

### 6.2 测试用例汇总表

| 类别 | 测试用例数 |
|------|------------|
| 压缩异常测试 | 5 |
| RetryChatModel 主动触发 | 6 |
| RetryChatModel 被动触发 | 7 |
| 压缩节点测试 | 10 |
| 滚动截断策略 | 12 |
| getRecentRounds 防御性 | 8 |
| 摘要格式化 | 9 |
| Prompt 构建 | 6 |
| DynamicContext | 8 |
| 熔断器测试 | 9 |
| 流式降级 | 5 |
| 配置测试 | 8 |
| 滚动压缩-消息历史 | 5 |
| 压缩请求构建 | 5 |
| 异常场景 | 8 |
| 日志验证 | 5 |
| 集成流程 | 10 |

**总计：127 个测试用例**

### 6.3 阈值汇总

| 触发类型 | 阈值 | 说明 |
|----------|------|------|
| 主动压缩触发 | **160,000** | 200,000 × 80%，预防性触发 |
| 被动压缩触发 | 1261 错误 | 服务端返回超长错误码 |
| 滚动截断基准 | 200,000 | 截断策略的计算基准 |
| 截断阈值 2→1 | 160K~240K | 压缩后超出 0%~50% |
| 截断阈值 1→0 | > 240K | 压缩后超出 50% 以上 |

### 6.4 重点 Corner 场景提醒

1. **滚动截断阈值计算**：< 160K 保留 2 轮，160K~240K 保留 1 轮，> 240K 保留 0 轮
2. **getRecentRounds 防御性处理**：消息不足时返回全部消息，不抛异常
3. **滚动压缩增量特性**：摘要会作为输入参与下一轮压缩，摘要内容会累积
4. **消息配对问题**：1 轮 = user + assistant（2 条消息），需要确保截断点不会破坏配对
5. **流式调用降级**：stream 超阈值时降级到 call，影响性能但保证正确性
6. **熔断后避免重复触发**：compressionRequired 已为 true 时不再重复设置
7. **原始存储不变**：压缩过程不修改 MySQL/Redis，DynamicContext.compressedPrompt 仅运行时生效

---

## 7. 风险与回滚

| 风险 | 应对 |
|------|------|
| 压缩模型超时 | 设置合理的超时配置 |
| 压缩后仍超长 | 熔断器限制连续压缩次数 |
| 压缩丢失关键信息 | 保留完整代码块和错误堆栈 |

**回滚**：删除新增的 Java 文件（`CompressionContextNode.java`、`CompressionRequiredException.java`），移除枚举值和字段改动即可。
