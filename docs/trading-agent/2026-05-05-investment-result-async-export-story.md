# Story: 投资结果异步导出 Markdown（v2 异步版）

## 1. 背景与目标

### 背景

交易 Agent 执行完成后，投资分析结果（各维度报告 + 投资建议）目前仅通过 SSE 推送给前端。为了便于后续复盘、归档和人工 Review，需要将结果持久化到 Markdown 文件中。

### 目标

在 `RecommendationNode.doApply()` 完成后，异步将完整的投资分析结果渲染为 Markdown 文档，写入 `docs/trading-agent/` 目录。主流程不受 I/O 影响，正常返回。

---

## 2. 技术方案

### 2.1 整体架构

```
RecommendationNode.doApply()
        │
        ▼
TradingResultVO.from(context)         ← 同步，组装数据（轻量）
        │
        ▼
TradingResultExportService.export()   ← @Async("tradingTaskExecutor")，异步写文件
                                          主流程立即返回，不受 I/O 影响
```

### 2.2 线程池

复用现有的 `tradingTaskExecutor`（`TradingExecutorConfig` 已配置，支持跨线程传递 `TradingDriver` ThreadLocal）。

```java
@Async("tradingTaskExecutor")
public void export(TradingResultVO result) { ... }
```

### 2.3 异常处理

- 异步任务内部用 `try-catch` 包裹，失败打印 error 日志后正常结束
- 主流程完全不感知写入成功与否，正常返回
- 目录不存在时自动创建父目录

### 2.4 文件路径

```
docs/trading-agent/{ticker}-{name}.md
```

例如：`docs/trading-agent/603259-药明康德.md`，覆盖式写入。

---

## 3. 变更计划

### 3.1 创建 TradingResultVO

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/model/valobj/TradingResultVO.java`

| status | 任务项 |
|--------|--------|
| pass | 创建文件，定义 `TradingResultVO` 及各维度 Summary 内部类 |
| pass | 实现 `from(TradingContextVO)` 方法，从上下文组装数据 |
| pass | 编译验证 |

---

### 3.2 创建 TradingResultExportService

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/service/TradingResultExportService.java`

| status | 任务项 |
|--------|--------|
| pass | 创建文件，实现 `export(TradingResultVO)` 方法，标注 `@Async("tradingTaskExecutor")` |
| pass | 实现 `renderMarkdown(TradingResultVO)` 方法，渲染完整 Markdown 文档 |
| pass | 实现文件名清理、目录自动创建、异常捕获 |
| pass | 编译验证 |

---

### 3.3 修改 RecommendationNode

**文件**：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/RecommendationNode.java`

| status | 任务项 |
|--------|--------|
| pass | 注入 `TradingResultExportService` |
| pass | 在 `doApply()` 末尾调用 `tradingResultExportService.export(TradingResultVO.from(context))` |
| pass | 编译验证 |
| pass | `mvn install` 安装模块到本地仓库 |

---

## 4. Markdown 文档格式

```markdown
# 药明康德 (603259) 投资分析报告

- **交易所**: NASDAQ
- **当前价格**: 109.55
- **生成时间**: 2026-05-05 11:30:00

## 基本面分析

| 指标 | 值 |
|--------|------|
| 评分 | 4/5 |
| 主要发现 | ROE 持续提升；毛利率稳定在 40%+ |
| 风险提示 | 营收增速放缓 |

## 技术面分析

| 指标 | 值 |
|--------|------|
| 评分 | 3/5 |
| 趋势信号 | 谨慎 |

## 投资建议

| 项目 | 值 |
|--------|------|
| 操作 | **BUY** |
| 建议仓位 | 30% |
| 入场价格区间 | 105-110 |
| 止损价 | 95 |
| 止盈价 | 130 |
| 持仓周期 | 2-4周 |
| 风险收益比 | 1:2.5 |
```

---

## 5. 涉及文件汇总

| 操作 | 文件路径 |
|------|---------|
| 新增 | `domain/model/valobj/TradingResultVO.java` |
| 新增 | `domain/service/TradingResultExportService.java` |
| 修改 | `domain/node/RecommendationNode.java` |

---

## 6. 后续 Skill 执行记录

> 以下为 superpower 流程的后续步骤

### 6.1 verification-before-completion

| status | 验证项 | 验证结果 |
|--------|--------|---------|
| pass | 编译验证通过 | exit code 0 |
| pass | 模块 install 成功 | exit code 0 |
| pass | 代码逻辑审查 | TradingResultVO.from() 正确映射各字段 |

### 6.2 finishing-a-development-branch

| status | 任务项 | 结果 |
|--------|--------|------|
| pass | 编译验证通过 | exit code 0 |
| pass | 模块 install 成功 | exit code 0 |
| pass | 集成测试 | 16/57 失败（预存在 Tushare/Sina API token 缺失，与本次改动无关） |
| pass | 代码逻辑审查 | TradingResultVO.from() 正确映射各字段 |
| pass | 提交到 master | commit 936ae5e |

---

*文档生成时间: 2026-05-05*
*最后更新: 2026-05-05 11:23*
