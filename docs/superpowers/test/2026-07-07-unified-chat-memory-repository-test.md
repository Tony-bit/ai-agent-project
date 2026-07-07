# Test: 统一会话记忆与上下文

## 1. 测试背景

### 1.1 对应 Story / Change
- 设计文档：`docs/superpowers/plans/2026-07-07-unified-chat-memory-repository-design.md`
- 实施计划：`docs/superpowers/plans/2026-07-07-unified-chat-memory-repository-implementation.md`

### 1.2 测试目标
- 验证旧会话在 JVM/Redis 均无缓存时，`4001` 可从 MySQL 恢复模型上下文。
- 验证 `4001` 不再依赖 Spring AI 默认 `InMemoryChatMemoryRepository`。
- 验证 Advisor runtime 写入只进入 L1/Redis，并保持 `durable=false`。
- 验证 durable write 成功后以 MySQL 为准重建 L1/Redis，并标记 `durable=true`。
- 验证路由、拆分、补槽不再直接拼接 `ChatMemoryPersistenceService#getConversationHistory` 历史。
- 验证 Mem0 同步仍由 endSession / 手动按钮触发，不由 `saveTurn(...)` 内联触发。

### 1.3 测试范围
- 会话记忆服务：`ConversationMemoryService`
- Spring AI 适配：`SpringAiConversationMemoryRepository`
- Advisor 注入：`ConversationContextAdvisor`
- 业务上下文：`ConversationContextProvider`
- 路由/拆分/补槽/压缩节点
- 会话历史 HTTP 查询与 Redis/MySQL 恢复链路

### 1.4 不在本次测试范围
- Mem0 自身记忆抽取质量。
- LLM 回答内容质量，只校验是否携带历史上下文。
- Redis/MySQL 中间件性能压测。

---

## 2. 测试策略

### 2.1 测试分层
| 测试层级 | 是否覆盖 | 说明 |
|------|------|------|
| 单元测试 | 是 | 已覆盖 L1/Redis/MySQL fallback、runtime/durable、repository 转换 |
| 集成测试 | 是 | 验证旧 session MySQL 回源、Redis 回填、4001 注入 |
| 接口测试 | 是 | 验证会话历史接口、聊天接口、手动 Mem0 同步接口 |
| 回归测试 | 是 | 验证现有聊天、路由、压缩、endSession 不破坏 |
| 手工验证 | 是 | 验证真实 Redis/MySQL 状态和端到端聊天效果 |

### 2.2 测试原则
- E2E 验收使用真实应用、真实 MySQL、真实 Redis。
- LLM 内容不做逐字断言，只做上下文连续性断言。
- Redis key 验证以 `chat:session:<sessionId>` 为准。
- MySQL 永远作为事实源校验。

---

## 3. 测试场景设计

### 3.1 正常场景
| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-001 | 新会话正常聊天并持久化 | `advisor_id=4001` 的 `ext_param.maxMessages=20` | 发起一轮普通聊天 | MySQL 写入 user/assistant；Redis `chat:session:<sessionId>` 存在 runtime window；页面可查历史 | append |
| TC-002 | 旧会话 MySQL 回源恢复上下文 | MySQL 有历史；删除 Redis key；重启应用或换 JVM | 用同一 `sessionId` 追问“刚才我问了什么” | 模型能基于旧历史回答；Redis 被回填；下一次读取命中缓存 | append |
| TC-003 | Redis 命中后回填 L1 | TC-002 后继续追问 | 同一 `sessionId` 再问一个依赖历史的问题 | 响应仍连续；日志不应每次都从 MySQL 回源 | append |
| TC-004 | 4001 使用统一仓储 | 应用启动完成 | 通过配置了 `4001` 的 ChatClient 调用 | 旧历史可恢复；不出现 JVM 重启后历史丢失现象 | append |
| TC-005 | 路由场景使用 provider 上下文 | 旧 session 有历史 | 发起需要路由/拆分的复杂请求 | 路由行为可用；不会因节点手动拼历史和 4001 同时存在导致重复历史 | append |
| TC-006 | 压缩场景保留最近 20 条消息 | session 历史超过 20 条 | 触发压缩 | 压缩上下文包含摘要 + 最近 20 条以内消息；不会保留旧的 2 轮策略 | append |
| TC-007 | 手动 Mem0 同步仍可用 | session 已有 MySQL 历史 | 点击/调用手动同步按钮接口 | Mem0 同步走原有路径；不依赖 `saveTurn(...)` 内联触发 | append |
| TC-008 | endSession 同步仍可用 | session 达到结束判断 | 触发 endSession | 会话结束后同步 Mem0；短期上下文链路不直接调用 Mem0 | append |

### 3.2 异常场景
| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-101 | Redis key 不存在但 MySQL 存在 | 删除 `chat:session:<sessionId>` | 同 session 继续聊天 | 从 MySQL 恢复并回填 Redis/L1 | append |
| TC-102 | Redis 异常降级 | 暂停 Redis 或模拟连接异常 | 同 session 继续聊天 | 不阻断聊天；可从 MySQL 或 L1 降级恢复；记录 warning | append |
| TC-103 | MySQL 异常降级 | Redis/L1 有短期 runtime window，MySQL 暂不可用 | 同 session 连续追问 | 允许短期 runtime context 使用；不把 Redis 反写 MySQL | append |
| TC-104 | durable write 失败 | 模拟 `saveTurn` 写 MySQL 失败 | 完成一次模型响应 | Redis/L1 不升级 `durable=true`；记录 error；不覆盖 MySQL | append |
| TC-105 | Advisor runtime 保存异常 | 模型响应成功但 Redis 写失败 | 一轮普通聊天 | 不让本次聊天请求失败；最终持久化仍由 `saveTurn` 负责 | append |

### 3.3 边界场景
| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-201 | 空历史 session | MySQL/Redis 均无历史 | 发起第一轮聊天 | 不报错；从空上下文开始；结束后持久化 | append |
| TC-202 | 历史不足 20 条 | MySQL 有 6 条历史 | 继续聊天 | 注入全部最近历史，不触发越界 | append |
| TC-203 | 历史超过 20 条 | MySQL 有 30 条历史 | 继续聊天 | Advisor 最多注入最近 20 条原始消息 | append |
| TC-204 | Redis 为旧格式数组 | Redis 中保留旧 JSON array 格式 | 继续聊天 | 新代码兼容解析，并转换为 durable runtime window | append |
| TC-205 | 缺少 `conversation_context_scene` | 现有 client 未传 scene | 普通 ChatClient 调用 | 默认按 `chat` 场景处理，兼容旧调用 | append |

### 3.4 回归场景
| 编号 | 场景名称 | 前置条件 | 输入 | 预期结果 | status |
|------|------|------|------|------|------|
| TC-301 | 会话历史接口仍可用 | session 有历史 | 调用历史查询接口 | 返回最近历史，接口不报错 | append |
| TC-302 | 普通聊天仍可用 | 使用原有聊天入口 | 一轮普通问答 | SSE/HTTP 响应正常，历史持久化正常 | append |
| TC-303 | 路由测试链路仍可用 | 使用多任务/股票/通用问题 | 发起对应请求 | 路由、拆分、补槽结果稳定 | append |
| TC-304 | 4001 配置兼容 | 数据库仍配置 advisor `4001` | ChatClient 使用 `4001` | 不需要删除 advisor 配置即可运行 | append |
| TC-305 | 手动清 Redis 缓存语义 | session 有 MySQL 历史 | 调用清 Redis 缓存接口 | 只清 Redis/L1，不删除 MySQL 历史 | append |

---

## 4. 手工 E2E 验收步骤

| 步骤 | 操作 | 预期结果 | status |
|------|------|------|------|
| 1 | 确认数据库 `ai_client_advisor.advisor_id=4001` 的 `ext_param` 为 `{"maxMessages": 20}` | 配置正确 | append |
| 2 | 启动应用，创建新 `sessionId`，连续聊 3 轮，第二/三轮明确依赖前文 | 模型回答能承接前文 | append |
| 3 | 查询 MySQL `chat_message`，确认该 `sessionId` 有 user/assistant 记录 | MySQL 为事实源，有完整原始消息 | append |
| 4 | 查询 Redis `chat:session:<sessionId>` | key 存在，内容包含 `recentMessages`、`durable`、`source` 等字段 | append |
| 5 | 删除 Redis key：`DEL chat:session:<sessionId>` | Redis 中该 session 缓存为空 | append |
| 6 | 重启应用，或确认 JVM 内存已重新初始化 | L1 缓存为空 | append |
| 7 | 用同一 `sessionId` 追问依赖旧历史的问题 | 模型能从 MySQL 恢复上下文并回答 | append |
| 8 | 再次查询 Redis key | Redis 被 MySQL 回源结果回填，`durable=true`、`source=durable_rebuild` | append |
| 9 | 再连续追问一次 | 上下文连续；不需要再次依赖 MySQL 回源 | append |
| 10 | 调用手动 Mem0 同步或 endSession 流程 | Mem0 同步仍按原入口执行，`saveTurn` 不直接触发 Mem0 | append |

---

## 5. 关键 SQL / Redis 校验

### 5.1 advisor 配置
```sql
SELECT advisor_id, advisor_type, ext_param
FROM ai_client_advisor
WHERE advisor_id = '4001';
```

预期：
```json
{"maxMessages": 20}
```

### 5.2 MySQL 历史
```sql
SELECT session_id, message_index, role, LEFT(content, 100) AS content
FROM chat_message
WHERE session_id = '<sessionId>'
ORDER BY message_index ASC;
```

预期：
- user / assistant 成对存在。
- 历史总量可以大于 20，但运行时窗口只取最近 20 条。

### 5.3 Redis runtime window
```bash
GET chat:session:<sessionId>
```

预期字段：
```json
{
  "sessionId": "<sessionId>",
  "runtimeVersion": 20,
  "durableVersion": 20,
  "source": "durable_rebuild",
  "durable": true,
  "recentMessages": []
}
```

### 5.4 删除 Redis 缓存
```bash
DEL chat:session:<sessionId>
```

预期：
- 删除后同 session 再聊天，会从 MySQL 回源。
- 回源后 Redis key 重新出现。

---

## 6. 验收标准

| 编号 | 验收项 | 标准 | status |
|------|------|------|------|
| AC-001 | 旧会话可恢复 | JVM/L1 和 Redis 均为空时，`4001` 能从 MySQL 恢复模型上下文 | append |
| AC-002 | Redis 回填正确 | MySQL 回源后 Redis 被回填，且标记 `durable=true` | append |
| AC-003 | runtime 非事实源 | Advisor runtime 写入不直接写 MySQL，且标记 `durable=false` | append |
| AC-004 | MySQL 为事实源 | `saveTurn` 成功后以 MySQL 为准重建 Redis/L1 | append |
| AC-005 | 无重复注入 | 路由/拆分/补槽不再手动拼接同一份历史 | append |
| AC-006 | 窗口对齐 | Advisor 最多注入最近 20 条原始消息 | append |
| AC-007 | Mem0 边界正确 | Mem0 不由 `saveTurn` 直接触发，现有 endSession/手动按钮仍可用 | append |
| AC-008 | 回归通过 | 普通聊天、历史查询、路由、压缩链路无关键回归 | append |

---

## 7. 风险与说明

| 风险点 | 影响 | 应对措施 |
|------|------|------|
| LLM 回答不可完全确定 | 端到端结果无法逐字断言 | 用“是否引用前文事实”作为判断标准 |
| Redis 旧格式仍存在 | 新旧数据格式混用 | 新代码兼容旧数组格式，验收 TC-204 单独覆盖 |
| 已执行过的 migration 不会自动更新 seed | `4001` 仍可能为 200 | 验收前手动 SQL 修正 |
| durable write 失败较难手工制造 | 异常链路不易 E2E | 可通过单元测试或临时断开 MySQL 验证 |

---

## 8. 执行结果记录

### 8.1 执行结果
| 项目 | 结果 |
|------|------|
| 单元测试 | append |
| 集成测试 | append |
| 手工验证 | append |
| 编译验证 | append |

### 8.2 问题记录
| 编号 | 问题描述 | 影响范围 | 状态 |
|------|------|------|------|
| BUG-001 | 无 | 无 | append |

### 8.3 结论
- 是否达到验收条件：待执行 E2E 后确认。
