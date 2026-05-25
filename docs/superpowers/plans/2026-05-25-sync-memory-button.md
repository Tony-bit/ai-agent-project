# 同步记忆按钮功能设计方案

**Metadata:**
- 状态: draft
- 预估工时: 1h
- 日期: 2026-05-25
- 版本: v1

> **For agentic workers:** Use superpowers:subagent-driven-development to implement this plan.

---

## 任务状态跟踪

| 序号 | 任务项 | 状态 |
|------|--------|------|
| 1 | 后端新增同步接口 | pending |
| 2 | 前端新增同步按钮 | pending |
| 3 | 编写单测 | pending |
| 4 | 编译验证 | pending |

---

## 目标

在 AI Chat UI 底部状态栏添加"同步记忆"按钮，允许用户手动触发将当前会话的未同步内容同步到 Mem0 长期记忆。

---

## 需求决策

| 问题 | 选项 | 决策 |
|------|------|------|
| 按钮位置 | A. 左侧面板标题栏 / B. 右侧面板标题栏 / **C. 会话状态栏** | **C** |
| 按钮文案 | **A. "同步记忆"** / B. "同步到Mem0" / C. 带图标 | **A** |
| 交互逻辑 | **A. 立即同步** / B. 带确认 / C. 可配置 | **A** |

---

## 1. 现状分析

### 1.1 现有同步能力

已实现 `ChatSessionMemorySyncService.syncSessionToMemory()` 方法：

- 查询 `addMemory=0` 的会话记录
- 调用 `Mem0RestClient.addMemory()` 写入 Mem0
- 批量更新 `addMemory=1`

**现有调用方：** `SessionEndDetectionServiceImpl`（会话结束时自动触发）

### 1.2 缺少的能力

| 能力 | 描述 | 状态 |
|------|------|------|
| HTTP 接口 | 供前端调用的同步接口 | **缺失** |
| 前端按钮 | 触发同步的 UI 按钮 | **缺失** |

---

## 2. 架构设计

### 2.1 调用链路

```
用户点击按钮
     ↓
前端 POST /api/v1/session/{sessionId}/sync-memory?userId=xxx
     ↓
ChatSessionController.syncSessionMemory()
     ↓
ISessionMemoryPersistenceService.syncSessionToMemory()
     ↓
ChatSessionMemorySyncService.syncSessionToMemory()
     ↓
Mem0RestClient.addMemory()
     ↓
返回成功/失败 toast
```

### 2.2 后端接口设计

**接口:** `POST /api/v1/session/{sessionId}/sync-memory`

**请求参数:**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sessionId | Path | 是 | 会话ID |
| userId | Query | 是 | 用户ID |

**响应:**
```json
{
  "code": "200",
  "message": "success",
  "data": null
}
```

---

## 3. 详细改动

### 3.1 后端改动

**文件:** `ai-agent-study-trigger/.../ChatSessionController.java`

| 改动 | 说明 |
|------|------|
| 新增注入 | `ISessionMemoryPersistenceService sessionMemoryPersistenceService` |
| 新增方法 | `syncSessionMemory(sessionId, userId)` |

### 3.2 前端改动

**文件:** `docs/dev-ops/nginx/html/index.html`

| 改动 | 说明 |
|------|------|
| 新增按钮 | 在会话状态栏添加"同步记忆"按钮 |
| 新增调用 | 点击调用 `/api/v1/session/{sessionId}/sync-memory` |
| 新增提示 | 同步成功/失败显示 toast 提示 |

---

## 4. 按钮样式

```
┌─────────────────────────────────────────────────────────────┐
│ 会话ID: abc123-xyz    [通用对话]    [📚 同步记忆]           │
└─────────────────────────────────────────────────────────────┘
```

- **位置:** 会话状态栏，会话ID标签和模式标签之后
- **文案:** "同步记忆"
- **图标:** 📚 (可选)
- **样式:** 紫色渐变，与现有"AI 思考"按钮风格一致

---

## 5. 前端调用示例

```javascript
// 同步记忆按钮点击处理
async function syncMemory() {
    try {
        const sessionId = getCurrentSessionId();
        const userId = getCurrentUserId();

        await fetch(`/api/v1/session/${sessionId}/sync-memory?userId=${userId}`, {
            method: 'POST'
        });

        showToast('同步成功', 'success');
    } catch (error) {
        showToast('同步失败: ' + error.message, 'error');
    }
}
```

---

## 6. Toast 提示设计

| 场景 | 提示文案 | 类型 |
|------|----------|------|
| 无需同步 | "暂无需要同步的记忆" | info |
| 同步成功 | "已同步 N 条记忆到 Mem0" | success |
| 同步失败 | "同步失败，请重试" | error |

---

## 7. 依赖文件

| 序号 | 文件 | 改动类型 |
|------|------|----------|
| 1 | `ai-agent-study-trigger/.../ChatSessionController.java` | 修改 |
| 2 | `docs/dev-ops/nginx/html/index.html` | 修改 |

---

## 8. 测试验证

### 8.1 后端单测

- 测试接口参数校验
- 测试无记忆需同步场景
- 测试同步成功场景
- 测试同步失败场景（Mock Mem0 调用）

### 8.2 手动验证

1. 打开前端页面
2. 发起几次对话
3. 点击"同步记忆"按钮
4. 确认 toast 提示正确
5. 确认无重复同步（再次点击应提示"暂无需要同步"）
