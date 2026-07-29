# Recommendation LLM 四场景对比测试设计

## 背景

`RecommendationNode` 在一次真实交易流程中使用 `clientId=6013` 调用 `deepseek-v4-pro`，运行时 Prompt 长度为 38472 字符。该调用先经历 ChatMemory 写 Redis 超时，随后在 45 秒内没有收到模型首包，最终以 `chunkCount=0` 失败。数据库对比已经确认，交易客户端 6001 至 6013 使用相同的模型、API 和 Advisor 配置，因此需要通过同一进程内的对照实验隔离 Prompt 大小、ChatClient 包装和 Advisor 链路的影响。

## 目标

新增一个手动启用的在线集成测试，在同一个 Spring 上下文、同一个模型实例和同一个测试方法中完成一次连接预热及四个顺序场景，统一采集首响应、首内容和总耗时，以判断故障位于模型服务、ChatClient 包装还是原始 Advisor 链路。

本设计不修改生产代码，不修改数据库配置，也不把 API Key 或完整生产 Prompt 写入源码和日志。

## 方案选择

采用单个顺序执行的 `@SpringBootTest` 测试方法。相比四个独立测试，该方案可以保证执行顺序并尽量复用底层 DNS、TLS 和 HTTP 连接池；相比启动完整 Trading 流程，该方案不会混入前置分析节点，能够直接比较四条调用路径。

测试默认跳过，仅当环境变量 `RUN_RECOMMENDATION_LLM_COMPARISON=true` 时执行，避免普通 `mvn test` 意外调用在线模型。

## 测试对象

- Spring 动态注册的模型 Bean：`ai_client_model_2009`
- Spring 动态注册的原始客户端：`ai_client_6013taskType0`
- 无 Advisor 客户端：测试内基于同一个 `ChatModel` 构建
- 模型与客户端必须来自应用实际 Armory 初始化结果，不在测试中硬编码 API 地址、API Key 或模型名

## Prompt 来源

短 Prompt 使用固定诊断文本，要求模型只返回简短确认信息。

长 Prompt 优先读取环境变量 `RECOMMENDATION_PROMPT_FILE` 指向的 UTF-8 文件，以便直接复现一次真实 Recommendation 输入。未提供文件时，测试生成固定结构、约 38472 字符的 Recommendation 风格 Prompt，包含分析报告、辩论历史、校验信息和最小 JSON 输出约束。生成内容只用于延迟诊断，不包含真实凭据。

场景 2、3、4 必须复用同一个长 Prompt 字符串，避免输入差异污染对比结果。

## 执行流程

1. 等待 Armory 中模型 `2009` 和客户端 `6013` 可用；超过限定时间则测试失败并报告初始化状态。
2. 使用模型 `2009` 执行一次短 Prompt 的阻塞式 `ChatModel.call()`，用于预热 DNS、TLS 和 HTTP 连接池；预热结果必须非空。
3. 顺序执行四个流式场景：
   - 短 Prompt直接调用 `ChatModel.stream()`。
   - 长 Prompt 直接调用 `ChatModel.stream()`。
   - 长 Prompt 通过无 Advisor 的 `ChatClient` 调用。
   - 长 Prompt 通过数据库配置的原始 `6013 ChatClient` 调用，并使用本次测试唯一的 conversation ID。
4. 每个场景无论成功或失败都生成一条结果，单个场景异常不得阻止后续场景运行。
5. 最后输出四场景汇总，测试至少断言预热成功、四条结果均已生成，并对短 Prompt 基线要求收到有效内容。

## 指标口径

每个场景记录以下字段：

| 字段 | 含义 |
|---|---|
| `scenario` | 场景名称 |
| `promptLength` | 发送的用户 Prompt 字符数 |
| `firstResponseLatencyMs` | 订阅后收到第一个 `ChatResponse` 的耗时 |
| `firstContentLatencyMs` | 订阅后收到第一个非空文本 chunk 的耗时 |
| `totalLatencyMs` | 流结束或抛出异常的总耗时 |
| `responseCount` | 收到的 `ChatResponse` 数量 |
| `contentChunkCount` | 收到的非空文本 chunk 数量 |
| `responseLength` | 聚合文本长度 |
| `completionState` | `completed` 或 `error` |
| `errorType` | 根异常类型 |
| `errorMessage` | 截断后的根异常消息 |

日志只打印指标和聚合响应长度，不打印完整长 Prompt。短响应可以按现有测试约定打印，但不得包含认证信息。

## 错误处理与结果解释

- 短 Prompt 直接模型调用失败：在线模型基线不可用，本轮其他结果只作参考。
- 长 Prompt 直接模型失败而短 Prompt 成功：优先判断模型 prefill 或首包阈值问题。
- 直接模型成功、无 Advisor ChatClient 失败：检查 Spring AI ChatClient 包装和聚合链路。
- 无 Advisor 客户端成功、原始 6013 客户端失败：检查 ChatMemory、Redis、Observability 或 Advisor 顺序。
- 原始客户端失败时仍保留前三组结果，便于在一次运行内完成比较。

## 文件范围

新增：

`ai-agent-study-app/src/test/java/denny/ai/agent/test/RecommendationLlmComparisonIntegrationTest.java`

不修改生产代码、生产资源和数据库迁移。

## 验证方式

默认测试套件运行时，该在线测试应通过 JUnit assumption 明确显示为 skipped。

手动在线运行时使用：

```powershell
$env:RUN_RECOMMENDATION_LLM_COMPARISON="true"
$env:RECOMMENDATION_PROMPT_FILE="D:\path\to\recommendation-prompt.txt"
mvn -pl ai-agent-study-app -am `
  -Dtest=RecommendationLlmComparisonIntegrationTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

未设置 `RECOMMENDATION_PROMPT_FILE` 时使用内置生成的诊断 Prompt。运行完成后通过同一日志中的四条指标和汇总结果判断首包延迟及失败边界。

## 风险与约束

- 在线模型延迟受供应商负载和网络波动影响，单次结果不能作为稳定性能基准；必要时重复运行并比较分位数。
- 测试会产生真实模型费用，因此必须保持显式 opt-in。
- 阻塞式预热只能提高连接复用概率，不能保证供应商侧会话或路由固定。
- 生成的长 Prompt 与真实业务内容不同，只用于输入规模诊断；需要内容级复现时必须提供真实 Prompt 文件。
