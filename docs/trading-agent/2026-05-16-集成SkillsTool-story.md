# Story: TradingToolCallbacks 改造为 Skills 渐进式披露

## 1. 背景

当前 `TradingToolCallbacks` 中 7 个 tool 的描述直接写在 Java 代码中：
- Context 膨胀，LLM 理解成本上升
- 维护困难，描述与逻辑混杂
- 无法实现渐进式披露（Progressive Disclosure）

通过利用 **Spring AI Alibaba 1.1.2.2 内置的 Skills 支持**，将 Tool 改造为 Skill 化管理，实现：
1. 轻量级 Skill 注册（只暴露 name + description）
2. 按需加载完整 Skill 内容
3. 保留 ToolCallback 作为实际执行层

## 2. 目标

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         改造目标                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   启动时: SkillsAgentHook → 扫描 SKILL.md → LLM 只看到元数据              │
│                                                                         │
│   运行时: LLM 决策需要 → read_skill tool → 按需加载完整内容                │
│                                                                         │
│   执行时: LLM 调用 tool → ToolCallback 执行 → 返回结果                    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 技术方案

### 3.1 核心组件

| 组件 | 来源 | 用途 |
|------|------|------|
| `SkillsAgentHook` | Spring AI Alibaba 内置 | Skill 注册与 Hook 集成 |
| `FileSystemSkillRegistry` | Spring AI Alibaba 内置 | 从文件系统加载 Skill |
| `ReadSkillTool` | Spring AI Alibaba 内置 | 按需读取 Skill 内容 |
| `SearchSkillsTool` | Spring AI Alibaba 内置 | 搜索可用 Skills |
| `DisableSkillTool` | Spring AI Alibaba 内置 | 禁用特定 Skill |

### 3.2 无需新增依赖

Spring AI Alibaba 1.1.2.2 已包含所有需要的组件。

---

## 4. 任务清单

| # | 任务 | 状态 | 备注 |
|---|------|------|------|
| **Phase 1: Skill 文件创建** | | | |
| 1.1 | 创建 `.claude/skills/trading/` 目录结构 | pending | |
| 1.2 | 创建 `get-stock-info/SKILL.md` | pending | 从 Java 描述迁移 |
| 1.3 | 创建 `get-historical-bars/SKILL.md` | pending | |
| 1.4 | 创建 `get-technical-indicators/SKILL.md` | pending | |
| 1.5 | 创建 `get-fundamental-data/SKILL.md` | pending | |
| 1.6 | 创建 `get-sentiment/SKILL.md` | pending | |
| 1.7 | 创建 `get-stock-news/SKILL.md` | pending | |
| 1.8 | 创建 `search-stock-by-name/SKILL.md` | pending | |
| **Phase 2: Java 配置改造** | | | |
| 2.1 | 创建 `TradingSkillsConfig.java` 配置类 | pending | 注册 SkillRegistry + Hook |
| 2.2 | 在现有 Agent/ChatClient 中集成 SkillsAgentHook | pending | |
| 2.3 | 保留 ToolCallback 作为执行层 | pending | 不改变现有执行逻辑 |
| **Phase 3: 测试用例开发** | | | |
| 3.1 | 创建 `TradingSkillsConfigTest.java` | pending | 配置类单元测试 |
| 3.2 | 创建 `SkillFileFormatTest.java` | pending | SKILL.md 格式验证 |
| 3.3 | 创建 `SkillRegistryIntegrationTest.java` | pending | 注册表集成测试 |
| 3.4 | 创建 `ProgressiveDisclosureTest.java` | pending | 渐进式披露测试 |
| **Phase 4: 验证测试** | | | |
| 4.1 | 编译验证 | pending | |
| 4.2 | Skill 注册验证（LLM 能看到 skill 元数据） | pending | |
| 4.3 | 按需加载验证（LLM 能调用 read_skill） | pending | |
| 4.4 | 功能测试验证 | pending | |

---

## 5. Skill 文件结构

```
ai-agent-study-trading-infra/src/main/resources/
└── .claude/
    └── skills/
        └── trading/
            ├── SKILL.md                    # 聚合入口（可选）
            ├── get-stock-info/
            │   └── SKILL.md
            ├── get-historical-bars/
            │   └── SKILL.md
            ├── get-technical-indicators/
            │   └── SKILL.md
            ├── get-fundamental-data/
            │   └── SKILL.md
            ├── get-sentiment/
            │   └── SKILL.md
            ├── get-stock-news/
            │   └── SKILL.md
            └── search-stock-by-name/
                └── SKILL.md
```

---

## 6. SKILL.md 模板

```markdown
---
name: {tool-name}
description: |
  {简短描述，一句话说明用途}
  适用场景：{何时使用}
  注意：{重要提示}
---

# {工具标题}

## 工具信息
- **Tool Name**: `{tool-name}`
- **执行层**: ToolCallback (`TradingToolCallbacks`)

## 功能说明
{详细功能描述}

## 输入参数
| 参数 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| param1 | string | 是 | 参数说明 | "000001" |

## 返回格式
{返回数据格式说明}

## 使用场景
1. 场景1
2. 场景2

## 注意事项
{重要提示}
```

---

## 7. Java 代码改造

### 7.1 TradingSkillsConfig.java

```java
package denny.ai.agent.trading.infra.config;

import com.alibaba.cloud.ai.graph.skills.hook.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.FileSystemSkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Trading 领域 Skills 配置。
 * <p>
 * 利用 Spring AI Alibaba 内置的 Skills 支持，实现 Tool 的渐进式披露。
 */
@Slf4j
@Configuration
public class TradingSkillsConfig {

    @Bean
    public SkillRegistry tradingSkillRegistry() {
        return FileSystemSkillRegistry.builder()
                .addSkillsDirectory("classpath:/.claude/skills/trading")
                .scanSubdirectories(true)
                .build();
    }

    @Bean
    public SkillsAgentHook tradingSkillsAgentHook(SkillRegistry tradingSkillRegistry) {
        return SkillsAgentHook.builder()
                .skillRegistry(tradingSkillRegistry)
                .autoReload(true)
                .build();
    }
}
```

### 7.2 在 ReactAgent 中集成（伪代码）

```java
// 方式1: 通过 Hook 注册
ReactAgent agent = ReactAgent.builder()
        .name("trading_agent")
        .model(chatModel)
        .tools(tradingToolCallbacks)  // 保留执行层
        .hooks(tradingSkillsAgentHook) // Skill Hook
        .build();

// 方式2: 直接注册为 Tool（通过 SpringAiSkillAdvisor）
ChatClient chatClient = ChatClient.builder(baseChatModel)
        .defaultTools(skillsTool, tradingToolCallbacks)
        .build();
```

---

## 8. 文件变更清单

### 修改文件
```
ai-agent-study-trading/ai-agent-study-trading-infra/
├── pom.xml                                    [可能需要确认依赖]
└── src/main/java/denny/ai/agent/trading/infra/config/
    └── TradingSkillsConfig.java               [新建]
```

### 新建文件
```
ai-agent-study-trading/ai-agent-study-trading-infra/
├── src/main/java/denny/ai/agent/trading/infra/config/
│   └── TradingSkillsConfig.java               [新建]
└── src/main/resources/
    └── .claude/
        └── skills/
            └── trading/
                ├── get-stock-info/SKILL.md
                ├── get-historical-bars/SKILL.md
                ├── get-technical-indicators/SKILL.md
                ├── get-fundamental-data/SKILL.md
                ├── get-sentiment/SKILL.md
                ├── get-stock-news/SKILL.md
                └── search-stock-by-name/SKILL.md

测试文件:
ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/
└── denny/ai/agent/trading/infra/config/
    ├── TradingSkillsConfigTest.java           [新建]
    ├── SkillFileFormatTest.java              [新建]
    ├── SkillRegistryIntegrationTest.java     [新建]
    └── ProgressiveDisclosureTest.java         [新建]
```

---

## 9. 对比：改造前 vs 改造后

| 维度 | 改造前 | 改造后 |
|------|--------|--------|
| **Tool 描述** | 硬编码在 Java | SKILL.md 文件 |
| **LLM 感知** | 7 个完整 description | 轻量元数据注册 |
| **按需加载** | 不支持 | read_skill 按需加载 |
| **维护性** | 代码混杂 | 描述与逻辑分离 |
| **复用性** | 低 | 高（可跨项目共享） |
| **执行层** | ToolCallback | ToolCallback（保持不变） |

---

## 10. 风险点与应对

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| Skill 路径配置 | classpath 加载可能失败 | 使用 `classpath:/` 前缀 |
| Hook 加载时机 | Skill 可能未及时注册 | 设置 `autoReload=true` |
| 与现有 Tool 冲突 | LLM 可能困惑 | 明确 Skill 描述边界 |
| 暂无 | - | - |

---

## 11. 依赖确认

无需新增依赖！Spring AI Alibaba 1.1.2.2 已包含：
- `spring-ai-alibaba-graph` → SkillsAgentHook
- `spring-ai-alibaba-graph-core` → FileSystemSkillRegistry

确认 `spring-ai-alibaba-agent-framework` 依赖已引入。

---

## 12. 测试用例设计

### 12.1 测试分层

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          测试分层                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   Layer 1: 单元测试 (Unit Tests)                                        │
│   ════════════════════════════════                                      │
│   • TradingSkillsConfigTest          - 配置类测试                        │
│   • FileSystemSkillRegistryTest      - Skill 注册表测试                  │
│   • SKILL.md 文件格式验证           - 文件完整性测试                      │
│                                                                         │
│   Layer 2: 集成测试 (Integration Tests)                                 │
│   ═══════════════════════════════════════════════                        │
│   • SkillsAgentHook 加载测试       - Hook 正确注册 Skills                │
│   • ReactAgent + Skills 集成测试   - LLM 能看到 skill 元数据            │
│                                                                         │
│   Layer 3: 端到端测试 (E2E Tests)                                       │
│   ════════════════════════════════                                      │
│   • 完整对话测试               - LLM 调用 read_skill → 获取内容 → 执行   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 12.2 单元测试设计

#### Test 1: TradingSkillsConfigTest

```java
package denny.ai.agent.trading.infra.config;

import com.alibaba.cloud.ai.graph.skills.hook.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TradingSkillsConfig 单元测试。
 * 验证配置类是否正确注册 SkillRegistry 和 SkillsAgentHook。
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring-ai-alibaba-skills.directory=classpath:/.claude/skills/trading"
})
class TradingSkillsConfigTest {

    @Autowired
    private SkillRegistry skillRegistry;

    @Autowired
    private SkillsAgentHook skillsAgentHook;

    @Test
    void testSkillRegistryBeanCreated() {
        assertNotNull(skillRegistry, "SkillRegistry should be created");
    }

    @Test
    void testSkillsAgentHookBeanCreated() {
        assertNotNull(skillsAgentHook, "SkillsAgentHook should be created");
    }

    @Test
    void testSkillRegistryLoads7Skills() {
        // 验证 7 个 skill 都被加载
        assertTrue(skillRegistry.list().size() >= 7,
            "Should load at least 7 trading skills");
    }

    @Test
    void testAllExpectedSkillsExist() {
        var skills = skillRegistry.list().stream()
            .map(SkillMetadata::getName)
            .toList();

        assertTrue(skills.contains("get-stock-info"));
        assertTrue(skills.contains("get-historical-bars"));
        assertTrue(skills.contains("get-technical-indicators"));
        assertTrue(skills.contains("get-fundamental-data"));
        assertTrue(skills.contains("get-sentiment"));
        assertTrue(skills.contains("get-stock-news"));
        assertTrue(skills.contains("search-stock-by-name"));
    }
}
```

#### Test 2: SKILL.md 文件格式验证

```java
package denny.ai.agent.trading.infra.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SKILL.md 文件格式验证测试。
 * 验证所有 SKILL.md 文件符合规范。
 */
class SkillFileFormatTest {

    @TempDir
    Path tempDir;

    @Test
    void testSkillFileHasRequiredFrontmatter() throws IOException {
        String skillContent = """
            ---
            name: get-stock-info
            description: |
              获取A股股票的实时行情信息
              适用场景：需要查询股票当前价格时调用
            ---
            
            # Stock Info Tool
            
            ## 功能说明
            获取股票实时行情
            """;

        Path skillFile = tempDir.resolve("get-stock-info/SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, skillContent);

        // 验证格式
        String content = Files.readString(skillFile);
        assertTrue(content.startsWith("---"), "Should have YAML frontmatter");
        assertTrue(content.contains("name:"), "Should have name field");
        assertTrue(content.contains("description:"), "Should have description field");
        assertTrue(content.contains("---"), "Should have closing frontmatter");
    }

    @Test
    void testSkillDescriptionNotEmpty() throws IOException {
        String skillContent = """
            ---
            name: test-skill
            description: |
              这是一个测试描述
            ---
            
            # Test Skill
            """;

        Path skillFile = tempDir.resolve("test-skill/SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, skillContent);

        String content = Files.readString(skillFile);
        assertTrue(content.contains("这是一个测试描述"));
    }
}
```

#### Test 3: SkillRegistry 按名称查询

```java
@Test
void testFindSkillByName() {
    var skill = skillRegistry.getByName("get-stock-info");
    assertTrue(skill.isPresent(), "Should find get-stock-info skill");

    var metadata = skill.get();
    assertEquals("get-stock-info", metadata.getName());
    assertNotNull(metadata.getDescription());
    assertNotNull(metadata.getPath());
}

@Test
void testFindSkillByPath() {
    var skill = skillRegistry.getByPath("get-stock-info/SKILL.md");
    assertTrue(skill.isPresent(), "Should find skill by path");
}
```

### 12.3 集成测试设计

#### Test 4: SkillsAgentHook 集成测试

```java
package denny.ai.agent.trading.infra.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.skills.hook.SkillsAgentHook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillsAgentHook 集成测试。
 * 验证 Hook 正确提供 read_skill 工具。
 */
@SpringBootTest
class SkillsAgentHookIntegrationTest {

    @Autowired
    private SkillsAgentHook skillsAgentHook;

    @Autowired
    private ReactAgent tradingAgent;

    @Test
    void testSkillsAgentHookProvidesReadSkillTool() {
        // 获取 Hook 提供的工具
        List<ToolCallback> tools = skillsAgentHook.getTools();
        assertNotNull(tools, "Hook should provide tools");
        assertFalse(tools.isEmpty(), "Hook should provide at least one tool");
    }

    @Test
    void testReadSkillToolExists() {
        List<ToolCallback> tools = skillsAgentHook.getTools();
        boolean hasReadSkillTool = tools.stream()
            .anyMatch(t -> t.getToolDefinition().name().equals("read_skill"));

        assertTrue(hasReadSkillTool, "Should have read_skill tool");
    }

    @Test
    void testReactAgentHasSkillsHook() {
        // 验证 ReactAgent 正确集成了 Skills Hook
        // 这个测试依赖于 ReactAgent 的实现
        assertNotNull(tradingAgent);
    }
}
```

#### Test 5: 渐进式披露验证

```java
@Test
void testProgressiveDisclosure_MetadataOnly() {
    // 验证启动时只加载元数据
    var skills = skillRegistry.list();

    for (var skill : skills) {
        // 元数据只包含 name 和 description
        assertNotNull(skill.getName());
        assertNotNull(skill.getDescription());
        // 不应该包含完整内容
    }

    // 验证注册表大小（轻量）
    long totalMetadataSize = skills.stream()
        .mapToLong(s -> s.getName().length() + s.getDescription().length())
        .sum();

    assertTrue(totalMetadataSize < 5000, "Metadata should be lightweight");
}

@Test
void testOnDemandLoading_FullContent() {
    // 验证按需加载完整内容
    String fullContent = skillRegistry.readSkillContent(
        "get-stock-info",
        "get-stock-info/SKILL.md"
    );

    assertNotNull(fullContent);
    assertTrue(fullContent.contains("工具信息"));
    assertTrue(fullContent.contains("输入参数"));
    assertTrue(fullContent.contains("返回格式"));
}
```

### 12.4 端到端测试设计

#### Test 6: 完整对话流程测试

```java
@Test
void testEndToEnd_AskStockPrice() {
    // 模拟用户问 "帮我查一下贵州茅台的股价"
    String userMessage = "帮我查一下贵州茅台的股价";

    // 1. LLM 应该先调用 search_stock_by_name 找到股票代码
    // 2. 然后调用 get_stock_info 获取价格

    // 验证 LLM 调用了正确的工具序列
    verify(mockToolCallbacks, times(1)).searchStockByNameCallback();
    verify(mockToolCallbacks, times(1)).getStockInfoCallback();
}

@Test
void testEndToEnd_ProgressiveDisclosureFlow() {
    // 1. 启动时验证 LLM 能看到 skill 元数据
    var skills = skillRegistry.list();
    assertEquals(7, skills.size());

    // 2. LLM 调用 read_skill 获取完整内容
    var fullContent = skillRegistry.readSkillContent(
        "get-technical-indicators",
        "get-technical-indicators/SKILL.md"
    );
    assertTrue(fullContent.length() > 500);

    // 3. LLM 根据完整内容决定参数
    // 4. 调用实际的 ToolCallback 执行
}
```

### 12.5 Mock 测试设计

```java
@Test
void testSkillRegistryWithMockedFileSystem() {
    // 使用 Mock 模拟文件系统
    FileSystemMock mockFs = FileSystemMock.create()
        .addFile("get-stock-info/SKILL.md", getStockInfoContent())
        .addFile("get-historical-bars/SKILL.md", getHistoricalBarsContent());

    FileSystemSkillRegistry registry = FileSystemSkillRegistry.builder()
        .fileSystem(mockFs)
        .build();

    assertEquals(2, registry.list().size());
}

private String getStockInfoContent() {
    return """
        ---
        name: get-stock-info
        description: 获取股票实时行情
        ---
        
        # Stock Info Tool
        """;
}
```

### 12.6 测试数据

#### 7 个 SKILL.md 文件对应的测试数据

| Skill 名称 | 测试参数 | 预期结果 |
|------------|----------|----------|
| get-stock-info | ticker=000001 | 返回股票实时行情 |
| get-historical-bars | ticker=000001, startDate=2024-01-01 | 返回 K 线数据 |
| get-technical-indicators | ticker=000001, startDate=2024-01-01 | 返回技术指标 |
| get-fundamental-data | ticker=000001 | 返回基本面数据 |
| get-sentiment | ticker=000001 | 返回情绪数据 |
| get-stock-news | ticker=000001, limit=5 | 返回新闻列表 |
| search-stock-by-name | name=贵州茅台 | 返回股票代码 |

---

## 13. 后续扩展

1. **聚合 Skill**: 创建顶级 `trading/SKILL.md` 作为入口
2. **组合 Skill**: 将多个 tool 组合为一个业务 Skill（如 "股票分析"）
3. **外部 Skill**: 支持从外部目录或远程加载 Skill
4. **Skill 版本管理**: 支持 Skill 热更新
