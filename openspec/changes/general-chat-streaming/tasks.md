# 实现任务清单

## 状态说明

- [ ] 待办
- [x] 完成

---

## 任务 0: 重构 AutoAgentStrategy 异常处理

**文件**: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/AutoAgentExecuteStrategy.java`

### 子任务 0.1: 新增 safeComplete 辅助方法

- [x] 创建 `safeComplete(ResponseBodyEmitter emitter, String errorMessage)` 方法
  - 参数：`emitter`, `errorMessage`（可选）
  - 实现：emitter 为空时直接返回；发送错误消息后调用 `complete()`；捕获异常并记录日志

### 子任务 0.2: 重构 execute 方法

- [x] 将 `execute()` 中的 `emitter.complete()` 调用改为 `safeComplete()`
- [x] 添加 try-catch 捕获节点链异常
- [x] 异常时调用 `safeComplete(emitter, "执行异常：" + e.getMessage())`

### 子任务 0.3: 简化 Controller 层

- [x] 移除 `AiAgentController.processAutoAgentRequest()` 中的 try-catch（可选，如已有则保持）

---

## 任务 1: 修改 GeneralChatNode.java (伪流式)

**⚠️ 注意**: 此任务的 `streamToEmitter()` 使用了 `collectList().block()`，属于伪流式。
**请使用任务 4 进行真流式改造。**

**文件**: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/chat/GeneralChatNode.java`

### 子任务 1.1: 添加必要 import

- [x] 添加 `reactor.core.publisher.Flux`
- [x] 添加 `java.util.concurrent.CountDownLatch`
- [x] 添加 `org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter`

### 子任务 1.2: 新增 streamToEmitter 辅助方法

- [x] 创建 `streamToEmitter()` 方法
  - 参数：`DynamicContext`, `ChatClient.ChatClientRequestSpec`, `subType`, `sessionId`
  - 返回：`String` (完整响应内容)
  - 实现：
    - 获取 emitter
    - emitter 为空时降级为同步调用
    - 发送 `xxx_start` 事件
    - 使用 `Flux<String>` 订阅流
    - **同步阻塞等待流完成**（`collectList().block()`）⚠️ 伪流式
    - 逐块发送到 SSE
    - 错误处理
    - 返回完整内容

### 子任务 1.3: 修改 doTextApply() 方法

- [x] 将 `promptBuilder.call().content()` 改为 `streamToEmitter(dynamicContext, promptBuilder, "general_chat_response", request.getSessionId())`

### 子任务 1.4: 修改 doMultimodalApply() 方法

- [x] 将 `promptBuilder.call().content()` 改为 `streamToEmitter(dynamicContext, promptBuilder, "multimodal_response", request.getSessionId())`

---

## 任务 2: 验证编译

- [x] 运行 Maven 编译确认无错误

---

## 任务 3: 测试验证

### 子任务 3.1: 单元测试 - GeneralChatNode 流式输出

**测试文件**: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/chat/GeneralChatNodeStreamTest.java`

| 测试场景 | 测试步骤 | 预期结果 | 状态 |
|---------|---------|---------|------|
| 流式正常结束 | Mock `ChatClient`，返回 3 个 chunk | 所有 chunk 正确发送，SSE complete 事件发送 | pass |
| emitter 为空时降级 | 不设置 dynamicContext 的 emitter | 降级为同步调用 `call().content()` | pass |
| 流式输出中途异常 | Mock `ChatClient` 第二 chunk 时抛异常 | 异常被捕获，记录日志，流程继续 | pass |
| 空消息输入 | 输入空消息 | 正常处理，返回空响应或友好提示 | pass |

### 子任务 3.2: 单元测试 - AutoAgentStrategy 异常处理

**测试文件**: `ai-agent-study-app/src/test/java/denny/ai/agent/test/service/auto/AutoAgentStrategyTest.java`

| 测试场景 | 测试步骤 | 预期结果 | 状态 |
|---------|---------|---------|------|
| 节点链正常执行 | Mock 节点链返回成功 | `emitter.complete()` 被调用一次 | pass |
| 节点链抛异常 | Mock 节点链抛 `RuntimeException` | 错误消息发送，`emitter.complete()` 被调用，异常不外抛 | pass |
| emitter 重复 close | 两次调用 `safeComplete()` | 第二次调用捕获异常，记录 warn 日志，不抛异常 | pass |
| emitter 为空 | 传入 null emitter | `safeComplete()` 直接返回，无异常 | pass |

### 子任务 3.3: 集成测试 - SSE 流式输出

**测试方式**: 启动应用，通过 HTTP 客户端测试

| 测试场景 | 测试步骤 | 预期结果 | 状态 |
|---------|---------|---------|------|
| 文本对话流式输出 | 发送普通文本消息 | SSE 流式返回，可观察到逐块输出 | pass |
| 多模态对话流式输出 | 上传图片并发送消息 | 图片识别结果流式返回 | pending |
| 客户端中途断开 | 开始接收后断开连接 | 服务端日志记录断开，流程安全结束，无异常 | pass |
| 长对话不超时 | 发送需要较长响应的问题 | 响应完整返回，无超时中断 | pass |
| 并发多会话 | 同时发起 2+ 个会话 | 各会话独立，互不影响 | pass |
| emitter 为空场景 | 直接调用节点（无 HTTP 层） | 降级为同步输出，不报错 | pending |

---

## 任务 4: 真流式改造 (LLM 逐字实时展示)

**背景**: 任务 1 的 `streamToEmitter()` 使用 `collectList().block()` 导致伪流式，用户体验不佳。

**关联 Story**: `docs/trading-agent/2026-06-01-llm-true-streaming-story.md`

**文件**: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/chat/GeneralChatNode.java`

### 子任务 4.1: 改造 streamToEmitter() 为真流式

- [ ] 将 `collectList().block()` 改为 `subscribe()` 实时发送
  - `onNext`: 每收到一块立即 `sendSseResult()` 发送
  - `onError`: 发送错误事件，`latch.countDown()`
  - `onComplete`: 发送完成事件，`latch.countDown()`
- [ ] 保持 `CountDownLatch` 确保流程同步

### 子任务 4.2: 编写单元测试

- [ ] 创建 `GeneralChatNodeStreamTest.java`
- [ ] 测试流式正常结束
- [ ] 测试 emitter 为空降级
- [ ] 测试流式中途异常
- [ ] 测试客户端断开场景

### 子任务 4.3: 集成测试验证

- [ ] 启动应用，发送文本消息
- [ ] 观察 SSE 响应是否逐字实时展示

---

## 变更汇总

| 序号 | 文件 | 改动类型 | 描述 |
|------|------|----------|------|
| 1 | AutoAgentExecuteStrategy.java | 重构 | 统一异常处理和安全关闭 emitter |
| 2 | GeneralChatNode.java | 修改 | 添加流式输出能力 |
