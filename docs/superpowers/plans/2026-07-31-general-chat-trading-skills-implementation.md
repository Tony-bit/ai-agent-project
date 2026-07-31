# GeneralChat 交易 Skills 完整调用链实现计划

> **致智能体工作者：** 必需子技能：使用 `executing-plans` 按任务逐步实现本计划，步骤使用复选框跟踪。

**目标：** 让 `GeneralChatNode` 的 client `3001` 能完成“股票名称解析 -> 唯一 ticker -> 历史行情 -> 收盘价”的完整 skills/tool 调用，同时保持 Trading Run 的 `TargetContext` 标的锁定。

**架构：** `AiClientNode` 根据实际 Advisor 和兼容配置统一装配 `SpringAiSkillAdvisor`、`read_skill` 与交易工具。`TradingToolCallbacks` 根据 `ToolContext` 是否包含 `TargetContext` 选择独立 ticker 模式或 Trading Run 锁定模式。`GeneralChatNode` 注入当前日期上下文，使模型可靠解析“昨天”等相对日期。

**技术栈：** Java 17、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.2、JUnit 4/5、Mockito、Maven

---

## 文件结构

- 修改 `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiClientNode.java`：统一 Trading Skills 能力判定与工具注册。
- 修改 `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/AiClientNodeToolIsolationTest.java`：覆盖 Advisor 驱动能力和最终回调集合。
- 修改 `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/tools/TradingToolCallbacks.java`：恢复 ticker schema，实现双模式 ticker 选择。
- 修改 `ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/tools/TradingToolCallbacksTest.java`：覆盖独立模式、锁定模式和非法 ticker。
- 修改 `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/chat/GeneralChatNode.java`：加入当前日期上下文。
- 修改 `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/chat/GeneralChatNodeToolInjectionTest.java`：固定时钟并验证日期上下文。

### 任务 1：统一 Skill Advisor 与工具能力

| 任务 | status |
|------|------|
| 任务 1：统一 Skill Advisor 与工具能力 | pass |

**文件：**
- 修改：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/AiClientNodeToolIsolationTest.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiClientNode.java`

- [ ] **步骤 1：编写失败测试**

增加测试，证明 `SpringAiSkillAdvisor` 可以让不在 YAML skills 白名单中的 `3001` 开启完整能力，并证明配置中的 `6001` 继续启用。

```java
assertTrue(node.isTradingSkillsEnabled(
        "3001", List.of(mock(SpringAiSkillAdvisor.class))));
assertTrue(node.isTradingSkillsEnabled("6001", List.of()));
assertTrue(node.shouldRegisterSpringToolCallback(
        "3001", tool("get_historical_bars"), true));
```

再用真实 `ToolCallbackRegistry` 和模拟 `ApplicationContext` 验证启用后最终名称同时包含 `read_skill`、交易工具和普通工具。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl ai-agent-study-domain -am "-Dtest=AiClientNodeToolIsolationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

预期：FAIL，缺少新的能力判定和注册签名。

- [ ] **步骤 3：实现最小生产代码**

在 Advisor 收集完成后计算：

```java
boolean tradingSkillsEnabled = isTradingSkillsEnabled(aiClientVO.getClientId(), advisors);
```

实现：

```java
boolean isTradingSkillsEnabled(String clientId, List<Advisor> advisors) {
    boolean advisorEnabled = advisors != null && advisors.stream()
            .anyMatch(SpringAiSkillAdvisor.class::isInstance);
    return advisorEnabled || getTradingSkillsEnabledClientIds().contains(clientId);
}
```

交易工具在 `tradingSkillsEnabled` 或 tools 白名单命中时注册。启用 skills 时保留普通工具原定义，只包装 `TRADING_TOOL_NAMES` 中的工具，最后注册 `read_skill`。日志输出 clientId、Advisor 状态、配置状态和最终工具名。

- [ ] **步骤 4：运行测试确认通过**

重复步骤 2 命令，预期 PASS。

### 任务 2：交易工具支持 GeneralChat 与 Trading Run 双模式

| 任务 | status |
|------|------|
| 任务 2：交易工具支持 GeneralChat 与 Trading Run 双模式 | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/tools/TradingToolCallbacksTest.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/tools/TradingToolCallbacks.java`

- [ ] **步骤 1：编写独立模式失败测试**

把旧的“无 context 必须失败”测试改为：

```java
callback.call("{\"ticker\":\"001309.SZ\",\"startDate\":\"2026-07-30\",\"endDate\":\"2026-07-30\"}");
verify(mockProvider).getHistoricalBars(
        "001309.SZ", "2026-07-30", "2026-07-30");
```

增加 ticker 缺失、空白、非法格式时不访问 Provider 的测试。保留 `TargetContext` 覆盖模型 ticker、错误类型 context、并发隔离和 Trading Run 禁止 `search_stock_by_name` 的测试。

- [ ] **步骤 2：编写 schema 失败测试**

断言六个交易数据工具的 `inputSchema()` 都包含 `ticker` 属性，但共享 schema 的 `required` 不包含 ticker；`get_historical_bars` 仍包含并要求 `startDate`、`endDate`。

- [ ] **步骤 3：运行测试确认失败**

运行：`mvn -pl ai-agent-study-trading/ai-agent-study-trading-infra -am "-Dtest=TradingToolCallbacksTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

预期：FAIL，当前工具无条件调用 `requireTarget()` 且 schema 无 ticker。

- [ ] **步骤 4：实现双模式 ticker 选择**

六个数据工具改用 `currentTarget(toolContext)`。`effectiveTicker` 实现以下规则：

```java
if (target != null) {
    recordOverrideWhenDifferent(input.get("ticker"), target);
    return target.targetId();
}
String ticker = requireAndNormalizeTicker(input.get("ticker"));
return ticker;
```

`requireAndNormalizeTicker` 接受 `^[0-9]{6}(\.(SH|SZ|BJ))?$`，缺失或非法时抛 `IllegalArgumentException`，由现有 ToolCallback 错误转换返回参数错误。错误类型 `TargetContext` 仍抛 `IDENTITY_BOUNDARY_VIOLATION`。

- [ ] **步骤 5：恢复 ticker schema 属性**

新增 schema helper，总是在 `properties` 中加入 ticker，但只把日期、limit 等业务参数加入 `required`。六个数据工具使用该 helper，`search_stock_by_name` 继续只要求 name。

- [ ] **步骤 6：运行测试确认通过**

重复步骤 3 命令，预期 PASS。

- [ ] **步骤 7：运行身份边界回归**

运行：`mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=TradingChatMemoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

预期：PASS，Trading Run 仍注入并校验不可变 `TargetContext`。

### 任务 3：为 GeneralChat 注入当前日期

| 任务 | status |
|------|------|
| 任务 3：为 GeneralChat 注入当前日期 | pass |

**文件：**
- 修改：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/chat/GeneralChatNodeToolInjectionTest.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/chat/GeneralChatNode.java`

- [ ] **步骤 1：编写固定日期失败测试**

用 `ReflectionTestUtils` 注入 `Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC)`，断言有无 userId 时系统上下文都包含 `2026-07-31`，有 userId 时仍包含用户标识。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl ai-agent-study-domain -am "-Dtest=GeneralChatNodeToolInjectionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

预期：FAIL，当前无 clock 字段且无 userId 返回 null。

- [ ] **步骤 3：实现日期上下文**

增加带默认值的可选注入字段：

```java
@Autowired(required = false)
private Clock clock = Clock.systemDefaultZone();
```

`buildSystemPrompt` 始终输出 `[上下文] 当前日期: yyyy-MM-dd`，userId 非空时追加 `[上下文] 当前用户ID: ...`。

- [ ] **步骤 4：运行测试确认通过**

重复步骤 2 命令，预期 PASS。

### 任务 4：完整回归与差异检查

| 任务 | status |
|------|------|
| 任务 4：完整回归与差异检查 | pass |

**文件：**
- 验证：`GeneralChatNodeTest.java`
- 验证：`ProgressiveDisclosureTest.java`
- 验证：`SkillRegistryIntegrationTest.java`
- 验证：`TradingChatMemoryTest.java`

- [ ] **步骤 1：运行 Domain 聚焦回归**

运行：`mvn -pl ai-agent-study-domain -am "-Dtest=AiClientNodeToolIsolationTest,GeneralChatNodeToolInjectionTest,GeneralChatNodeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

预期：PASS。

- [ ] **步骤 2：运行 Trading Skills 与工具回归**

运行：`mvn -pl ai-agent-study-trading/ai-agent-study-trading-infra -am "-Dtest=TradingToolCallbacksTest,ProgressiveDisclosureTest,SkillRegistryIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

预期：PASS。

- [ ] **步骤 3：执行编译验证**

运行：`mvn -pl ai-agent-study-domain,ai-agent-study-trading/ai-agent-study-trading-infra -am "-DskipTests" package`

预期：BUILD SUCCESS。

- [ ] **步骤 4：检查最终差异**

运行 `git diff --check`、`git status --short` 并审阅三个生产文件的差异。不得改动密钥、数据库数据或无关模块。相关文件已有用户改动，不创建会混入既有改动的实现提交。

## 验收标准

- `3001` 绑定 `TradingSkill` Advisor 时拥有 `read_skill`、`search_stock_by_name`、`get_historical_bars` 等回调。
- 示例请求可形成 `read_skill -> search_stock_by_name -> read_skill -> get_historical_bars -> 最终文本` 调用链。
- GeneralChat 无 `TargetContext` 时使用合法输入 ticker；Trading Run 有 context 时始终使用 `targetId`。
- 名称多候选、ticker 非法、单日无行情和流式工具异常均不生成虚构数据。
- 聚焦测试、Trading 回归和 Maven 编译全部通过。
