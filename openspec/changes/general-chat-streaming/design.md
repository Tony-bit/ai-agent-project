## Context

当前 `GeneralChatNode` 使用同步调用 `chatClient.prompt().call().content()`，用户需要等待完整响应后才能看到结果，体验较差。项目中已有 SSE 基础设施（`AbstractExecuteSupport.sendSseResult()`），需要复用这一基础设施实现流式输出。

## Goals / Non-Goals

**Goals:**
- 将 `GeneralChatNode.doTextApply()` 改为流式输出
- 将 `GeneralChatNode.doMultimodalApply()` 改为流式输出
- 复用现有的 SSE 基础设施
- 保持向后兼容（emitter 为空时降级为同步调用）

**Non-Goals:**
- 不改造其他节点（Step1AnalyzerNode、Step2PrecisionExecutorNode 等）
- 不添加新的 SSE 事件类型（复用现有的 `type/subType` 格式）
- 不改变 LLM 调用结果的处理逻辑

## Decisions

### 方案：使用 Spring AI 的 stream() API

**选择理由：**
- Spring AI 原生支持 `prompt().stream().content()` 流式 API
- 与现有 `call().content()` 调用方式一致，改动最小
- 已有 SSE 基础设施可复用

**实现方式：**

1. 新增辅助方法 `streamToEmitter()`：
   - 接收 `ChatClient.ChatClientRequestSpec` 构建器
   - 使用 `Flux<String>` 订阅流式响应
   - 逐块调用 `sendSseResult()` 发送到前端
   - 返回完整响应文本供后续逻辑使用

2. 降级策略：
   - 如果 `emitter` 为空，降级为同步调用
   - 避免因 SSE 配置问题导致服务不可用

## Risks / Trade-offs

[Risk] 客户端断开连接时发送 SSE 可能抛异常
→ [Mitigation] 在 `sendSseResult()` 中捕获异常并记录日志，不影响主流程

[Risk] 流式输出过程中出现异常
→ [Mitigation] 在 `subscribe()` 的 error handler 中发送错误事件并调用 `latch.countDown()` 确保流程结束

## Emitter 生命周期管理

### 设计原则

**emitter 关闭时机**：emitter.close() 时，必须保证没有任何代码在运行，也不会有待输出的结果。

### 架构设计

```
AiAgentController (异步线程)
    └── AutoAgentStrategy.execute()
            └── executeHandler.apply()
                    └── GeneralChatNode.doTextApply()
                            └── streamToEmitter()  ← 同步阻塞等待流完成
                                    └── 逐块发送 SSE
                                    └── 流结束后返回完整响应
            └── emitter.complete()  ← 所有节点链返回后安全关闭
```

### 关键约束

1. `streamToEmitter()` 必须**同步阻塞等待流完成**后才返回
2. 流结束后才发送 `completeEvent`
3. `AutoAgentStrategy` 统一负责 emitter 的生命周期管理

### 异常处理方案

**问题场景**：
- 节点链执行中抛异常
- 流式输出中途失败
- emitter 可能已被 close 或正在被 close

**解决方案**：在 `AutoAgentStrategy` 中统一处理，并提供幂等的 `safeComplete()` 方法

```java
public void execute(ExecuteCommandEntity executeCommandEntity, ResponseBodyEmitter emitter) throws Exception {
    try {
        // 执行业务逻辑
        String result = executeHandler.apply(executeCommandEntity, dynamicContext);

        // 正常流程：完成后安全关闭
        safeComplete(emitter);

    } catch (Exception e) {
        log.error("节点执行异常", e);

        // 异常流程：发送错误消息后安全关闭
        safeComplete(emitter, "执行异常：" + e.getMessage());
    }
}

/**
 * 安全关闭 emitter
 * @param emitter SSE emitter
 * @param errorMessage 可选，错误消息，传入时发送后再关闭
 */
private void safeComplete(ResponseBodyEmitter emitter, String errorMessage) {
    if (emitter == null) {
        return;
    }
    try {
        if (errorMessage != null) {
            emitter.send(errorMessage);
        }
        emitter.complete();
    } catch (Exception e) {
        // emitter 可能已关闭，忽略
        log.warn("emitter关闭异常（可能已提前关闭）: {}", e.getMessage());
    }
}
```

**Controller 层简化**：

```java
// AiAgentController - 保持简洁，不处理异常
threadPoolExecutor.execute(() -> {
    autoAgentStrategy.execute(executeCommandEntity, emitter);
});
```

**好处**：
- Controller 不需要任何 try-catch
- `AutoAgentStrategy` 统一负责生命周期
- `safeComplete()` 封装了幂等关闭逻辑，多次调用不会报错

### 流式输出中的 emitter 提前关闭处理

**问题场景**：`streamToEmitter()` 执行过程中，客户端断开连接导致 `emitter.send()` 抛异常。

**处理方式**：
- `sendSseResult()` 已有异常捕获逻辑，异常被吞掉不影响主流程
- 流中断后，`streamToEmitter()` 仍然返回（使用 `collectList().block()` 等待剩余数据）
- 后续的 `safeComplete()` 调用会检测 emitter 状态，忽略已关闭的情况

## Open Questions

无
