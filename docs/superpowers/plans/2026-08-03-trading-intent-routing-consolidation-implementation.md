# Trading 意图路由职责合并实现计划

> **致智能体工作者：** 必需子技能：使用 `executing-plans` 按任务逐步实现本计划。步骤使用复选框跟踪。

**目标：** 删除 Trading Client 6001 的二次 LLM 路由，由 3201 输出权威候选槽位，并通过确定性的 Java 节点完成身份预检和 Trading 启动。

**架构：** 统一路由 3201 负责意图分类及股票名称工具解析；`TradingRequestNode` 负责请求构造、类型映射和身份预检；`TradingStarter` 接收已验证的 `TargetContext` 后执行现有 pipeline。路由前置失败通过统一终止上下文返回，数据库使用 `V2030` 精确删除 6001 Client 数据。

**技术栈：** Java 17、Spring Boot、Spring AI、Maven、JUnit 5、Mockito、Flyway、MySQL。

---

### 任务 1：更新 3201 路由槽位契约

| 任务 | status |
|------|------|
| 任务 1：更新 3201 路由槽位契约 | pass |

**文件：**
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/StockSlot.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingPrompt.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingService.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/RoutingStructuredOutputValidator.java`
- 测试：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/*Test.java`

- [x] 编写失败测试，覆盖 `stockName`、标准 `stockQueryType`、废弃 `exchange` 和名称工具结果槽位。
- [x] 运行定向测试确认失败。
- [x] 更新模型、Schema、Prompt、Few-Shot、解析和 Validator。
- [x] 运行 `mvn -pl ai-agent-study-domain -am test -Dtest=IntentRoutingPromptTest,IntentRoutingServiceTest,IntentRoutingNodeTest -Dsurefire.failIfNoSpecifiedTests=false`。
- [x] 测试通过后更新任务和关联用例状态。

### 任务 2：实现 Trading Tool 构建期白名单

| 任务 | status |
|------|------|
| 任务 2：实现 Trading Tool 构建期白名单 | pass |

**文件：**
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiClientNode.java`
- 修改：`ai-agent-study-app/src/main/resources/application.yml`
- 测试：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/AiClientNodeToolIsolationTest.java`

- [x] 编写失败测试，覆盖缺省拒绝、空列表、去重和未知工具启动失败。
- [x] 将配置绑定为 `Map<String, List<String>>` 并生成不可变白名单。
- [x] 精确配置 3201 与 6002-6013，保持 MCP 和通用工具不变。
- [x] 运行 `AiClientNodeToolIsolationTest` 及 domain 模块测试。
- [x] 测试通过后更新任务和关联用例状态。

### 任务 3：实现 TradingRequestNode 与身份预检

| 任务 | status |
|------|------|
| 任务 3：实现 TradingRequestNode 与身份预检 | pass |

**文件：**
- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/service/AnalysisTypeMapper.java`
- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/TradingRequestNode.java`
- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/exception/StockIdentity*.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/service/TargetContextFactory.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/config/TradingStarter.java`
- 测试：对应新增及现有 `*Test.java`

- [x] 编写类型映射、ticker 规范化、名称校验和异常分类失败测试。
- [x] 实现 Mapper、领域异常和 `TargetContextFactory` 新契约。
- [x] 实现无 LLM 的 `TradingRequestNode` 及已验证 `TargetContext` Starter 重载。
- [x] 保留直接 Trading API 原入口并运行 trading-domain 测试。
- [x] 测试通过后更新任务和关联用例状态。

### 任务 4：切换路由、门禁与终止协议

| 任务 | status |
|------|------|
| 任务 4：切换路由、门禁与终止协议 | pass |

**文件：**
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/RoutingResultHandler.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNode.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/RootNode.java`
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/factory/DefaultAutoAgentExecuteStrategyFactory.java`
- 测试：对应路由、Root 和 SSE 测试。

- [x] 编写股票多任务整轮拒绝、`CLARIFICATION/ERROR` 互斥和持久化失败测试。
- [x] 将下游 Bean 切换为 `tradingRequestNode`，登记统一终止上下文。
- [x] 统一业务事件发送并保证外层是 emitter 唯一关闭者。
- [x] 运行 domain 路由与 SSE 回归测试。
- [x] 测试通过后更新任务和关联用例状态。

### 任务 5：删除 6001 并添加 V2030

| 任务 | status |
|------|------|
| 任务 5：删除 6001 并添加 V2030 | pass |

**文件：**
- 删除：6001 专属 Node、Prompt、Service 及专属测试。
- 修改：`ai-agent-study-app/src/main/resources/application.yml`
- 创建：`ai-agent-study-app/src/main/resources/db/migration/V2030__remove_trading_intent_client_6001.sql`
- 测试：新增迁移静态与内存数据模型测试。

- [x] 编写精确删除和 Prompt 6001 保留测试。
- [x] 删除 6001 代码、装配与配置引用。
- [x] 添加幂等、类型化谓词的 V2030 DML。
- [x] 运行 app 迁移测试和全仓 6001 引用扫描。
- [x] 测试通过后更新任务和关联用例状态。

### 任务 6：补齐集成与连续运行回归

| 任务 | status |
|------|------|
| 任务 6：补齐集成与连续运行回归 | pass |

**文件：**
- 测试：`TradingRoutingConsolidationIntegrationTest`、`TradingStarterPipelineTest`、`TradingChatMemoryTest`、`TradingNamespaceKeyFactoryTest`。

- [x] 覆盖连续换股、连续同股、唯一身份单次查询、runId/targetId 隔离与缓存复用。
- [x] 运行 domain、trading-domain 和 app 集成测试。
- [x] 测试通过后更新任务和关联用例状态。

### 任务 7：执行兼容性与全仓回归

| 任务 | status |
|------|------|
| 任务 7：执行兼容性与全仓回归 | pass |

**文件：**
- 测试：GENERAL_CHAT、PE、巡检、直接 Trading API、6002-6013 工具与 StockInfo 来源相关测试。
- 更新：`docs/trading-agent/2026-08-03-trading-intent-routing-consolidation-story.md`
- 更新：`docs/superpowers/test/2026-08-03-trading-intent-routing-consolidation-test.md`

- [x] 运行 `mvn clean compile -q`。
- [x] 运行各模块回归和 `mvn test`。
- [x] 核对所有 AC/TC、执行结果与遗留风险。
- [x] 全部验证通过后将剩余状态更新为 `pass` 并记录结论。
