# Story: 集成 Spring AI Agent Utils SkillsTool

## 1. 背景

当前 `TradingToolCallbacks` 中的 7 个 tool 描述直接写在 Java 代码中，随着 tool 数量增加会导致：
- Context 膨胀，LLM 理解成本上升
- 维护困难，描述与逻辑混杂
- 复用性差，难以跨领域共享

通过集成官方 `spring-ai-agent-utils` 的 SkillsTool，实现 tool 的 skill 化管理。

## 2. 目标

1. 升级 Spring AI 从 1.1.0 到 2.x 版本
2. 集成 spring-ai-agent-utils 依赖
3. 将 7 个 Trading tool 迁移为 Skill 形式
4. 验证 SkillsTool 与现有 ToolCallback 的兼容性

---

## 任务清单

| # | 任务 | 状态 |
|---|------|------|
| **Phase 1: Spring AI 版本升级** | | |
| 1.1 | 升级 spring-ai-bom 版本到 2.0.0 | pending |
| 1.2 | 更新 spring-ai-alibaba-starter-memory-mem0 版本 | pending |
| 1.3 | 检查并更新其他 spring-ai 相关依赖兼容性 | pending |
| 1.4 | 编译验证，修复 breaking changes | pending |
| **Phase 2: 添加 spring-ai-agent-utils 依赖** | | |
| 2.1 | 添加 spring-ai-agent-utils-bom 到 dependencyManagement | pending |
| 2.2 | 添加 spring-ai-agent-utils 依赖到 ai-agent-study-trading-infra | pending |
| 2.3 | 编译验证依赖引入成功 | pending |
| **Phase 3: 创建 Skill 文件结构** | | |
| 3.1 | 创建 .claude/skills/trading/ 目录结构 | pending |
| 3.2 | 创建 get-stock-info/SKILL.md | pending |
| 3.3 | 创建 get-historical-bars/SKILL.md | pending |
| 3.4 | 创建 get-technical-indicators/SKILL.md | pending |
| 3.5 | 创建 get-fundamental-data/SKILL.md | pending |
| 3.6 | 创建 get-sentiment/SKILL.md | pending |
| 3.7 | 创建 get-stock-news/SKILL.md | pending |
| 3.8 | 创建 search-stock-by-name/SKILL.md | pending |
| **Phase 4: Java 代码改造** | | |
| 4.1 | 创建 TradingSkillsConfig 配置类 | pending |
| 4.2 | 在 ChatClient 中注册 SkillsTool | pending |
| 4.3 | 保留 ToolCallback 作为实际执行层 | pending |
| **Phase 5: 测试与验证** | | |
| 5.1 | 编译通过，无错误 | pending |
| 5.2 | SkillsTool 成功加载 7 个 skill | pending |
| 5.3 | 功能测试验证 | pending |

---

## 3. Spring AI 2.x 兼容性分析

### 3.1 必须修改的文件（3个）

| # | 文件 | 问题 | 解决方案 |
|---|------|------|----------|
| 1 | `AiClientAdvisorTypeEnumVO.java` | 使用废弃的 `PromptChatMemoryAdvisor` | 迁移到 `MessageChatMemoryAdvisor` |
| 2 | `AiAgentTest.java` | 使用废弃的 `PromptChatMemoryAdvisor` (3处) | 迁移到 `MessageChatMemoryAdvisor` |
| 3 | `pom.xml` | `spring-ai-bom` 版本太低 | 升级到 2.0.0 |

### 3.2 迁移代码示例

```java
// ❌ 旧代码 (1.1.x)
PromptChatMemoryAdvisor.builder(
    MessageWindowChatMemory.builder()
        .maxMessages(100)
        .build()
).build();

// ✅ 新代码 (2.x)
MessageChatMemoryAdvisor.builder(
    MessageWindowChatMemory.builder()
        .maxMessages(100)
        .build()
).build();
```

### 3.3 无需修改的代码

以下模块的代码在 Spring AI 2.x 中无需修改：
- 28 个使用 `ChatClient` 的 Node 类
- `RetryChatModel.java`
- `TradingToolCallbacks.java`
- `RagAnswerAdvisor.java`
- `ObservabilityAdvisor.java`
- 所有 VectorStore/Embedding 相关代码

---

## 4. 依赖变更

```xml
<!-- 1. Spring AI BOM 升级 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-bom</artifactId>
    <version>2.0.0</version>  <!-- 1.1.0 -> 2.0.0 -->
</dependency>

<!-- 2. 新增 spring-ai-agent-utils -->
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agent-utils-bom</artifactId>
    <version>0.5.0</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>

<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agent-utils</artifactId>
</dependency>
```

---

## 5. Skill 文件模板

```markdown
---
name: {tool-name}
description: |
  {tool-description}
  触发场景：{when-to-use}
---

# {Tool Title}

## 功能说明
{详细说明}

## 输入参数
{参数说明}

## 返回格式
{返回数据格式说明}

## 使用示例
{few-shot examples}
```

---

## 6. 文件变更清单

```
ai-agent-study-trading/ai-agent-study-trading-infra/pom.xml      [修改]

新建文件:
  ai-agent-study-trading-infra/src/main/resources/
    .claude/skills/trading/
      get-stock-info/SKILL.md
      get-historical-bars/SKILL.md
      get-technical-indicators/SKILL.md
      get-fundamental-data/SKILL.md
      get-sentiment/SKILL.md
      get-stock-news/SKILL.md
      search-stock-by-name/SKILL.md

  ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/config/
    TradingSkillsConfig.java
```

---

## 7. 风险点

1. **Breaking Changes**: Spring AI 2.x 可能有 API 不兼容
2. **ClassLoader 问题**: 资源文件从 classpath 加载 skill 的路径配置
3. **Tool vs Skill 职责边界**: 需要明确区分 Skill(描述) 和 ToolCallback(执行)
