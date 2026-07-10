# 意图路由 Structured Output 稳定性改造设计

## 1. 背景

当前意图路由包含两条可切换链路：

- `UNIFIED`：一个 LLM 一次完成任务识别、意图路由、槽位提取与澄清判断。
- `SPLIT`：Round1 完成任务拆分，Round2 针对每个子任务完成意图路由与槽位提取。

在线评测中，UNIFIED 曾出现语义判断为 `GENERAL_CHAT`，但返回 JSON 字符串未闭合，最终被判定为 `FORMAT_ERROR`。这说明当前实现主要依靠 Prompt 要求模型输出 JSON，结构稳定性仍不足。

本次改造补齐以下五层能力：

```text
JSON_OBJECT            保证 JSON 语法
+ Prompt Schema        引导字段结构
+ 本地 Schema 校验     拒绝非法结构
+ 自动重试             提升最终成功率
+ 业务规则校验         保证路由逻辑
```

## 2. 目标

1. UNIFIED、SPLIT Round1、SPLIT Round2 全部采用相同等级的结构化输出保障。
2. GLM 输出首先满足合法 JSON，再满足本地定义的字段、类型和枚举约束。
3. 网络、限流、空响应、JSON 错误、Schema 错误、DTO 转换错误和可修复的业务规则错误共享现有重试预算。
4. 不新增第二套重试机制，只把“模型响应校验异常”作为现有重试流程的一种新失败来源。
5. 未配置 `maxAttempts` 时默认最多调用 3 次；显式配置时按照配置值执行。
6. 保留现有 ChatClient Advisor、Langfuse Trace、最终响应 token 与总延迟统计能力。
7. 结构校验失败不能被静默伪装成一次成功的 `GENERAL_CHAT` 路由。
8. `executorNode` 等运行期字段由服务端根据意图统一生成，不信任模型输出。

## 3. 非目标

1. 不升级 Spring AI，继续使用 `1.1.2`。
2. 不升级 Spring AI Alibaba，继续使用 `1.1.2.2`。
3. 不引入第二套独立重试循环。
4. 不使用 Spring AI `StructuredOutputValidationAdvisor` 自带的重复调用能力。
5. 不尝试通过 JSON Schema 判断模型选择的意图在语义上是否正确。
6. 第一阶段不在重试 Prompt 中追加上一次校验错误，结构失败重试仍使用原始 Prompt。
7. 第一阶段不统计每次重试调用分别消耗的 token，也不要求每次供应商调用形成独立 Langfuse observation。
8. 第一阶段不扩展到 `stream()`、异步调用或会发生线程切换的模型调用。
9. 第一阶段不处理路由模型返回 `tool_calls` 的特殊场景；当前意图路由仍按纯文本 JSON 响应设计。
10. 第一阶段不扩展 SPLIT 的特殊澄清、部分成功或子任务失败编排语义，保持现有业务行为。

## 4. 当前能力与缺口

| 能力 | 当前状态 | 改造目标 |
|---|---|---|
| GLM `JSON_OBJECT` | 缺失 | 三类路由请求全部启用 |
| Prompt Schema | 已有 | 保留并同步服务端字段所有权 |
| JSON 语法解析 | 已有 | JSON Mode 提升稳定性，仍保留防御性解析 |
| 本地 JSON Schema 校验 | 缺失 | 为三个阶段建立独立 Schema |
| 结构失败重试 | 缺失 | 作为新失败来源接入现有 `RetryChatModel` |
| 网络与限流重试 | 已有 | 保持原逻辑，不做重构 |
| 任务图校验 | 已有 | 保留 `TaskGraphValidator` 并纳入响应校验 |
| 字段与业务一致性校验 | 部分具备 | 补齐跨字段和意图映射规则 |
| 重试可观测性 | 部分具备 | 增加 attempt、原因及最终失败类型 |

当前实际链路：

```text
Prompt JSON 示例
  -> 模型自由输出文本
  -> extractJson()
  -> JSON.parseObject()
  -> 手动读取字段
  -> TaskGraphValidator
```

目标链路：

```text
Prompt Schema
  -> GLM JSON_OBJECT
  -> RetryChatModel 调用供应商
  -> 可选响应校验器
       -> 空响应检查
       -> JSON 解析
       -> 本地 JSON Schema 校验
       -> DTO 转换
       -> 可修复的业务规则校验
  -> 正常路由
  -> 服务端补齐 executorNode 等运行期字段

任一步发生可重试失败
  -> 回到同一个 RetryChatModel 重试循环
  -> 使用该阶段原始 Prompt 再次调用
  -> 重试耗尽后由路由层统一 fallback
```

## 5. 重试预算边界

重试预算按照一次独立的 LLM 推理任务计算：

| 阶段 | 重试预算 |
|---|---|
| UNIFIED 路由 | 当前模型配置的 `maxAttempts` |
| SPLIT Round1 任务拆分 | 当前模型配置的 `maxAttempts` |
| SPLIT Round2 子任务 1 | 当前模型配置的 `maxAttempts` |
| SPLIT Round2 子任务 N | 每个子任务拥有独立预算 |

配置规则：

```text
未配置 maxAttempts -> 默认 3 次
显式配置 maxAttempts -> 按配置值执行
```

Round1 与 Round2 不共享预算。每个 Round2 子任务也拥有独立预算。

同一个推理阶段中，禁止出现：

```text
RetryChatModel N 次 × Schema Advisor M 次
```

意图路由使用的模型配置必须开启 retry。若 retry 未开启，响应仍可校验，但校验失败不会由当前方案自动发起下一次供应商调用；该配置不满足本需求验收条件。

## 6. 总体设计

### 6.1 GLM JSON Mode

三类路由请求统一通过 `OpenAiChatOptions` 设置：

```java
ResponseFormat.builder()
        .type(ResponseFormat.Type.JSON_OBJECT)
        .build();
```

JSON Mode 的职责是提高合法 JSON 输出的稳定性，不保证字段完整、字段类型、枚举或业务规则正确。

配置放在 `IntentRoutingService.callRoutingModel(...)` 的路由请求级别，不修改全局 ChatClient 默认配置，避免影响普通聊天和其他模型调用。

### 6.2 Prompt Schema

保留三个现有 Prompt 中的业务定义：

- 合法意图枚举及说明；
- 意图与执行节点映射；
- 多任务与单任务边界；
- 澄清条件；
- 槽位语义；
- Few-Shot 示例。

由于 GLM 不支持 `json_schema + strict=true`，Prompt 中仍需保留结构说明。第一阶段不删除现有 JSON 示例，避免一次改动同时改变模型语义表现。

模型只负责输出任务结构、路由语义、槽位和澄清字段。以下运行期字段不再要求模型输出，也不进入模型输出 DTO 和 Schema：

```text
executorNode
taskType
status
result
latencyMs
errorMessage
metrics
```

`executorNode` 由服务端根据 `intent` 调用统一映射逻辑生成。UNIFIED Prompt 中已有的 `executorNode`、`taskType` 示例需要删除，确保 UNIFIED 与 SPLIT 使用同一字段所有权规则。

### 6.3 本地 Schema 校验

建立三个模型输出类型，避免直接使用包含运行时字段的业务对象生成 Schema：

```text
UnifiedRoutingOutput
QueryDecompositionOutput
TaskIntentRoutingOutput
```

三个输出 DTO 均不包含 `executorNode`、`taskType`、`status`、`RoutingExecutionMetrics` 等业务运行期字段。

Schema 由 Spring AI `JsonSchemaGenerator` 或 `BeanOutputConverter` 根据输出 DTO 生成。本地校验复用当前依赖树已有的 `DefaultJsonSchemaValidator`。为避免依赖偶然来自 MCP 的传递依赖，所需校验依赖应在 `ai-agent-study-domain/pom.xml` 中显式声明。

本地 Schema 至少校验：

- JSON 根节点必须是对象；
- 必填字段存在；
- 字段类型正确；
- `intent`、`confidence` 等字段属于当前阶段允许的枚举；
- 数组元素结构正确；
- 嵌套对象结构正确；
- 不允许的额外字段被拒绝；
- nullable 字段按照明确 Schema 表达，不能依赖模型猜测。

Schema 校验只负责结构，不负责判断 `PE_RETRIEVAL` 是否是语义正确答案。

意图输出 Schema 只允许当前路由真正可执行的意图值，不能因为直接复用系统枚举而自动放行 `UNKNOWN` 或其他不应由该阶段输出的值。

### 6.4 统一重试控制

`RetryChatModel` 继续作为唯一重试控制器。现有网络、限流、服务端异常分类、退避、attempt 计数和最大尝试次数逻辑保持不变。

本次只新增一种失败来源：

```text
ResponseValidationException
```

新增通用校验接口：

```java
@FunctionalInterface
public interface ChatResponseValidator {
    void validate(ChatResponse response);
}
```

校验器不包含在 `RetryChatModel` 内部，避免基础设施层依赖路由 DTO。路由层只负责为当前阶段选择校验器。

`CallRetryStrategy#doExecute(...)` 在供应商正常返回后执行可选校验器：

```java
@Override
protected ChatResponse doExecute(Prompt prompt) {
    ChatResponse response = delegate.call(prompt);
    ChatResponseValidator validator = ResponseValidationContext.currentValidator();
    if (validator != null) {
        validator.validate(response);
    }
    return response;
}
```

校验失败时抛出 `ResponseValidationException`。现有重试判定只需把该异常明确视为可重试，不通过异常消息或动态错误码识别。没有注册 validator 的普通模型调用保持原行为，网络、限流、压缩和其他异常路径不因本次改造改变。

### 6.5 校验器传递

当前路由层持有 `ChatClient`，不能直接调用 `RetryChatModel` 的扩展方法。设计一个同步调用范围内的 `ResponseValidationContext`：

```java
ResponseValidationContext.withValidator(
        validator,
        () -> chatClient.prompt(prompt)
                .options(jsonObjectOptions)
                .call()
                .chatResponse());
```

`ResponseValidationContext` 可使用 `ThreadLocal<Deque<ChatResponseValidator>>` 实现，并遵守以下边界：

- 仅支持同步 `ChatClient.call()`；
- validator 注册、供应商调用、校验和重试必须在同一线程完成；
- 不支持 `stream()`、异步调用或中途切换线程；
- 使用 `try/finally` 清理上下文；
- 使用栈结构支持同线程嵌套调用；
- 最外层退出后调用 `ThreadLocal.remove()`；
- 并发请求之间不得泄漏 validator；
- 未注册校验器的普通请求保持原行为。

`RetryChatModel` 每次从供应商获得响应后执行当前校验器。校验器抛出 `ResponseValidationException` 时，进入现有重试判断。在上述同步边界内不会发生跨线程上下文丢失；未来若改为异步或响应式调用，需要重新设计显式上下文传递。

### 6.6 响应校验器职责与重试行为

每个阶段使用独立 validator，但遵循相同执行顺序：

```text
1. ChatResponse 和输出文本非空检查
2. JSON 解析
3. JSON Schema 校验
4. DTO 转换
5. 可修复的业务规则校验
```

任一步失败时抛出带明确分类的 `ResponseValidationException`：

```text
EMPTY_RESPONSE
JSON_PARSE_ERROR
SCHEMA_VALIDATION_ERROR
DTO_CONVERSION_ERROR
BUSINESS_VALIDATION_ERROR
```

第一阶段不修改原有 Prompt。无论网络异常还是响应校验异常，下一次 attempt 都继续使用该阶段的原始 Prompt。后续只有在线评测证明重复使用原 Prompt 的修复率不足时，才考虑增加校验反馈 Prompt。

现有 parser 不再捕获结构异常并立即构造 fallback。validator 在 `RetryChatModel` attempt 内完成结构、DTO 和业务校验；parser 只处理已经通过校验的响应，并映射为业务 VO。为避免在 ThreadLocal 中缓存 DTO，parser 可以再次执行一次 DTO 转换。只有重试耗尽或遇到不可重试异常时，路由层才统一生成 fallback。

## 7. 失败分类

以下错误占用同一阶段的当前配置预算并允许重试：

- 网络超时；
- 可恢复的连接失败；
- 429 限流；
- 可重试的供应商 5xx；
- 空响应；
- 非法 JSON；
- JSON Schema 校验失败；
- 由模型输出导致的 DTO 转换失败。
- 模型重新回答可能修复的业务规则失败。

以下错误直接失败，不重复调用：

- 401/403；
- client/model 配置错误；
- 供应商不支持 `JSON_OBJECT`；
- 本地 Schema 本身非法；
- 代码缺陷导致的空指针等确定性异常。
- 服务端意图到执行节点映射配置错误。

最终仍失败时，返回现有降级对象，但必须保留明确失败分类：

```text
INFRA_ERROR
EMPTY_RESPONSE
JSON_PARSE_ERROR
SCHEMA_VALIDATION_ERROR
DTO_CONVERSION_ERROR
BUSINESS_VALIDATION_ERROR
```

评测器不得仅根据降级对象中的 `GENERAL_CHAT` 将该次执行视为正常路由，应优先读取结构化失败类型，异常消息只用于诊断。

## 8. 业务规则校验

Schema 校验通过后继续执行业务校验。

### 8.1 UNIFIED

- `multiTask=true` 时 `taskList.size() > 1`；
- `multiTask=false` 时最多一个任务；
- `needsClarification=true` 时 `missingInfo` 非空；
- 无需澄清时 `taskList` 不能为空；
- `taskId`、`taskIndex`、`totalTasks` 一致；
- `dependsOn` 指向已存在任务且无环。
- `intent` 和 `confidence` 属于当前阶段允许值；
- 不校验模型输出的 `executorNode`，因为该字段不再由模型输出；
- DTO 映射为业务 VO 后，由服务端根据 `intent` 生成 `executorNode`。

### 8.2 SPLIT Round1

- `taskList` 不能为空；
- 单任务不得被重复拆分；
- task index 连续且唯一；
- `totalTasks` 与列表长度一致；
- 依赖关系有效且无环。

### 8.3 SPLIT Round2

- `intent` 必须属于当前路由允许的标准意图；
- `confidence` 必须属于 `ConfidenceEnum`；
- 禁止 `TECHNICAL_CONSULTING` 等非系统枚举；
- 槽位对象结构和类型符合 Schema；
- 执行节点由服务端根据 intent 统一映射。

业务错误是否重试应单独判断。只有模型重新回答可能修正的错误才可作为 `ResponseValidationException` 进入当前预算；纯代码或配置错误不可重试。

本次不新增 SPLIT Round2 澄清输出或部分成功编排语义，相关场景继续沿用现有行为。

## 9. 文件级改造点

### 9.1 模型与重试层

修改：

- `ai-agent-study-domain/.../armory/factory/element/RetryChatModel.java`
- `ai-agent-study-domain/.../armory/factory/element/RetryableExceptionTypes.java`，或在现有重试判定处增加明确异常分支

新增：

- `ChatResponseValidator.java`
- `ResponseValidationContext.java`
- `ResponseValidationException.java`
- `ResponseValidationFailureType.java`

改造内容：

- 仅在 `CallRetryStrategy#doExecute(...)` 的正常响应后执行可选 validator；
- `ResponseValidationException` 作为现有重试逻辑的一种新失败来源；
- 不新增重试循环；
- 不修改原有网络、限流、退避、压缩和最大 attempts 逻辑；
- 重试时继续使用原始 Prompt；
- 默认 `maxAttempts=3`，显式配置时按配置值执行；
- 明确仅支持同步 `call()`。

### 9.2 路由层

修改：

- `IntentRoutingService.java`
- `IntentRoutingPrompt.java`
- `TaskGraphValidator.java`

新增：

- `RoutingStructuredOutputValidator.java`
- `UnifiedRoutingOutput.java`
- `QueryDecompositionOutput.java`
- `TaskIntentRoutingOutput.java`

改造内容：

- 路由请求启用 `JSON_OBJECT`；
- 三个阶段选择各自 Schema 和业务 validator；
- 通过同步 `ResponseValidationContext` 注册 validator；
- 输出 DTO 转换为现有业务 VO；
- `executorNode`、`taskType`、`status` 等字段由服务端生成；
- UNIFIED Prompt 删除运行期字段；
- parser 不再吞掉结构错误并提前 fallback；
- 重试耗尽后统一保留明确失败原因。

### 9.3 模型配置

意图路由使用的模型配置必须开启 retry：

```text
enabled=true
maxAttempts 未配置时使用默认值 3
maxAttempts 已配置时使用配置值
```

本次不要求把所有模型全局强制包装为 `RetryChatModel`，只要求意图路由实际使用的模型配置满足上述条件。

### 9.4 依赖

修改：

- `ai-agent-study-domain/pom.xml`

改造内容：

- 显式声明 JSON Schema 校验所需依赖；
- 不依赖其他 Starter 的间接引入行为；
- 不升级 Spring AI 或 Spring AI Alibaba。

### 9.5 评测与报告

修改：

- `IntentRoutingOnlineEvaluator.java`
- `IntentRoutingOnlineEvalReportWriter.java`
- 相关指标对象与测试。

第一阶段至少新增或修正：

- `finalFailureType`；
- `jsonModeEnabled`；
- `schemaValidationEnabled`；
- `attemptCount` 和 `retryReasons`，仅在现有重试层能够以低侵入方式透传时补充。

本次不要求统计每个 attempt 的独立 token，也不要求把每次供应商调用拆成独立 Langfuse observation。现有最终响应 token、ChatClient trace 和端到端延迟统计保持不丢失。

## 10. 可观测性

每个路由阶段继续记录现有指标，并新增明确最终失败类型：

```text
stageName
taskId
finalFailureType
totalLatencyMs
promptTokens
completionTokens
totalTokens
```

如果能在不改变现有响应结构和重试主流程的前提下透传，再补充：

```text
attemptCount
retryReasons
```

token 只统计现有链路可取得的最终响应 usage，不承诺等于所有 attempts 的真实总消耗。文档、报告字段和指标名称应明确这一口径，避免误解为完整重试成本。

对于 SPLIT，多任务场景必须能区分：

```text
query-decomposition
task-routing-slot[sub-1]
task-routing-slot[sub-2]
```

## 11. 测试设计

### 11.1 RetryChatModel

- 未注册 validator 时保持原有调用、异常分类和重试行为；
- 第一次返回合法结构，只调用一次；
- 第一次 JSON 错误、第二次成功，共调用两次；
- 第一次网络错误、第二次 Schema 错误、第三次成功，共调用三次；
- `ResponseValidationException` 明确被判定为可重试；
- 其他未知业务异常不会因为本次改造被误判为可重试；
- 未配置 `maxAttempts` 时默认最多调用三次；
- 显式配置 `maxAttempts=N` 时最多调用 N 次；
- 401/403 等不可重试错误只调用一次；
- 结构失败重试继续使用原始 Prompt，不追加失败反馈；
- 普通非路由请求未注册 validator 时保持原行为；
- 校验上下文在成功和异常后均被清理。

### 11.2 ResponseValidationContext

- 同一线程注册后可以在 `RetryChatModel` 中读取 validator；
- 正常返回后上下文被清理；
- 供应商异常后上下文被清理；
- validator 异常后上下文被清理；
- 嵌套调用按栈顺序恢复外层 validator；
- 两个并发线程使用不同 validator，互不泄漏；
- 未注册时返回空上下文；
- `stream()` 不使用该上下文能力。

### 11.3 Schema Validator

- 缺少必填字段被拒绝；
- 字段类型错误被拒绝；
- 非法 intent 枚举被拒绝；
- 非法 confidence 枚举被拒绝；
- `UNKNOWN` 等当前阶段不允许值被拒绝；
- 多余字段按照 Schema 策略被拒绝；
- 模型输出 `executorNode`、`taskType`、`status` 等运行期字段时被拒绝；
- 合法嵌套任务数组通过；
- DTO 内部运行期字段不出现在模型输出 Schema 中。

### 11.4 路由集成

- UNIFIED 使用 Unified Schema；
- SPLIT Round1 使用 Decomposition Schema；
- 每个 SPLIT Round2 独立使用 Intent Schema 和当前配置预算；
- Round1 消耗重试后，Round2 仍拥有完整独立预算；
- JSON Mode 请求参数确实传递给 GLM 兼容接口；
- Schema、DTO 或业务规则失败能够在 `RetryChatModel` 内触发重试；
- parser 不会在第一次结构失败时提前返回 fallback；
- 重试耗尽后才生成 fallback；
- 服务端根据 intent 生成正确的 `executorNode`；
- Schema 失败最终在评测中记为格式错误，而不是成功的 GENERAL_CHAT。

### 11.5 在线评测

依次执行：

1. UNIFIED smoke，最多 3 cases；
2. SPLIT smoke，使用同一批 cases；
3. UNIFIED challenge；
4. SPLIT challenge；
5. 比较语义准确率、最终格式错误率、平均重试次数和总延迟。

token 指标继续展示当前最终响应口径，但不用于比较完整重试成本。

## 12. 验收标准

1. UNIFIED、Round1、Round2 请求均启用 GLM `JSON_OBJECT`。
2. 三个阶段均有独立、可测试的本地 Schema 和 validator。
3. 响应校验失败作为一种新失败来源进入原有 `RetryChatModel`，不新增第二套重试循环。
4. 原有网络、限流、退避、压缩和不可重试异常行为不发生回归。
5. 未配置 `maxAttempts` 时默认 3 次，显式配置时按配置值执行。
6. SPLIT 各阶段和各子任务拥有独立重试预算。
7. `ResponseValidationContext` 仅用于同步 `call()`，成功和异常后均无上下文泄漏。
8. 模型不再输出 `executorNode`、`taskType`、`status` 等运行期字段，服务端根据 intent 统一生成执行节点。
9. parser 不会吞掉首次结构错误，fallback 只在重试耗尽或不可重试失败后产生。
10. 格式错误不会被静默统计为成功路由。
11. 原有 Langfuse、最终响应 token 和总延迟指标不丢失。
12. 原有路由和 RetryChatModel 测试通过，新增结构校验、上下文隔离和重试集成测试通过。
13. UNIFIED 与 SPLIT 使用同等级结构保障，实验不引入新的链路差异变量。

## 13. 实施顺序

1. 增加输出 DTO、Schema 生成与本地 validator。
2. 调整 Prompt 字段所有权，删除模型输出中的运行期字段。
3. 增加 `ResponseValidationContext`、`ChatResponseValidator` 和校验异常分类。
4. 在 `RetryChatModel` 正常响应后执行可选 validator，并把校验异常纳入原有重试判断。
5. 为三类路由调用启用 `JSON_OBJECT` 和对应 validator。
6. 调整 parser 与 fallback 边界，确保校验失败不会被提前吞掉。
7. 服务端统一生成 `executorNode` 等运行期字段。
8. 补齐失败分类、评测指标和报告。
9. 完成单元测试后运行低成本 smoke 在线评测。

## 14. 后续可选增强

以下能力仅在第一阶段数据证明有必要时再设计：

- 在结构失败重试时向 Prompt 追加受控的校验反馈；
- 统计所有 attempts 的真实 token 与供应商成本；
- 为每次供应商调用创建独立 Langfuse observation；
- 支持异步、线程切换和响应式上下文传播；
- 处理 `tool_calls`、特殊 `finishReason`；
- 扩展 SPLIT 的澄清、部分成功和子任务失败编排语义。
