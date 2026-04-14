# 智能巡检智能体 - 变动文档

> 本次新增了 `IntelligentInspection` 智能巡检节点，作为独立智能体类型，可直接启动执行系统巡检任务。

---

## 一、核心变更

### 1. 新增文件

| 文件路径 | 说明 |
|---------|------|
| `ai-agent-study-domain/.../step/react/IntelligentInspection.java` | 智能巡检执行节点，实现系统巡检任务逻辑 |

### 2. 新增接口

**POST** `/api/v1/agent/inspection`

专门用于触发起始智能体，固定路由到 `IntelligentInspection` 节点，执行完成后直接结束流程。

**与 `auto_agent` 的区别**：

| 对比项 | `auto_agent` | `inspection` |
|-------|-------------|--------------|
| 执行流程 | 分析 → 执行 → 监督 → 总结（多步） | 巡检节点单步执行后结束 |
| maxStep | 可配置，默认为 3 | 固定为 1 |
| agentType | `"default"` 或 null | `"inspection"` |

### 3. 字段变更

#### `ExecuteCommandEntity` 新增字段

```java
/**
 * 智能体类型：
 * - null / "default": 默认对话流程（分析 -> 执行 -> 监督 -> 总结）
 * - "inspection": 智能巡检流程（直接执行巡检任务后结束）
 */
private String agentType;
```

#### `AutoAgentRequestDTO` 新增字段

```java
/**
 * 智能体类型：
 * - null / "default": 默认对话流程（分析 -> 执行 -> 监督 -> 总结）
 * - "inspection": 智能巡检流程（直接执行巡检任务后结束）
 */
private String agentType;
```

---

## 二、逻辑变更

### 1. `RootNode.get()` 路由扩展

根据 `agentType` 决定第一步走哪个节点：

```java
public StrategyHandler<...> get(...) {
    if ("inspection".equals(requestParameter.getAgentType())) {
        return intelligentInspection;
    }
    return step1AnalyzerNode;
}
```

### 2. `AutoAgentExecuteStrategy.execute()` 初始化提前

对于 `agentType=inspection`，在 `RootNode` 之前预先加载 `PRECISION_EXECUTOR_CLIENT` 配置（因为巡检节点不经过标准多步流程链，无法在 `RootNode` 中获取）。

### 3. `IntelligentInspection` 执行逻辑

- 从 `DynamicContext` 获取 `PRECISION_EXECUTOR_CLIENT` 配置，组装 Prompt 并调用 ChatClient
- 通过 SSE 向前端发送 `type=supervision` + `subType=inspection_report` 的巡检报告
- 发送 `type=complete` 标识结束流程
- 记录 Langfuse trace/span

---

## 三、启动方式

### 1. 数据库配置

需要在智能体流程配置表（`ai_agent_client_flow_config`）中，准备好对应 `aiAgentId` 的 **PRECISION_EXECUTOR_CLIENT** 节点配置：

| 字段 | 说明 |
|-----|------|
| clientId | ChatClient Bean 名称 |
| stepPrompt | 完整 Prompt 模板（包含 `%s` 占位符） |

Prompt 模板中 `%s` 的填充顺序：

| 占位符顺序 | 填充内容 |
|-----------|---------|
| 第 1 个 `%s` | 用户传入的 message（完整 Prompt 配置） |
| 第 2 个 `%s` | 当前时间 `yyyy-MM-dd HH:mm:ss` |
| 第 3 个 `%s` | 会话 sessionId |

### 2. 请求示例

```bash
curl -X POST http://localhost:8080/api/v1/agent/inspection \
  -H "Content-Type: application/json" \
  -d '{
    "aiAgentId": "your-agent-id",
    "message": "完整的巡检Prompt内容...",
    "sessionId": "session-xxx"
  }'
```

### 3. Prompt 参考模板

你的 Prompt 配置中需要包含以下结构化摘要约束（供前端程序化解析）：

```
## 输出格式约束
- 最终输出必须为纯文本报告，不要有多余的思考过程或自我解释
- 如果检测到异常，在报告末尾补充一行结构化摘要：
  检测时间: %s
  检测到的listId列表: <逗号分隔的listId>
  异常类型: <正常/用户缓存异常/Redis缓存刷新失败/数据库队列存储为空>
  影响范围: <数字> 条记录受影响
```

---

## 四、SSE 返回格式

| type | subType | 说明 |
|------|---------|------|
| `supervision` | `inspection_report` | 巡检报告内容 |
| `complete` | - | 流程结束标识 |

示例：

```json
{
  "type": "supervision",
  "subType": "inspection_report",
  "content": "【巡检报告】时间: 2026-04-07 15:30:00 状态: ✅ 正常 摘要: 未检测到榜单队列为空的情况...",
  "completed": false,
  "timestamp": 1744029000000,
  "sessionId": "session-xxx"
}
```

---

## 五、依赖说明

| 依赖 | 说明 |
|-----|------|
| `IntelligentInspection` | `@Service` 自动注入，无需手动注册 |
| `PRECISION_EXECUTOR_CLIENT` 配置 | 必须在数据库中配置，否则抛异常 |
| `ObservabilityService` | Langfuse 可观测性服务，已注入基类 |

---

*文档生成时间: 2026-04-07*
