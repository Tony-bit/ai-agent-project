# ObservabilityAdvisor output 修复 story

- status: pass

## 1. 背景

- status: pass

当前 `ObservabilityAdvisor` 已能够在 Langfuse 中创建 trace/span，并正常展示 input。
但线上日志表明 `after()` 阶段未能从 `ChatClientResponse` 中正确提取 LLM 输出，导致 Langfuse 中 generation 仅记录 input，没有 output。

已观测到的关键日志现象：

- `ObservabilityAdvisor: output text extraction all paths returned empty`
- `chatResponse` 中存在 `generations=[Generation[assistantMessage=AssistantMessage[...]]]`
- `assistantMessage.textContent` 为空
- 业务日志显示本次对话 `responseLength=2255`，说明模型实际已返回文本

因此问题可界定为：

> `after()` 已执行，但当前 output 提取逻辑未覆盖实际 Spring AI 返回结构，导致 `extractOutputText()` 返回空串。

---

## 2. 问题分析

- status: pass

### 2.1 当前 output 提取路径不匹配实际结构

当前 `ObservabilityAdvisor` 的 output 提取逻辑依赖以下路径：

1. `chatResponse.getResult().getOutput().getText()`
2. `chatResponse.getMetadata().get("content")`
3. `chatResponse.getMetadata().get("result")`

但从日志可见，当前有效响应主要体现在 `generations` 结构中，而不是上述 metadata/result 路径，因此现有逻辑无法稳定取到最终 output。

### 2.2 `assistantMessage.textContent` 为空，说明不能简单依赖单一文本字段

当前日志中可见：

- `assistantMessage.textContent=`
- 但业务层最终 `responseLength > 0`

说明当前模型适配器 / Spring AI 版本下，最终文本并不一定落在 `textContent` 这个字段上，需要进一步基于 `Generation` / `AssistantMessage` 的真实 API 结构进行提取。

### 2.3 `before()` 未回填 input，不影响 trace 创建，但影响 `after()` 的 generation 上报稳定性

当前 `before()` 能直接调用 `startTrace(sessionId, input, traceMetadata)`，因此 trace 中的 input 已能展示。

但 `after()` 上报 generation 时，input 来源是 `extractPromptText(chatClientResponse, context)`，它优先依赖 `context.get("input")`，其次才退化为 `chatResponse.metadata.prompt`。

由于 `before()` 当前未执行 `context.put("input", input)`，导致 generation 阶段 input 获取依赖不稳定字段。该问题不是本次 output 缺失的主因，但建议一并修复，以保证 trace 与 generation 使用同一份原始输入。

---

## 3. 变更目标

- status: pass

本次变更目标如下：

1. 修复 `ObservabilityAdvisor.after()` 对 output 的提取逻辑
2. 让 Langfuse generation 稳定展示 output
3. 增强 output 提取失败时的日志，便于后续适配 Spring AI/模型升级
4. 回填原始 input 到 context，保证 generation 上报时 input 来源稳定

---

## 4. 变更范围

- status: pass

本次仅修改以下文件：

- `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/ObservabilityAdvisor.java`

本次不计划修改：

- `ObservabilityService`
- 其他业务节点
- stream 处理逻辑
- Langfuse SDK 接入逻辑

---

## 5. 详细变更方案

- status: pass

### 5.1 在 `before()` 中回填 input 到 context

#### 现有代码

```35:65:ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/ObservabilityAdvisor.java
@Override
public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
    Map<String, Object> context = new HashMap<>(chatClientRequest.context());

    String input = extractUserText(chatClientRequest);
    String sessionId = doGetSessionId(context);

    String traceId = context.containsKey(TRACE_ID_KEY)
            ? String.valueOf(context.get(TRACE_ID_KEY))
            : "";

    if (StringUtils.isBlank(traceId)) {
        Map<String, Object> traceMetadata = new HashMap<>();
        traceMetadata.put("advisor", getName());
        traceMetadata.put("sessionId", sessionId);
        traceId = observabilityService.startTrace(sessionId, input, traceMetadata);
        context.put(TRACE_ID_KEY, traceId);
    }

    Map<String, Object> spanMetadata = new HashMap<>();
    spanMetadata.put("advisor", getName());
    spanMetadata.put("sessionId", sessionId);
    String spanId = observabilityService.startSpan(traceId, "chat_client_call", spanMetadata);

    context.put(SPAN_ID_KEY, spanId);
    context.put(START_AT_KEY, System.currentTimeMillis());

    return ChatClientRequest.builder()
            .prompt(chatClientRequest.prompt())
            .context(context)
            .build();
}
```

#### 拟修改点

在 `before()` 中新增：

```java
context.put("input", input);
```

#### 目的

- 保证 `after()` 中 `extractPromptText(...)` 能稳定获取原始输入
- 避免 generation 上报时依赖 `chatResponse.metadata.prompt`
- 保证 trace 与 generation 看到的是同一份 input
- 说明：这不是 output 缺失的主因修复，而是 generation input 获取链路的稳定性增强

---

### 5.2 重构 `extractOutputText()` 的提取顺序

#### 现有代码

```174:197:ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/ObservabilityAdvisor.java
private String extractOutputText(ChatClientResponse response) {
    if (response == null || response.chatResponse() == null) {
        log.debug("extractOutputText: response or chatResponse is null");
        return "";
    }

    ChatResponse chatResponse = response.chatResponse();
    String outputText = tryExtractFromResult(chatResponse);

    if (StringUtils.isBlank(outputText)) {
        outputText = tryExtractFromMetadata(chatResponse);
    }

    if (StringUtils.isBlank(outputText)) {
        outputText = tryExtractFromMessageContent(chatResponse);
    }

    if (StringUtils.isBlank(outputText)) {
        log.warn("ObservabilityAdvisor: output text extraction all paths returned empty, "
                + "chatResponse={}", chatResponse);
    }

    return outputText;
}
```

#### 现存问题

当前逻辑完全未覆盖 `generations` 主结构，而日志表明当前有效输出主要存在于 `generations` 中。

#### 拟修改点

调整提取顺序为：

1. `tryExtractFromResult(chatResponse)`
2. `tryExtractFromGenerations(chatResponse)`
3. `tryExtractFromMetadata(chatResponse)`
4. `tryExtractFromMessageContent(chatResponse)`
5. 若仍为空，则输出增强日志

#### 目的

优先覆盖 Spring AI 当前实际返回结构，提升 output 提取成功率。

---

### 5.3 新增 `tryExtractFromGenerations(ChatResponse chatResponse)`

- status: in_progress

#### 拟新增职责

从 `chatResponse.getResults()` / `chatResponse` 的 generation 主结构中提取第一条 generation，再从可编译、可访问的公开 API 中获取最终文本。

#### 第一版实现策略

本次优先采用保守实现：

1. 先基于 `ChatResponse` / `Generation` 的公开 API 访问第一条 result/generation
2. 优先读取 `getOutput().getText()` 这类稳定文本接口
3. 不在第一版中写死依赖 `AssistantMessage.textContent` 之类尚未在本文件中验证过的字段
4. 若编译期发现 Spring AI 当前版本 API 与预期不一致，再按真实类结构做小范围调整

#### 设计原则

- 优先使用 Spring AI 官方对象结构中的公开 API
- 避免继续依赖 metadata 中的猜测性字段
- 避免一开始就引入激进反射或深层对象探测
- 若 generation 为空或 message 中无文本，则返回空串

#### 目的

解决当前“trace 有 input、generation 无 output”的核心问题，同时控制改动风险。

---

### 5.4 增强 output 提取失败日志

- status: in_progress

#### 现有代码

```191:194:ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/ObservabilityAdvisor.java
if (StringUtils.isBlank(outputText)) {
    log.warn("ObservabilityAdvisor: output text extraction all paths returned empty, "
            + "chatResponse={}", chatResponse);
}
```

#### 拟修改点

将失败日志从内联 `warn` 升级为独立诊断方法，例如 `logOutputExtractionFailure(chatResponse)`，输出更有定位价值的信息，例如：

- `result` 是否为空
- generation / result 数量
- metadata key 列表
- 诊断日志自身是否发生异常

#### 目的

减少未来排查成本，便于在更换模型或升级 Spring AI 后快速识别结构变化。

---

### 5.5 保持 `after()` 上报逻辑不变，仅修正 input/output 来源

#### 现有代码

```68:116:ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/ObservabilityAdvisor.java
@Override
public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
    Map<String, Object> context = new HashMap<>(chatClientResponse.context());

    String traceId = asString(context.get(TRACE_ID_KEY));
    String spanId = asString(context.get(SPAN_ID_KEY));
    long startAt = parseStartAt(context.get(START_AT_KEY));
    long latencyMs = startAt > 0 ? System.currentTimeMillis() - startAt : -1;

    String sessionId = doGetSessionId(context);
    String input = extractPromptText(chatClientResponse, context);
    String output = extractOutputText(chatClientResponse);
    String model = extractModelName(chatClientResponse);

    // ...
}
```

#### 拟处理方式

保留现有 trace/span/generation 上报主流程，仅通过：

- `before()` 回填 input
- `extractOutputText()` 修正 output 提取逻辑
- 新增 generation 主结构提取与诊断日志

来恢复 Langfuse 展示。

#### 目的

最小化改动范围，避免在未确认必要性前扩大改动面。

---

### 5.6 关于 `after()` 是否必须再次携带 input 的设计结论

- status: pass

#### 已确认结论

本次不调整 `ObservabilityService.logGeneration(...)` 的接口设计，继续沿用当前 generation 记录 `input + output` 的模式。

#### 原因说明

1. 从技术上说，`before` 的 input 与 `after` 的 output 确实可以只依赖 `traceId` / `spanId` 关联，这是一种成立的规范化设计
2. 但当前项目的 `logGeneration(traceId, spanId, name, input, output, ...)` 接口已经明确采用“单条 generation 自包含 input/output”的建模方式
3. 因此本次修复不做观测模型重构，只修复 output 提取失败问题，并增强 generation input 获取稳定性
4. `before()` 仍然保留独立价值，用于：
   - 创建 trace/span
   - 记录调用起点时间
   - 在异常场景下保留可追踪上下文
   - 向后续链路透传观测上下文

#### 结论

本次按最小改动方案执行：

- 不移除 `after()` 中的 input
- 不改 `ObservabilityService` 接口
- 不改 trace 与 generation 的现有职责划分

---

## 6. 不在本次变更范围内的项

- status: pass

以下内容暂不纳入本次修复：

1. `adviseStream()` 的流式输出聚合与观测补齐
2. `endSpan` 与 `logGeneration` 的解耦优化
3. `userId / agentId / clientId` 元数据接入
4. `ragRetrievedHitCount` 指标准确性优化
5. 失败请求下 generation 级错误事件补齐

这些项可在本次 output 修复完成并验证通过后，再作为下一阶段增强项处理。

---

## 7. 验证方案

- status: pass

### 7.1 本地验证

执行一次普通非流式对话请求，确认：

- `ObservabilityAdvisor` 不再打印 `output text extraction all paths returned empty`
- debug / info 日志中 output length 大于 0
- 业务侧 `responseLength` 与 observability output length 大体一致

### 7.2 Langfuse 验证

在 Langfuse 中检查本次请求对应的 trace / generation：

- trace 仍正常存在
- generation 中可看到 input
- generation 中可看到 output
- token usage、latency 等字段仍正常展示

### 7.3 回归验证

确认本次修改不会影响：

- traceId / spanId 透传
- 非 output 相关 metadata 上报
- 正常调用返回结果

---

## 8. 风险与注意事项

- status: pass

1. Spring AI 当前版本中 `AssistantMessage` 的真实文本字段可能与日志展示字段不同，开发时需基于实际 API 而不是仅凭 `toString()` 猜测
2. 若模型适配器返回的是结构化 content blocks，而非单字符串文本，需要在 `tryExtractFromGenerations()` 中兼容处理
3. 若当前请求是 streaming 模式，则本次修复可能只能覆盖非流式 call 场景，需要后续单独补充 stream 观测逻辑

---

## 9. 执行顺序

- status: pass

1. 修改 `before()`，回填 input
2. 重构 `extractOutputText()` 调用顺序
3. 新增 `tryExtractFromGenerations()`
4. 增强 output 提取失败日志
5. 编译验证
6. 发起一次真实请求验证 Langfuse 展示效果
