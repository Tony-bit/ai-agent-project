## Why

当前 GeneralChatNode 使用同步调用 `chatClient.prompt().call().content()`，用户需要等待完整响应后才能看到结果，体验较差。需要改造为流式输出，让 AI 的思考过程和回复能够实时展示给用户。

## What Changes

- 将 `GeneralChatNode.doTextApply()` 中的同步调用改为流式输出
- 将 `GeneralChatNode.doMultimodalApply()` 中的同步调用改为流式输出
- 新增通用流式处理辅助方法 `streamToEmitter()`
- 复用现有的 SSE 基础设施（`sendSseResult()`）发送流式内容

## Capabilities

### New Capabilities

- `general-chat-streaming`: 通用对话节点流式输出能力，将 LLM 流式响应实时通过 SSE 推送给前端

### Modified Capabilities

- 无

## Impact

### 影响的代码

- `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/chat/GeneralChatNode.java`

### 依赖

- Spring WebFlux (reactor.core.publisher.Flux)
- Spring MVC (ResponseBodyEmitter)
- 现有的 SSE 基础设施保持不变
