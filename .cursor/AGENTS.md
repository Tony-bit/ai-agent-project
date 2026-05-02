# ai-agent-study — Spring Trading Agent 项目指南

## 项目概述

本项目是一个基于 Spring AI 的多 Agent 智能对话框架，参考 [TradingAgents](https://github.com/TauricResearch/TradingAgents) 设计理念，目标是在 Java 工程中实现纯 Java 版本的股票智能分析多 Agent 系统。

**设计文档位置**：`docs/trading-agent/2026-04-21-spring-trading-agent-design.md`

---

## 当前任务：实现 Spring Trading Agent（STA）

### 项目背景

- **现有能力**：Auto Agent 编排框架（bugstack/wrench 树形 StrategyHandler）、多节点链式执行、流式 SSE、会话记忆、可观测性（Langfuse）
- **目标**：新增股票分析 Agent 场景，通过意图识别自动路由

### 架构设计（详见设计文档）

```
用户请求
    │
    ▼
IntentRoutingNode（新增） ←——— 前置于 Step1AnalyzerNode
    │
    ├── 股票分析意图 ──────────────────┐
    │                                 │
    ▼                                 ▼
TradingRootNode          原有 Agent 流程
    │                          │
    ├── 分析师团队（并行）         │
    │   Fundamental / Technical /  ...
    ├── Bull/Bear 辩论（多轮）    │
    ├── Trader                  │
    └── 风控 + Portfolio Manager │
    │
    ▼
FinalReport（SSE 流式输出）
```

---

## 开发策略：先跑通链路，后替换数据

### 为什么先用 Mock 数据

| 阶段 | 重点 | 数据 |
|------|------|------|
| Phase 1-5 | **验证 Agent 链路逻辑**（意图路由、分析师、辩论、交易员、风控、SSE） | Mock 数据 |
| Phase 6 | **替换为真实数据源**，同时加缓存和降级 | Yahoo Finance API |

这样做的好处：
- **快速验证**：不依赖网络，随时可跑
- **隔离关注点**：先确认 Agent 编排正确，再处理数据问题
- **后续迭代**：Phase 6 只需替换 `IStockDataProvider` 的实现，不影响上层节点逻辑

---

## 任务领取方式

**设计文档就是任务清单**，每个任务有唯一的 ID（如 `T0-01`、`T1-07`）。

### 任务执行流程

1. **查看进度**：读取设计文档，找到下一个 `Status: [ ]` 的任务
2. **领取任务**：告诉我任务 ID，如"我来做 T1-01"
3. **完成代码**：实现该任务的所有内容
4. **标记完成**：我会在设计文档中将该任务的 `Status: [ ]` 改为 `Status: [PASS]`
5. **领取下一个**：继续找下一个 `Status: [ ]` 的任务

### 任务状态标记

| 标记 | 含义 |
|------|------|
| `[ ]` | 待做（TODO） |
| `[>]` | 进行中（In Progress） |
| `[PASS]` | 已完成（Done） |
| `[FAIL]` | 失败（Blocked/Failed，需人工介入） |

### 任务分组

| Phase | 内容 | 任务数 |
|-------|------|--------|
| Phase 0 | 模块骨架、配置、Domain 模型 | 11 |
| Phase 1 | IntentRoutingNode + 数据 Provider（Mock） | 8 |
| Phase 2 | 4 个分析师节点（Mock 数据） | 9 |
| Phase 3 | 多空辩论团队 + Research Manager | 4 |
| Phase 4 | Trader + 风控团队 + Portfolio Manager | 6 |
| Phase 5 | 流式 SSE 集成 + 可观测性 | 3 |
| Phase 6 | 真实数据源接入（Yahoo Finance） | 3 |
| Phase 7 | Prompt 调优 + 五档评分机制 | 3 |
| Phase 8 | 意图置信度优化 + 独立端点 | 2 |
| **合计** | | **49** |

---

## 开发约定

### 代码规范

- **包命名**：`denny.ai.agent.trading.{api,domain,infra}.*`
- **模块结构**：遵循现有 DDD 分层（trigger / domain / infrastructure / api / types / app）
- **Agent 节点**：继承 `AbstractExecuteSupport`，遵循现有 `StrategyHandler` 模式
- **流式输出**：通过 `sendSseResult()` 发送 SSE 事件，格式参见设计文档 Phase 5
- **配置管理**：使用 `@ConfigurationProperties`，遵循现有 `*Properties` 模式

### 现有可复用组件

- `AbstractExecuteSupport`：基础执行支撑类，提供 `getChatClientByClientId()`、`sendSseResult()`、`persistConversation()` 等方法
- `DynamicContext`：上下文对象，通过 `setValue()/getValue()` 传递数据
- `ExecuteCommandEntity`：执行命令实体
- `ChatMemoryPersistenceService`：会话记忆持久化
- `ObservabilityService`：可观测性服务（Langfuse）

### 数据层约定（重要）

- **Phase 1-5**：使用 `MockStockDataProvider`，数据硬编码，用于验证链路
- **Phase 6**：替换为 `YahooFinanceStockDataProvider`，同时保留 Mock 作为降级兜底
- Provider 接口（`IStockDataProvider`）从 Phase 1 就确定，后续实现可替换但接口不变

### 提交规范

每个 Task 完成后单独提交：

```
sta: [T{n}-{nn}] {简要描述}
```

例如：`sta: [T0-01] 创建 ai-agent-study-trading 父 POM`

---

## 设计文档快速索引

| 需要了解的内容 | 查看设计文档章节 |
|--------------|----------------|
| 整体架构和流程 | 第 2 节 |
| 所有 Phase 和任务清单 | 第 3 节起 |
| IntentRoutingNode 设计 | Phase 1, T1-07 |
| 各分析师节点设计 | Phase 2, T2-05 ~ T2-08 |
| 辩论流程设计 | Phase 3, T3-02 ~ T3-04 |
| Trader + 风控设计 | Phase 4, T4-02 ~ T4-05 |
| 数据 Provider 接口 | Phase 1, T1-01 |
| Mock 数据策略 | Phase 1, T1-02 |
| Yahoo Finance 接入 | Phase 6, T6-01 |
| HTTP 接口格式 | Phase 8, T8-01 |
| Prompt 模板 | Phase 2, T2-09 |
| 异常处理策略 | 设计文档第 10 节 |

---

## 项目结构

```
ai-agent-study/
├── ai-agent-study-trigger/       # HTTP 入口层
├── ai-agent-study-domain/         # 领域层（Agent 编排）
├── ai-agent-study-infrastructure/ # 基础设施层
├── ai-agent-study-api/           # 接口定义
├── ai-agent-study-types/         # DTO/枚举/值对象
├── ai-agent-study-app/           # 应用层（启动/配置）
├── ai-agent-study-inspection/     # 巡检 Agent（现有）
├── ai-agent-study-trading/        # 🆕 股票分析 Agent 模块
│   ├── ai-agent-study-trading-api/     # API 子模块
│   ├── ai-agent-study-trading-domain/  # 领域子模块
│   └── ai-agent-study-trading-infra/    # 基础设施子模块
├── docs/trading-agent/           # 📄 设计文档
└── .cursor/AGENTS.md            # 本文件
```

---

*本文档由 ai-agent-study 项目维护，每次对话自动加载。*
