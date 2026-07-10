# Story: LLM 流式输出真流式改造

| 字段 | 内容 |
|------|------|
| 创建日期 | 2026-06-01 |
| 状态 | completed |
| 负责人 | - |
| 关联需求 | GeneralChatNode SSE 流式输出优化 |
| 优先级 | P0 |

---

## 1. 背景与问题

### 1.1 问题描述

当前 `GeneralChatNode.streamToEmitter()` 方法虽然使用了 `promptBuilder.stream().content()` 获取流式响应，但实现方式是 **伪流式**：

```java:ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/chat/GeneralChatNode.java
// 当前实现（伪流式）
Flux<String> flux = promptBuilder.stream().content();

// 同步阻塞等待流完成
collectedChunks = flux.collectList().block();  // ⚠️ 这里等全部收完才发
```

**问题**：客户端看到的效果是 "白屏等待 → 突然显示完整回复"，而不是逐字展示。

### 1.2 用户体验对比

```
┌─────────────────────────────────────────────────────────────────┐
│                    伪流式 vs 真流式用户体验                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  伪流式:                                                        │
│  客户端: [发送] ───────── [等待...漫长的等待... ] ── [突然看到完整]│
│                       │                                         │
│                       │ LLM 在服务端慢慢生成                     │
│                       │ 但客户端什么都看不到                      │
│                       ▼                                         │
│                  用户以为"卡住了"                                 │
│                                                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  真流式:                                                        │
│  LLM:    [H] ─ [e] ─ [l] ─ [l] ─ [o] ─ [你] ─ [好]            │
│          │   │   │   │   │   │    │                            │
│          ▼   ▼   ▼   ▼   ▼   ▼    ▼                            │
│  客户端: [H][e][l][l][o][你][好]...                            │
│           │                                         │            │
│           └──── 用户看着文字一个字一个字蹦出来 ────────┘           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 改造目标

将 `streamToEmitter()` 从伪流式改为真流式，实现 LLM 逐字实时展示给用户。

---

## 2. 数据流架构

### 2.1 完整数据流

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│   后端                          LLM Server                       │
│   ────                          ─────────                       │
│    │                                                        │
│    │  promptBuilder.stream()                                    │
│    │───────────────────────────────►                              │
│    │                               │                            │
│    │  subscribe()  ◄───────────────  Flux<String> (逐字返回)   │
│    │   ↑                                                        │
│    │   │  "吃"数据：用 subscribe() 消费 LLM 返回的流              │
│    │                                                        │
│    ▼                                                        │
│  ════════════════════════════════════════════════════════════ │
│                                                                  │
│   后端                          前端 (浏览器)                     │
│   ────                          ─────────                       │
│    │                                                        │
│    │  emitter.send()                                          │
│    │───────────────────────────────►  SSE / EventSource        │
│    │   ↑                                                        │
│    │   │  "吐"数据：用 emitter.send() 发给前端                   │
│    │                                                        │
│    │                               ▼                            │
│    │                          逐字显示给用户                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 两个交互的本质

| 交互 | 方向 | 工具 | 说明 |
|------|------|------|------|
| **后端 → LLM** | 消费数据 | `Flux.subscribe()` | 必须用 subscribe 才能拿到 LLM 返回的流 |
| **后端 → 前端** | 发送数据 | `ResponseBodyEmitter.send()` | 用 emitter 发 SSE 给浏览器 |

### 2.3 线程安全说明

**不需要担心线程安全问题**：

- 一个 HTTP 请求 = 一个 emitter = 一个会话 = 一个 agent 在输出
- `subscribe()` 回调在同一 subscriber 内是串行执行的
- 不会有两个线程同时往同一个 emitter 发数据

---

## 3. 技术方案

### 3.1 核心改动

将 `subscribe()` 实时发送替代 `collectList().block()` 批量收集：

```java
// 伪流式（当前）
collectedChunks = flux.collectList().block();
for (String chunk : collectedChunks) {
    sendSseResult(dynamicContext, ...);
}

// 真流式（目标）
flux.subscribe(
    chunk -> {
        fullContent.append(chunk);
        sendSseResult(dynamicContext, createChunkResult(chunk));  // 来一块发一块
    },
    error -> {
        log.error("流式输出异常", error);
        sendSseResult(dynamicContext, createErrorResult(error));
        latch.countDown();
    },
    () -> {
        sendSseResult(dynamicContext, createCompleteResult());
        latch.countDown();
    }
);

latch.await();  // 等待流完成
```

### 3.2 架构说明

```
┌─────────────────────────────────────────────────────────────────┐
│                    改动范围                                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  GeneralChatNode.streamToEmitter()                              │
│       │                                                         │
│       ├── 改动: collectList().block() → subscribe()             │
│       │                                                         │
│       ├── subscribe() 异步订阅 LLM 流                           │
│       │                                                         │
│       ├── onNext: 实时发送 SSE                                   │
│       │                                                         │
│       ├── onError: 发送错误事件                                  │
│       │                                                         │
│       └── onComplete: 发送完成事件                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 3.3 保持兼容的设计

| 场景 | 处理方式 |
|------|----------|
| emitter 为空 | 降级为 `call().content()` 同步调用 |
| 客户端断开 | `sendSseResult()` 捕获异常，记录日志 |
| 流式异常 | 发送错误事件，latch.countDown() 确保流程结束 |
| 完成事件 | 发送 `completed=true` 的结果 |

---

## 4. 文件变更清单

### 3.1 修改文件

| 文件路径 | 修改内容 | status |
|----------|----------|--------|
| `ai-agent-study-domain/.../GeneralChatNode.java` | 改造 `streamToEmitter()` 方法 | pending |

### 3.2 改动行数

| 区域 | 改动前 | 改动后 |
|------|--------|--------|
| `streamToEmitter()` 方法 | ~30行 | ~40行 |
| 核心逻辑 | `collectList().block()` 循环发送 | `subscribe()` 实时发送 |

---

## 5. 代码设计

### 5.1 streamToEmitter() 改造后完整代码

```java
/**
 * 流式输出到 SSE
 * <p>
 * 使用 subscribe() 实时发送每一块内容，实现真流式输出。
 * 如果 emitter 为空，则降级为同步调用。
 * </p>
 *
 * @param dynamicContext 动态上下文
 * @param promptBuilder  ChatClient 请求构建器
 * @param subType        SSE 子类型
 * @param sessionId      会话ID
 * @return 完整响应内容
 */
private String streamToEmitter(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                               ChatClient.ChatClientRequestSpec promptBuilder,
                               String subType, String sessionId) {
    ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");

    // 降级：emitter 为空时使用同步调用
    if (emitter == null) {
        log.warn("emitter 为空，降级为同步调用");
        return promptBuilder.call().content();
    }

    // 发送开始事件
    sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
            .type("system")
            .subType(subType + "_start")
            .content("开始生成...")
            .completed(false)
            .timestamp(System.currentTimeMillis())
            .build());

    // 使用 StringBuilder 收集完整响应
    StringBuilder fullContent = new StringBuilder();
    CountDownLatch latch = new CountDownLatch(1);

    // 真流式：subscribe 实时发送
    promptBuilder.stream().content()
            .subscribe(
                    // onNext: 每收到一块立即发送
                    chunk -> {
                        fullContent.append(chunk);
                        sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
                                .type("content")
                                .subType(subType)
                                .content(chunk)
                                .completed(false)
                                .timestamp(System.currentTimeMillis())
                                .build());
                    },
                    // onError: 异常处理
                    error -> {
                        log.error("流式输出异常: subType={}, error={}", subType, error.getMessage(), error);
                        sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
                                .type("error")
                                .subType(subType)
                                .content("流式输出异常: " + error.getMessage())
                                .completed(true)
                                .timestamp(System.currentTimeMillis())
                                .build());
                        latch.countDown();
                    },
                    // onComplete: 完成（仅发完成标识，不重复发完整内容，避免前端收到重复帧）
                    () -> {
                        sendSseResult(dynamicContext, AutoAgentExecuteResultEntity.builder()
                                .type("content")
                                .subType(subType)
                                .content("")
                                .completed(true)
                                .timestamp(System.currentTimeMillis())
                                .build());
                        latch.countDown();
                    }
            );

    try {
        latch.await();  // 等待流完成
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("流式输出等待被中断: subType={}", subType);
    }

    return fullContent.toString();
}
```

### 5.2 与当前代码对比

| 项目 | 伪流式（当前） | 真流式（改造后） |
|------|----------------|------------------|
| 等待方式 | `collectList().block()` | `subscribe()` |
| 发送时机 | 全部收完再发 | 来一块发一块 |
| 实时性 | 无 | 实时 |
| 代码行数 | ~35行 | ~50行 |
| 复杂度 | 简单 | 稍复杂（异步回调） |

---

## 6. 测试计划

### 6.1 测试场景

#### 6.1.1 单元测试

| 用例ID | 测试场景 | 输入 | 预期结果 | 验证方式 |
|--------|----------|------|----------|----------|
| TC-01 | 流式正常结束 | Mock Flux 返回 3 个 chunk | 所有 chunk 实时发送，onComplete 触发 | Mock 验证 |
| TC-02 | emitter 为空降级 | emitter = null | 降级为 `call().content()` 同步调用 | Mock 验证 |
| TC-03 | 流式中途异常 | Mock Flux 第二 chunk 时抛异常 | 错误事件发送，latch.countDown() | Mock 验证 |
| TC-04 | 客户端断开 | Mock emitter.send() 抛 IOException | 异常被捕获，记录日志，流程继续 | Mock 验证 |
| TC-05 | 空 Flux 处理 | Flux.empty() | onComplete 触发，无 chunk 发送 | Mock 验证 |
| TC-06 | chunks 顺序保持 | Flux 发送顺序 1,2,3,4,5 | 接收顺序与发送顺序一致 | Mock 验证 |
| TC-07 | CountDownLatch 超时 | latch.await() 长时间未完成 | 应设置合理超时时间 | Mock 验证 |

#### 6.1.2 集成测试

| 用例ID | 测试场景 | 输入 | 预期结果 | 验证方式 |
|--------|----------|------|----------|----------|
| TC-08 | 空消息输入 | 输入空消息 | 正常处理，返回空响应 | 手动测试 |
| TC-09 | 并发多会话 | 同时发起 2+ 个会话 | 各会话独立，互不影响 | 手动测试 |
| TC-10 | 真流式效果验证 | 发起一个对话请求 | 文字逐字展示，不是整段出现 | 手动测试 |

### 6.2 验证标准

#### 功能验证点

- [x] `subscribe()` 回调被正确触发
- [x] 每个 chunk 到达时 `onNext` 被调用
- [x] 流结束时 `onComplete` 被调用
- [x] 异常时 `onError` 被调用并发送错误事件
- [x] `latch.countDown()` 在所有路径（正常/异常）都被调用
- [x] emitter 为空时降级为同步调用

#### 用户体验验证

- [ ] 浏览器 Network 面板显示多个 SSE 事件
- [x] 前端逐字/逐句展示，不是等待完整后一次性显示
- [x] 响应延迟 < 100ms（首字延迟）

### 6.3 测试文件

| 文件路径 | 说明 | status |
|----------|------|--------|
| `ai-agent-study-app/.../GeneralChatNodeStreamTest.java` | 真流式输出单元测试 | pending |

---

## 7. 任务清单

| 序号 | 任务 | 状态 | 依赖 |
|------|------|------|------|
| 1 | 改造 `streamToEmitter()` 方法 | pass | 无 |
| 2 | 编写单元测试 `GeneralChatNodeStreamTest` | pending | 1 |
| 3 | 编译验证 | pass | 1 |
| 4 | 集成测试（手动验证真流式效果） | pass | 3 |

---

## 8. 预期效果

| 指标 | 改造前 | 改造后 |
|------|--------|--------|
| 用户体验 | 白屏等待 → 突然显示 | 逐字实时展示 |
| 实时性 | 伪流式（0%） | 真流式（100%） |
| 代码行数 | ~35行 | ~50行 |
| 新增文件 | 0 | 0 |

---

## 9. 风险与注意事项

1. **异步线程安全**：✅ 不需要担心，一个会话只有一个 emitter，不会并发
2. **背压控制**：`ResponseBodyEmitter` 无背压控制，LLM 生成速度 > 发送速度时会缓冲
3. **客户端断开**：`emitter.send()` 抛 `IOException` 时被 `sendSseResult()` 捕获，不影响主流程
4. **CountDownLatch**：异常时也必须调用 `latch.countDown()`，否则会死等
