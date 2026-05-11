# 多模态对话支持功能测试用例手册

> **文档版本：** v1.0
> **编写日期：** 2026-05-11
> **编写人：** Denny
> **功能模块：** 多模态对话支持 (Multimodal Chat Support)
> **测试范围：** 仅覆盖本次代码变更部分，已有逻辑无需重复测试

---

## 1. 测试概述

### 1.1 测试背景

本次功能开发在现有对话架构上增加图片输入支持，用户上传图片到 OSS 后，通过 Qwen VL 模型解析图片内容，结合用户问题返回多模态理解结果。

### 1.2 代码变更范围

| # | 文件 | 变更类型 | 测试重点 |
|---|------|---------|---------|
| 1 | `GeneralChatNode.java` | 修改 | inputType=1 分支路由、OSSUploadService 注入、多模态消息构建 |
| 2 | `OSSUploadService.java` | 修改 | 文件安全校验（大小、类型、文件名） |
| 3 | `IntentRoutingNode.java` | 修改 | inputType=1 优先路由逻辑 |

### 1.3 测试策略

- **单元测试**：使用 Mockito Mock 所有外部依赖（ChatClient、OSSUploadService 等）
- **Mock 原则**：只 Mock 中间件和外部服务，不 Mock 同层代码
- **边界测试**：重点覆盖边界条件和异常场景

---

## 2. OSSUploadService 测试用例

> **测试文件：** `OSSUploadServiceTest.java`（新建）
> **被测方法：** `upload(MultipartFile file)`

### 2.1 文件安全校验测试

#### TC-OSS-001: 正常图片上传

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-001 |
| **用例名称** | 正常图片上传 |
| **前置条件** | OSS 配置正确，网络可用 |
| **输入** | `MockMultipartFile(name="test.jpg", contentType="image/jpeg", content="valid jpeg bytes")` |
| **预期输出** | 返回 OSS URL（非空字符串，包含 `https://`） |
| **测试要点** | 1. 返回值非空<br>2. URL 格式正确 |
| **优先级** | P0 |

#### TC-OSS-002: 支持的图片类型 - JPEG

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-002 |
| **用例名称** | 支持的图片类型_JPEG |
| **输入** | `contentType="image/jpeg"` |
| **预期输出** | 返回 OSS URL |
| **测试要点** | image/jpeg 在白名单中，应通过校验 |
| **优先级** | P0 |

#### TC-OSS-003: 支持的图片类型 - PNG

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-003 |
| **用例名称** | 支持的图片类型_PNG |
| **输入** | `contentType="image/png"` |
| **预期输出** | 返回 OSS URL |
| **测试要点** | image/png 在白名单中，应通过校验 |
| **优先级** | P0 |

#### TC-OSS-004: 支持的图片类型 - GIF

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-004 |
| **用例名称** | 支持的图片类型_GIF |
| **输入** | `contentType="image/gif"` |
| **预期输出** | 返回 OSS URL |
| **测试要点** | image/gif 在白名单中，应通过校验 |
| **优先级** | P1 |

#### TC-OSS-005: 支持的图片类型 - WebP

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-005 |
| **用例名称** | 支持的图片类型_WebP |
| **输入** | `contentType="image/webp"` |
| **预期输出** | 返回 OSS URL |
| **测试要点** | image/webp 在白名单中，应通过校验 |
| **优先级** | P1 |

#### TC-OSS-006: 不支持的类型 - HTML

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-006 |
| **用例名称** | 不支持的类型_HTML文件 |
| **输入** | `contentType="text/html"` |
| **预期输出** | 抛出 `IllegalArgumentException("不支持的文件类型，仅支持 jpeg/png/gif/webp")` |
| **测试要点** | 非图片类型应被拒绝 |
| **优先级** | P0 |

#### TC-OSS-007: 不支持的类型 - EXE

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-007 |
| **用例名称** | 不支持的类型_EXE可执行文件 |
| **输入** | `contentType="application/x-msdownload"` |
| **预期输出** | 抛出 `IllegalArgumentException("不支持的文件类型，仅支持 jpeg/png/gif/webp")` |
| **测试要点** | 危险文件类型应被拒绝 |
| **优先级** | P0 |

#### TC-OSS-008: 不支持的类型 - JSP

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-008 |
| **用例名称** | 不支持的类型_JSP脚本 |
| **输入** | `contentType="application/x-jsp"` |
| **预期输出** | 抛出 `IllegalArgumentException("不支持的文件类型，仅支持 jpeg/png/gif/webp")` |
| **测试要点** | 脚本文件类型应被拒绝 |
| **优先级** | P0 |

#### TC-OSS-009: 不支持的类型 - 大小写变体

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-009 |
| **用例名称** | 不支持的类型_大小写变体 |
| **输入** | `contentType="IMAGE/JPEG"`（大写） |
| **预期输出** | 抛出 `IllegalArgumentException` |
| **测试要点** | 校验应区分大小写 |
| **优先级** | P2 |

#### TC-OSS-010: 文件大小超限 - 刚好 10MB

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-010 |
| **用例名称** | 文件大小超限_刚好10MB |
| **输入** | `file.getSize() = 10 * 1024 * 1024`（精确 10MB） |
| **预期输出** | 抛出 `IllegalArgumentException("文件大小超过限制，最大支持 10MB")` |
| **测试要点** | 10MB 边界值测试 |
| **优先级** | P1 |

#### TC-OSS-011: 文件大小超限 - 超过 10MB

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-011 |
| **用例名称** | 文件大小超限_超过10MB |
| **输入** | `file.getSize() = 10 * 1024 * 1024 + 1`（超 1 字节） |
| **预期输出** | 抛出 `IllegalArgumentException("文件大小超过限制，最大支持 10MB")` |
| **测试要点** | 超过限制即拒绝 |
| **优先级** | P0 |

#### TC-OSS-012: 文件大小超限 - GB级文件

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-012 |
| **用例名称** | 文件大小超限_GB级文件 |
| **输入** | `file.getSize() = 1024 * 1024 * 1024`（1GB） |
| **预期输出** | 抛出 `IllegalArgumentException("文件大小超过限制，最大支持 10MB")` |
| **测试要点** | 防资源耗尽攻击 |
| **优先级** | P0 |

#### TC-OSS-013: 文件大小临界 - 刚好 10MB 以下

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-013 |
| **用例名称** | 文件大小临界_刚好10MB以下 |
| **输入** | `file.getSize() = 10 * 1024 * 1024 - 1`（差 1 字节到 10MB） |
| **预期输出** | 返回 OSS URL |
| **测试要点** | 边界内应通过 |
| **优先级** | P1 |

#### TC-OSS-014: 空文件上传

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-014 |
| **用例名称** | 空文件上传 |
| **输入** | `file.isEmpty() = true` |
| **预期输出** | 抛出 `IllegalArgumentException("上传文件为空")` |
| **测试要点** | 空文件应被拒绝 |
| **优先级** | P0 |

#### TC-OSS-015: 文件名为空的处理

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-015 |
| **用例名称** | 文件名为空的处理 |
| **输入** | `file.getOriginalFilename() = null` |
| **预期输出** | 使用 UUID 生成文件名，不抛出异常 |
| **测试要点** | null 文件名不应导致 NPE |
| **优先级** | P1 |

#### TC-OSS-016: 文件名无扩展名

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-016 |
| **用例名称** | 文件名无扩展名 |
| **输入** | `file.getOriginalFilename() = "test"`（无 `.`） |
| **预期输出** | 使用 UUID 生成文件名，不带扩展名 |
| **测试要点** | 无扩展名时不应异常 |
| **优先级** | P2 |

#### TC-OSS-017: 文件名路径穿越攻击

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-017 |
| **用例名称** | 文件名路径穿越攻击 |
| **输入** | `file.getOriginalFilename() = "../../../etc/passwd"` |
| **预期输出** | 使用 UUID 生成文件名，不使用原始文件名 |
| **测试要点** | 防止路径穿越漏洞 |
| **优先级** | P0 |

#### TC-OSS-018: UUID 文件名生成验证

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-018 |
| **用例名称** | UUID文件名生成验证 |
| **输入** | 任意合法图片文件 |
| **预期输出** | OSS Key 格式为 `{UUID}.{extension}`，不含原始文件名 |
| **测试要点** | 1. UUID 格式正确<br>2. 不包含原始文件名 |
| **优先级** | P1 |

### 2.2 文件上传成功测试

#### TC-OSS-019: OSS 上传异常降级

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-OSS-019 |
| **用例名称** | OSS上传异常降级 |
| **输入** | OSS 服务不可用（抛出 AmazonS3Exception） |
| **预期输出** | 捕获异常，返回 `null` |
| **测试要点** | 上传失败时不应抛出未处理异常 |
| **优先级** | P1 |

---

## 3. IntentRoutingNode 测试用例

> **测试文件：** `IntentRoutingNodeTest.java`（扩展现有）
> **被测方法：** `doApply()`, `get()`

### 3.1 inputType=1 优先路由测试

#### TC-IRN-001: inputType=1 时直接路由到 GeneralChatNode

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-IRN-001 |
| **用例名称** | inputType=1时直接路由到GeneralChatNode |
| **前置条件** | IntentRoutingNode 已注入 GeneralChatNode |
| **输入** | `request.inputType = 1, request.file = mockFile` |
| **预期输出** | `get()` 返回 `generalChatNode` |
| **测试要点** | 1. 不执行意图识别 LLM 调用<br>2. 直接路由到 GeneralChatNode |
| **优先级** | P0 |
| **验证方式** | Mock `intentRoutingService.route()` 不应被调用 |

#### TC-IRN-002: inputType=1 时设置 recognizedIntent 为 GENERAL_CHAT

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-IRN-002 |
| **用例名称** | inputType=1时设置recognizedIntent为GENERAL_CHAT |
| **输入** | `request.inputType = 1, request.file = mockFile` |
| **预期输出** | `dynamicContext.getValue("recognizedIntent") == GENERAL_CHAT` |
| **测试要点** | recognizedIntent 被正确设置为 GENERAL_CHAT |
| **优先级** | P0 |

#### TC-IRN-003: inputType=0 时正常执行意图识别

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-IRN-003 |
| **用例名称** | inputType=0时正常执行意图识别 |
| **输入** | `request.inputType = 0, request.message = "分析股票"` |
| **预期输出** | `intentRoutingService.route()` 被调用 |
| **测试要点** | 1. 正常执行意图识别<br>2. 根据识别结果路由 |
| **优先级** | P0 |
| **验证方式** | Verify `intentRoutingService.route()` 被调用 1 次 |

#### TC-IRN-004: inputType=null 时正常执行意图识别

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-IRN-004 |
| **用例名称** | inputType为null时正常执行意图识别 |
| **输入** | `request.inputType = null` |
| **预期输出** | `intentRoutingService.route()` 被调用 |
| **测试要点** | null 值按文本输入处理 |
| **优先级** | P1 |

#### TC-IRN-005: inputType=1 但 file=null 时

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-IRN-005 |
| **用例名称** | inputType=1但file为null时 |
| **输入** | `request.inputType = 1, request.file = null` |
| **预期输出** | 正常执行意图识别（不满足多模态条件） |
| **测试要点** | 必须同时满足 inputType=1 和 file!=null 才走多模态 |
| **优先级** | P0 |

#### TC-IRN-006: inputType=1 但 file 为空 MultipartFile 时

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-IRN-006 |
| **用例名称** | inputType=1但file为空时 |
| **输入** | `request.inputType = 1, request.file = emptyFile` |
| **预期输出** | OSSUploadService.upload() 抛出 IllegalArgumentException |
| **测试要点** | 空文件在 GeneralChatNode 中被 OSSUploadService 拒绝 |
| **优先级** | P1 |

---

## 4. GeneralChatNode 测试用例

> **测试文件：** `GeneralChatNodeTest.java`（扩展现有）
> **被测方法：** `doApply()`

### 4.1 分支路由测试

#### TC-GCN-001: inputType=0 走文本分支

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-001 |
| **用例名称** | inputType=0走文本分支 |
| **前置条件** | GeneralChatNode 已注入 OSSUploadService |
| **输入** | `request.inputType = 0, request.message = "你好"` |
| **预期输出** | 调用 `doTextApply()`，ChatClient 使用 `.user(text)` |
| **测试要点** | 1. OSSUploadService.upload() 不被调用<br>2. 使用 "default" ChatClient |
| **优先级** | P0 |
| **验证方式** | Verify `ossUploadService.upload()` 从不被调用 |

#### TC-GCN-002: inputType=1 且有文件走多模态分支

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-002 |
| **用例名称** | inputType=1且有文件走多模态分支 |
| **输入** | `request.inputType = 1, request.file = mockFile` |
| **预期输出** | 调用 `doMultimodalApply()` |
| **测试要点** | 1. OSSUploadService.upload() 被调用 1 次<br>2. ChatClient 使用 `.user(u -> u.text().image())` |
| **优先级** | P0 |
| **验证方式** | 1. Verify `ossUploadService.upload(mockFile)` 被调用<br>2. 验证消息构建使用多模态 API |

#### TC-GCN-003: inputType=null 走文本分支

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-003 |
| **用例名称** | inputType为null走文本分支 |
| **输入** | `request.inputType = null` |
| **预期输出** | 调用 `doTextApply()` |
| **测试要点** | null 值按文本输入处理 |
| **优先级** | P1 |

#### TC-GCN-004: inputType=1 但 file=null 走文本分支

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-004 |
| **用例名称** | inputType=1但file为null走文本分支 |
| **输入** | `request.inputType = 1, request.file = null` |
| **预期输出** | 调用 `doTextApply()` |
| **测试要点** | 必须同时满足 inputType=1 和 file!=null |
| **优先级** | P1 |

### 4.2 文本分支测试（doTextApply）

#### TC-GCN-005: 文本分支 - 正常对话

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-005 |
| **用例名称** | 文本分支_正常对话 |
| **输入** | `request.message = "你好，请介绍一下北京", inputType = 0` |
| **预期输出** | 返回 AI 回复内容 |
| **测试要点** | 1. ChatClient 被调用<br>2. 使用 "default" clientId<br>3. 返回非空回复 |
| **优先级** | P0 |
| **Mock 依赖** | ChatClient, getChatClientByClientId() |

#### TC-GCN-006: 文本分支 - retrieveSize 动态配置

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-006 |
| **用例名称** | 文本分支_retrieveSize动态配置 |
| **输入** | `inputType = 0`，`dynamicContext` 中配置 retrieveSize |
| **预期输出** | ChatClient.advisors() 使用配置的 retrieveSize 值 |
| **测试要点** | retrieveSize 从 dynamicContext 动态获取 |
| **优先级** | P1 |
| **验证方式** | 捕获 ChatClient prompt 参数验证 |

#### TC-GCN-007: 文本分支 - SSE 事件发送

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-007 |
| **用例名称** | 文本分支_SSE事件发送 |
| **输入** | `inputType = 0` |
| **预期输出** | 发送 SSE 事件：`general_chat_start` → `general_chat_response` → `complete` |
| **测试要点** | SSE 事件按正确顺序发送 |
| **优先级** | P1 |

#### TC-GCN-008: 文本分支 - dynamicContext 设置响应

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-008 |
| **用例名称** | 文本分支_dynamicContext设置响应 |
| **输入** | `inputType = 0` |
| **预期输出** | `dynamicContext.getValue("generalChatResponse") == AI回复` |
| **测试要点** | 响应内容被存储到 dynamicContext |
| **优先级** | P1 |

### 4.3 多模态分支测试（doMultimodalApply）

#### TC-GCN-009: 多模态分支 - 正常图片识别

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-009 |
| **用例名称** | 多模态分支_正常图片识别 |
| **输入** | `inputType = 1, file = mockImage, message = "这张图片里有什么？"` |
| **预期输出** | 返回图片描述内容 |
| **测试要点** | 1. OSSUploadService.upload() 被调用<br>2. 使用 "multimodal" 或 flowConfig clientId<br>3. 返回非空回复 |
| **优先级** | P0 |
| **Mock 依赖** | OSSUploadService, ChatClient |

#### TC-GCN-010: 多模态分支 - clientId 优先从 flowConfig 获取

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-010 |
| **用例名称** | 多模态分支_clientId优先从flowConfig获取 |
| **前置条件** | `dynamicContext.aiAgentClientFlowConfigVOMap` 包含 "multimodal" key |
| **输入** | `inputType = 1, file = mockImage` |
| **预期输出** | 使用 `flowConfigMap.get("multimodal").getClientId()` 获取的 clientId |
| **测试要点** | flowConfig 优先于硬编码 |
| **优先级** | P0 |
| **验证方式** | Mock `dynamicContext.getAiAgentClientFlowConfigVOMap()` 返回配置 |

#### TC-GCN-011: 多模态分支 - 无 flowConfig 时使用硬编码

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-011 |
| **用例名称** | 多模态分支_无flowConfig时使用硬编码 |
| **前置条件** | `dynamicContext.aiAgentClientFlowConfigVOMap` 为 null 或不包含 "multimodal" |
| **输入** | `inputType = 1, file = mockImage` |
| **预期输出** | 使用硬编码 "multimodal" 作为 clientId |
| **测试要点** | 降级到硬编码值 |
| **优先级** | P0 |
| **验证方式** | 调用 `getChatClientByClientId("multimodal", 0)` |

#### TC-GCN-012: 多模态分支 - message 为 null 时使用默认提示

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-012 |
| **用例名称** | 多模态分支_message为null时使用默认提示 |
| **输入** | `inputType = 1, file = mockImage, message = null` |
| **预期输出** | 使用默认消息 "请描述这张图片的内容" |
| **测试要点** | null message 不导致 NPE |
| **优先级** | P1 |

#### TC-GCN-013: 多模态分支 - message 为空字符串

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-013 |
| **用例名称** | 多模态分支_message为空字符串 |
| **输入** | `inputType = 1, file = mockImage, message = ""` |
| **预期输出** | 使用默认消息 "请描述这张图片的内容" |
| **测试要点** | 空字符串按 null 处理 |
| **优先级** | P2 |

#### TC-GCN-014: 多模态分支 - OSS 上传失败抛出异常

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-014 |
| **用例名称** | 多模态分支_OSS上传失败抛出异常 |
| **输入** | `inputType = 1, file = mockImage`，OSSUploadService.upload() 返回 null |
| **预期输出** | 抛出 `RuntimeException("图片上传 OSS 失败")` |
| **测试要点** | OSS 上传失败时异常信息明确 |
| **优先级** | P0 |
| **验证方式** | AssertThrows RuntimeException |

#### TC-GCN-015: 多模态分支 - OSS 上传返回空字符串

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-015 |
| **用例名称** | 多模态分支_OSS上传返回空字符串 |
| **输入** | `inputType = 1, file = mockImage`，OSSUploadService.upload() 返回 "" |
| **预期输出** | 抛出 `RuntimeException("图片上传 OSS 失败")` |
| **测试要点** | 空字符串按上传失败处理 |
| **优先级** | P0 |

#### TC-GCN-016: 多模态分支 - retrieveSize 禁用 Memory

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-016 |
| **用例名称** | 多模态分支_retrieveSize禁用Memory |
| **输入** | `inputType = 1, file = mockImage` |
| **预期输出** | `CHAT_MEMORY_RETRIEVE_SIZE_KEY = 0` |
| **测试要点** | 多模态场景禁用 ChatMemory |
| **优先级** | P1 |
| **验证方式** | 捕获 ChatClient prompt advisors 参数验证 |

#### TC-GCN-017: 多模态分支 - SSE 事件发送

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-017 |
| **用例名称** | 多模态分支_SSE事件发送 |
| **输入** | `inputType = 1, file = mockImage` |
| **预期输出** | 发送 SSE 事件：`multimodal_start` → `multimodal_response` → `complete` |
| **测试要点** | SSE subType 为 `multimodal_start` 和 `multimodal_response` |
| **优先级** | P1 |

#### TC-GCN-018: 多模态分支 - dynamicContext 设置响应

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-018 |
| **用例名称** | 多模态分支_dynamicContext设置响应 |
| **输入** | `inputType = 1, file = mockImage` |
| **预期输出** | `dynamicContext.getValue("generalChatResponse") == AI回复` |
| **测试要点** | 响应内容被存储到 dynamicContext（与文本分支一致） |
| **优先级** | P1 |

#### TC-GCN-019: 多模态分支 - 日志记录完整信息

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-GCN-019 |
| **用例名称** | 多模态分支_日志记录完整信息 |
| **输入** | `inputType = 1, file = mockImage` |
| **预期输出** | 日志包含 `ossUrl` 和 `responseLength` |
| **测试要点** | 便于问题排查 |
| **优先级** | P2 |

---

## 5. 集成测试用例

> **测试文件：** `MultimodalIntegrationTest.java`（新建）
> **测试说明：** 端到端测试，验证完整流程

### 5.1 完整流程测试

#### TC-INT-001: 文本对话完整流程

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-INT-001 |
| **用例名称** | 文本对话完整流程 |
| **场景** | 用户发送文本消息，AI 返回文本回复 |
| **输入** | `message = "你好"`, `inputType = 0` |
| **预期输出** | 完整的 SSE 事件流，最终返回 AI 回复 |
| **测试步骤** | 1. 调用 RootNode.execute()<br>2. 验证意图识别<br>3. 验证 GeneralChatNode 执行<br>4. 验证返回结果 |
| **优先级** | P0 |

#### TC-INT-002: 图片对话完整流程

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-INT-002 |
| **用例名称** | 图片对话完整流程 |
| **场景** | 用户发送图片和文本问题，AI 返回图片理解结果 |
| **输入** | `message = "这张图片里有什么？"`, `inputType = 1`, `file = mockImage` |
| **预期输出** | 完整的 SSE 事件流，最终返回图片描述 |
| **测试步骤** | 1. 调用 RootNode.execute()<br>2. 验证 IntentRoutingNode 识别 inputType=1<br>3. 验证 GeneralChatNode 多模态分支执行<br>4. 验证 OSS 上传和 AI 调用<br>5. 验证返回结果 |
| **优先级** | P0 |

#### TC-INT-003: 恶意文件类型完整流程

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-INT-003 |
| **用例名称** | 恶意文件类型完整流程 |
| **场景** | 用户上传恶意文件（.exe），系统拒绝 |
| **输入** | `message = "分析这个文件"`, `inputType = 1`, `file = exeFile(contentType="application/x-msdownload")` |
| **预期输出** | 抛出 `IllegalArgumentException("不支持的文件类型，仅支持 jpeg/png/gif/webp")` |
| **测试步骤** | 1. 调用 RootNode.execute()<br>2. 验证 OSSUploadService.upload() 抛出异常 |
| **优先级** | P0 |

#### TC-INT-004: 超大文件完整流程

| 字段 | 内容 |
|------|------|
| **用例ID** | TC-INT-004 |
| **用例名称** | 超大文件完整流程 |
| **场景** | 用户上传超大文件（>10MB），系统拒绝 |
| **输入** | `message = "分析这个文件"`, `inputType = 1`, `file = largeFile(size=15MB)` |
| **预期输出** | 抛出 `IllegalArgumentException("文件大小超过限制，最大支持 10MB")` |
| **测试步骤** | 1. 调用 RootNode.execute()<br>2. 验证 OSSUploadService.upload() 抛出异常 |
| **优先级** | P0 |

---

## 6. 测试数据规格

### 6.1 Mock 图片文件规格

```java
// 正常 JPEG 图片
MockMultipartFile validJpeg = new MockMultipartFile(
    "file",
    "test.jpg",
    "image/jpeg",
    "fake jpeg content".getBytes()
);

// 正常 PNG 图片
MockMultipartFile validPng = new MockMultipartFile(
    "file",
    "test.png",
    "image/png",
    "fake png content".getBytes()
);

// 空文件
MockMultipartFile emptyFile = new MockMultipartFile(
    "file",
    "empty.jpg",
    "image/jpeg",
    new byte[0]
);

// 危险文件类型
MockMultipartFile exeFile = new MockMultipartFile(
    "file",
    "malware.exe",
    "application/x-msdownload",
    "fake exe content".getBytes()
);

// 路径穿越文件名
MockMultipartFile pathTraversalFile = new MockMultipartFile(
    "file",
    "../../../etc/passwd",
    "image/jpeg",
    "fake content".getBytes()
);
```

### 6.2 边界值规格

| 参数 | 边界值 | 说明 |
|------|--------|------|
| 文件大小 | 0 bytes | 空文件 |
| 文件大小 | 10MB - 1 byte | 临界合法值 |
| 文件大小 | 10MB | 临界非法值 |
| 文件大小 | 10MB + 1 byte | 超出限制 |
| 文件大小 | 1GB | 防资源耗尽测试 |
| inputType | null | 未设置 |
| inputType | 0 | 文本输入 |
| inputType | 1 | 图片输入 |
| message | null | 未设置 |
| message | "" | 空字符串 |

---

## 7. 测试执行计划

### 7.1 执行顺序

```
Phase 1: 单元测试（独立执行，无外部依赖）
├── Step 1: OSSUploadServiceTest（文件安全校验）
├── Step 2: IntentRoutingNodeTest（路由逻辑）
└── Step 3: GeneralChatNodeTest（分支逻辑）

Phase 2: 集成测试（端到端验证）
└── Step 4: MultimodalIntegrationTest
```

### 7.2 测试通过标准

- 所有 P0 测试用例必须通过
- P1 测试用例通过率 ≥ 90%
- P2 测试用例通过率 ≥ 70%

---

## 8. 测试覆盖率目标

| 模块 | 覆盖率目标 |
|------|-----------|
| OSSUploadService.upload() | 100% |
| IntentRoutingNode.doApply() | 100% |
| IntentRoutingNode.get() | 100% |
| GeneralChatNode.doApply() | 100% |
| doMultimodalApply() | 100% |
| doTextApply() | 100% |

---

## 9. 附录

### 9.1 异常类型对照表

| 场景 | 异常类型 | 异常消息 |
|------|---------|---------|
| 空文件 | IllegalArgumentException | "上传文件为空" |
| 大小超限 | IllegalArgumentException | "文件大小超过限制，最大支持 10MB" |
| 类型不支持 | IllegalArgumentException | "不支持的文件类型，仅支持 jpeg/png/gif/webp" |
| OSS 上传失败 | RuntimeException | "图片上传 OSS 失败" |
| ChatClient 未初始化 | RuntimeException | "ChatClient 未初始化，key: xxx" |

### 9.2 Mock 配置参考

```java
// ChatClient Mock 配置
ChatClient mockChatClient = mock(ChatClient.class);
ChatClient.ChatClientRequestSpec mockRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
ChatClient.ChatClientCallSpec mockCallSpec = mock(ChatClient.ChatClientCallSpec.class);

when(mockChatClient.prompt()).thenReturn(mockRequestSpec);
when(mockRequestSpec.system(anyString())).thenReturn(mockRequestSpec);
when(mockRequestSpec.user(anyString())).thenReturn(mockRequestSpec);
when(mockRequestSpec.user(any(Consumer.class))).thenReturn(mockRequestSpec);
when(mockRequestSpec.advisors(any(Consumer.class))).thenReturn(mockCallSpec);
when(mockCallSpec.call()).thenReturn(mock(ChatClient.ChatClientResponse.class));
when(mockCallSpec.call().content()).thenReturn("AI 回复内容");
```
