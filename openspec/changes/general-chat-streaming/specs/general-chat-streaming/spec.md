## ADDED Requirements

### Requirement: 流式对话输出
GeneralChatNode SHALL 使用流式 API 调用 LLM，并将响应内容实时通过 SSE 推送给前端。

#### Scenario: 文本对话流式输出
- **WHEN** 用户发送文本消息，intent 为 GENERAL_CHAT
- **THEN** 系统使用 `chatClient.prompt().stream().content()` 获取流式响应
- **AND** 每一块响应内容通过 SSE 事件 `type=content, subType=general_chat_response` 发送
- **AND** 完成后发送 `type=complete` 事件

#### Scenario: 多模态对话流式输出
- **WHEN** 用户发送图片消息，intent 为 GENERAL_CHAT
- **THEN** 系统上传图片到 OSS 后，使用流式 API 调用多模态模型
- **AND** 响应内容实时通过 SSE 发送

#### Scenario: emitter 为空时降级
- **WHEN** dynamicContext 中 emitter 为 null
- **THEN** 系统降级为同步调用 `call().content()`
- **AND** 不发送任何 SSE 事件

#### Scenario: SSE 发送失败时优雅处理
- **WHEN** SSE 发送过程中客户端断开连接
- **THEN** 系统捕获异常并记录日志
- **AND** 不抛出异常，不影响后续流程
