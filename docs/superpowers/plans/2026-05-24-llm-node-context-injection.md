# LLM 节点统一上下文注入 + 固定5分钟缓存方案



**Metadata:**

- 状态: pass

- 预估工时: 1.5h

- 日期: 2026-05-24

- 负责人: Denny



> **For agentic workers:** Use superpowers:subagent-driven-development to implement this plan.



**Goal:** 两个目标：（1）将画像注入逻辑抽取到 `AbstractExecuteSupport`，在 `RootNode` 入口处统一注入一次，所有子节点通过幂等检查复用；（2）将 Redis 缓存 TTL 从 30 分钟改为固定 5 分钟，删除 TTL 刷新逻辑，缓存到期后重新查询 Mem0 获取最新画像。



**Architecture:**

- **注入层**：`RootNode` 作为入口节点，在 `router()` 调用前统一调用一次 `injectPersonaContext()`；子节点通过幂等检查（`persona` 已存在则跳过）复用已注入的画像，无需重复查询 Redis

- **缓存层**：`ICrossSessionMemoryCacheService` 接口和 `CrossSessionMemoryCacheServiceImpl` 实现修改：TTL 改为 5 分钟，删除 `refreshTtl()` 方法，命中时不再刷新 TTL



<details>

<summary><strong>Background (点击展开)</strong></summary>



### 问题一：画像未在所有 LLM 节点中注入



**问题现象**



当前只有 `Step1AnalyzerNode` 注入了跨会话用户画像（`persona`）到 `DynamicContext`，其他 7 个有 LLM 调用的节点（`IntentRoutingNode`、`GeneralChatNode`、`SessionEndJudgementNode`、`IntelligentInspection`、`Step2PrecisionExecutorNode`、`Step3QualitySupervisorNode`、`Step4LogExecutionSummaryNode`）均未注入，导致这些节点的 LLM 调用缺少用户画像上下文。



**根因分析**



- `Step1AnalyzerNode` 在 2026-05-24 的 Mem0 Persona 集成中新增了画像注入逻辑，但该逻辑直接写在节点内部

- 其他节点各自独立实现，没有复用机制

- `AbstractExecuteSupport` 作为所有节点的基类，未提供统一注入上下文的能力



### 问题二：缓存 TTL 行为不符合预期



**问题现象**



当前 `CrossSessionMemoryCacheServiceImpl` 的 `DEFAULT_TTL_MINUTES = 30`，且每次命中缓存时都调用 `expire()` 刷新 TTL。这导致：

- 画像变更后（Mem0 中已更新），用户可能在 30 分钟后才能看到最新画像

- 高频用户画像永远不过期，无法感知用户偏好变化



**根因分析**



- TTL 固定为 30 分钟硬编码常量，无配置化

- 命中缓存时主动调用 `expire()` 刷新，等同于"访问刷新"，导致活跃用户的画像永远驻留缓存



**方案选型**



| 方案 | 说明 | 结论 |
|------|------|------|
| 方案A - 新增中间基类 | 新增接口 + 实现类 + 组合器 + 抽象基类（4个文件） | **不采用** — 破坏性大，扩展性强但当前不需要 |
| 方案B - RootNode统一注入 + 幂等复用 | RootNode 入口处调一次 injectPersonaContext，子节点幂等检查跳过 | **采用** — 改动最少，Redis 查询从 N 次降为 1 次 |
| 方案C - 每个节点显式调用 | 每个子类 doApply() 开头手动调 injectPersonaContext（8处） | **不采用** — 与原方案一致，但会产生 N 次 Redis 查询 |
| 方案D - 固定 TTL + 删刷新逻辑 | 改常量值，删除 expire() 调用 | **采用** — 改动最小，效果明确 |



</details>



**Tech Stack:** Java, Spring AI, Spring `@Resource` DI, Redis, Mem0



**执行顺序:** Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6 → Task 7



---

## 任务状态

| 任务 | 状态 |
|------|------|
| 0. RootNode.doApply() 在 router() 前直接调用 injectPersonaContext | pass |
| 1. AbstractExecuteSupport 新增注入器依赖和 injectPersonaContext 公共方法 | pass |
| 2. Step1AnalyzerNode 移除重复注入代码（幂等跳过，无需手动调用） | pass |
| 3. ICrossSessionMemoryCacheService 接口修改（TTL 30→5，删除 refreshTtl） | pass |
| 4. CrossSessionMemoryCacheServiceImpl 实现修改（删除 expire() 调用和 refreshTtl 方法） | pass |
| 5. CrossSessionMemoryProperties 增加 TTL 配置化 | pass |
| 6. 单元测试更新 | pass |
| 7. 编译验证 | pass |



---

## 变更文件汇总



| 文件 | 改动类型 | 改动内容 |
|------|---------|---------|
| `AbstractExecuteSupport.java` | 修改 | 新增 `injectPersonaContext()` 及注入器依赖 |
| `RootNode.java` | 修改 | `doApply()` 在 `router()` 前调用 `injectPersonaContext()` |
| `CrossSessionMemoryProperties.java` | 修改 | 新增 `crossSessionMemoryTtlMinutes` 字段 |
| `Step1AnalyzerNode.java` | 修改 | 删除 `crossSessionMemoryCacheService`/`crossSessionMemoryProperties` 字段及注入 if block |
| `ICrossSessionMemoryCacheService.java` | 修改 | TTL 30→5，删除 `refreshTtl()` |
| `CrossSessionMemoryCacheServiceImpl.java` | 修改 | 删除 `expire()` 调用、`refreshTtl()` 方法 |
| `CrossSessionMemoryCacheServiceImplTest.java` | 修改 | 删除 refreshTtl 测试，修改缓存命中断言，更新 TTL 断言值 |
| `AbstractExecuteSupportTest.java` | **新增** | 8 个测试用例 |
| `RootNodeTest.java` | **新增** | 1 个测试用例 |
| `Step1AnalyzerNodeTest.java` | **新增** | 1 个测试用例 |



> **注入架构说明：** 画像注入由 `RootNode.doApply()` 入口统一执行一次，通过 `injectPersonaContext` 内部幂等检查（`persona` 已存在则跳过），后续所有子节点无需任何改动即可复用已注入的上下文。
