# 2026-06-06 IntentRoutingService 统一 ChatClient 装配改造 Story

status: pass
owner: Cursor Agent
created_at: 2026-06-06

## 1. 背景

status: pass

当前 `IntentRoutingNode` 主链已经切到统一路由能力，但 `IntentRoutingService` 内部仍通过默认注入的 `ChatClient` 发起 LLM 调用：

```33:37:ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingService.java
@Resource
private ChatClient chatClient;

@Resource
private IntentFewshotService intentFewshotService;
```

统一路由主链当前调用如下：

```82:84:ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNode.java
List<String> historyMessages = getRecentHistoryMessages(request.getSessionId());
MultiIntentRoutingResult routingResult = intentRoutingService.routeUnified(request.getMessage(), historyMessages);
```

这会导致 `IntentRoutingService` 实际走的是默认配置 Bean，而不是工程内统一注册装配的动态 `ChatClient`。

项目中已有统一装配获取方式：

```66:72:ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/AbstractExecuteSupport.java
public ChatClient getChatClientByClientId(String clientId, Integer taskType) {
    String key = AiAgentEnumVO.AI_CLIENT.getBeanName(clientId) + "taskType" + taskType;
    ChatClient chatClient = armoryObjectRegistry.get(key);
    if (chatClient == null) {
        throw new RuntimeException("ChatClient 未初始化，key: " + key);
    }
    return chatClient;
}
```

因此当前存在的核心问题是：

- `IntentRoutingNode` 已按流程配置驱动
- `IntentRoutingService` 却仍依赖默认 `ChatClient`
- 导致意图路由链路与工程统一组装链路不一致
- 也容易触发供应商兼容路径、默认模型配置不一致等问题

---

## 2. 目标

status: pass

将 `IntentRoutingService` 的 LLM 调用改造为**使用工程统一组装的 `ChatClient`**，同时**保留现有的 service 调用方式风格**。

目标效果：

1. `IntentRoutingNode` 仍负责主流程控制与上下文写入
2. `IntentRoutingService` 仍负责 Few-Shot 检索、Prompt 构造、LLM 调用、响应解析与降级
3. `IntentRoutingService` 不再依赖默认注入的 `ChatClient`
4. 意图路由调用改为按 `AiAgentClientFlowConfigVO.clientId` 动态获取统一装配的 `ChatClient`
5. 保留现有 `routeUnified(...)` 主链语义，仅在签名上补充配置参数
6. 增加兼容性测试，确保主链切换后：
   - 单任务上下文映射不变
   - 下游节点选择不变
   - few-shot 降级行为不变
   - 配置驱动 client 获取行为生效

---

## 3. 设计原则

status: pass

### 3.1 职责边界原则

status: pass

- `IntentRoutingNode` 负责流程控制、获取 `AiAgentClientFlowConfigVO`、收集历史消息、写入 `DynamicContext`
- `IntentRoutingService` 负责 Few-Shot 检索、Prompt 构造、LLM 调用、结果解析、异常降级
- 不把 Prompt/解析逻辑回退到 Node 中
- 不让 `IntentRoutingNode` 直接承担模型调用细节

### 3.2 调用风格兼容原则

status: pass

保留“由 Node 调用 Service”的现有主链风格，不改成“Node 自己拿 `ChatClient` 后直接 prompt 调用”。

因此推荐将 `AiAgentClientFlowConfigVO` 作为参数传入 `IntentRoutingService`，由 Service 内部通过统一装配方式解析出 `ChatClient`。

### 3.3 最小改动原则

status: pass

- 尽量不重构 Prompt / 解析逻辑
- 尽量不改下游路由判断逻辑
- 优先通过扩展现有 `routeUnified(...)` 签名解决问题
- 原有 `route(...)` / `doRoute(...)` 保留兼容，但内部也切换至统一获取 client 的方式，避免遗留默认注入链

---

## 4. 变更范围

status: pass

### 4.1 计划修改文件

status: pass

1. `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingService.java`
2. `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNode.java`
3. `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingServiceTest.java`
4. `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNodeTest.java`

### 4.2 非目标范围

status: pass

以下内容不在本次 Story 范围内：

- 改造 `IntentFewshotService` 检索逻辑
- 调整 `IntentRoutingPrompt` 提示词结构
- 修改 `MultiIntentRoutingResult` / `IntentRoutingResult` 数据结构
- 改造下游 `Step1AnalyzerNode`、`GeneralChatNode`、`IntelligentInspection`、交易节点逻辑
- 调整数据库表结构或新增远程依赖

---

## 5. 详细方案

status: pass

### 5.1 `IntentRoutingService`：去除默认 `ChatClient` 注入

status: pass

当前代码：

```33:37:ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingService.java
@Resource
private ChatClient chatClient;

@Resource
private IntentFewshotService intentFewshotService;
```

改造目标：

- 删除 `@Resource private ChatClient chatClient;`
- 保留 `IntentFewshotService` 注入
- 让 `IntentRoutingService` 具备 `getChatClientByClientId(...)` 能力

推荐做法：

- 让 `IntentRoutingService` 继承 `AbstractExecuteSupport`

即：

```java
public class IntentRoutingService extends AbstractExecuteSupport {
```

并补充抽象方法最小实现，避免语义误用：

```java
@Override
protected String doApply(ExecuteCommandEntity request,
                         DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
    throw new UnsupportedOperationException("IntentRoutingService 不支持策略节点执行");
}
```

说明：
- 这里不是把它当 Node 使用
- 只是复用统一装配获取 `ChatClient` 的基础能力
- 改动最小，且符合你“保留原来调用方式”的诉求

---

### 5.2 `IntentRoutingService.routeUnified(...)`：改为配置驱动 client 获取

status: pass

当前代码：

```63:75:ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingService.java
public MultiIntentRoutingResult routeUnified(String userMessage, List<String> historyMessages) {
    List<IntentFewshotSample> fewshotSamples = retrieveFewshotSamples(userMessage);
    String prompt = IntentRoutingPrompt.buildUnifiedRoutingPrompt(userMessage, historyMessages, fewshotSamples);
    try {
        String response = chatClient.prompt(prompt).call().content();
        log.debug("统一路由 LLM 原始响应: userMessage={}, response={}", userMessage, response);
        return parseUnifiedResponse(response);
    } catch (Exception e) {
        log.error("统一路由调用失败，降级为 GENERAL_CHAT: userMessage={}, error={}",
                userMessage, e.getMessage());
        return fallbackMultiIntentResult("LLM调用异常: " + e.getMessage());
    }
}
```

改造后签名：

```java
public MultiIntentRoutingResult routeUnified(String userMessage,
                                             List<String> historyMessages,
                                             AiAgentClientFlowConfigVO configVO)
```

改造后逻辑：

```java
List<IntentFewshotSample> fewshotSamples = retrieveFewshotSamples(userMessage);
String prompt = IntentRoutingPrompt.buildUnifiedRoutingPrompt(userMessage, historyMessages, fewshotSamples);
try {
    ChatClient chatClient = getChatClientByClientId(configVO.getClientId(), 0);
    String response = chatClient.prompt(prompt).call().content();
    log.debug("统一路由 LLM 原始响应: userMessage={}, clientId={}, response={}",
            userMessage, configVO.getClientId(), response);
    return parseUnifiedResponse(response);
} catch (Exception e) {
    log.error("统一路由调用失败，降级为 GENERAL_CHAT: userMessage={}, clientId={}, error={}",
            userMessage, configVO.getClientId(), e.getMessage());
    return fallbackMultiIntentResult("LLM调用异常: " + e.getMessage());
}
```

说明：
- `taskType` 先固定传 `0`，与当前意图路由场景保持一致
- 日志补充 `clientId`，便于排查是否命中正确配置链路

---

### 5.3 `IntentRoutingService` 兼容旧方法

status: pass

当前保留的旧方法：

```46:61:ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingService.java
String route(String userMessage, List<String> historyMessages) { ... }

IntentRoutingResult doRoute(String userMessage, String prompt) { ... }
```

兼容建议：

- `route(...)` 仍保留为纯 Prompt 构造方法，可不依赖 `ChatClient`
- `doRoute(...)` 增加 `AiAgentClientFlowConfigVO configVO` 参数
- 内部调用改为：

```java
ChatClient chatClient = getChatClientByClientId(configVO.getClientId(), 0);
String response = chatClient.prompt(prompt).call().content();
```

这样可以避免：
- 新主链修好了
- 旧兼容链却还偷偷走默认 `ChatClient`

---

### 5.4 `IntentRoutingNode`：改为透传 `AiAgentClientFlowConfigVO`

status: pass

当前代码：

```76:83:ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNode.java
AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap().get(AiClientTypeEnumVO.INTENT_ROUTING.getCode());
if (aiAgentClientFlowConfigVO == null) {
    throw new IllegalStateException("未找到任务分析客户端配置，aiAgentId=" + request.getAiAgentId()
            + "，请确认智能体流程配置中已添加 TASK_ANALYZER_CLIENT 类型的节点");
}

List<String> historyMessages = getRecentHistoryMessages(request.getSessionId());
MultiIntentRoutingResult routingResult = intentRoutingService.routeUnified(request.getMessage(), historyMessages);
```

改造后：

```java
AiAgentClientFlowConfigVO aiAgentClientFlowConfigVO = dynamicContext.getAiAgentClientFlowConfigVOMap()
        .get(AiClientTypeEnumVO.INTENT_ROUTING.getCode());
if (aiAgentClientFlowConfigVO == null) {
    throw new IllegalStateException("未找到意图路由客户端配置，aiAgentId=" + request.getAiAgentId()
            + "，请确认智能体流程配置中已添加 INTENT_ROUTING 类型的节点");
}

List<String> historyMessages = getRecentHistoryMessages(request.getSessionId());
MultiIntentRoutingResult routingResult = intentRoutingService.routeUnified(
        request.getMessage(),
        historyMessages,
        aiAgentClientFlowConfigVO
);
```

额外建议：
- 现有异常文案里写的是“任务分析客户端配置 / TASK_ANALYZER_CLIENT”
- 这里实际读的是 `INTENT_ROUTING`
- 建议顺手把错误文案修正，避免后续排查误导

---

## 6. 测试方案

status: pass

### 6.1 `IntentRoutingNodeTest`：适配新签名

status: pass

当前 mock 方式：

```106:111:ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNodeTest.java
when(intentRoutingService.routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(buildSingleTaskResult(IntentTypeEnum.PE_RETRIEVAL, "step1AnalyzerNode"));

verify(intentRoutingService).routeUnified(anyString(), org.mockito.ArgumentMatchers.anyList());
```

需要改为三参版本：

```java
when(intentRoutingService.routeUnified(
        anyString(),
        org.mockito.ArgumentMatchers.anyList(),
        org.mockito.ArgumentMatchers.any(AiAgentClientFlowConfigVO.class)))
        .thenReturn(...);

verify(intentRoutingService).routeUnified(
        anyString(),
        org.mockito.ArgumentMatchers.anyList(),
        org.mockito.ArgumentMatchers.any(AiAgentClientFlowConfigVO.class));
```

#### 新增兼容性断言
建议增加一个测试，验证 `Node` 透传的是 `INTENT_ROUTING` 对应配置，而不是别的 client 配置。

测试目标：
- `dynamicContext` 中同时存在多个 `AiAgentClientFlowConfigVO`
- 调用 `doApply(...)`
- 校验传给 `routeUnified(...)` 的 `configVO.clientId == "intent-routing-client"`

可命名为：

```java
should_pass_intent_routing_config_to_service_when_unified_routing_is_called
```

---

### 6.2 `IntentRoutingServiceTest`：从“字段注入 chatClient”切到“统一装配解析”

status: pass

当前 `setUp()`：

```46:53:ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingServiceTest.java
@Before
public void setUp() throws Exception {
    intentRoutingService = new IntentRoutingService();
    ChatClient chatClient = ChatClient.builder(chatModel).build();

    setField(intentRoutingService, "chatClient", chatClient);
    setField(intentRoutingService, "intentFewshotService", intentFewshotService);
}
```

这部分会失效，因为：
- 生产代码删除了 `chatClient` 字段注入
- 需要改为 mock 统一装配链路

#### 建议改法
在测试中 mock：
- `ArmoryObjectRegistry`
- `ChatModel`
- `ChatClient` 通过 `ChatClient.builder(chatModel).build()` 构建
- 将生成的 `ChatClient` 注册到 mock registry 返回

并通过反射给 `IntentRoutingService` 注入：
- `armoryObjectRegistry`
- `intentFewshotService`

#### 新增配置对象
统一准备一个：

```java
AiAgentClientFlowConfigVO configVO = AiAgentClientFlowConfigVO.builder()
        .clientId("intent-routing-client")
        .build();
```

后续 `routeUnified(...)` 调用改为：

```java
intentRoutingService.routeUnified("解释向量数据库", List.of(), configVO);
```

---

### 6.3 必加兼容性测试用例

status: pass

#### TC-301：统一路由使用配置驱动的 ChatClient
目标：
- 验证 `routeUnified(...)` 不再依赖默认字段注入
- 而是按 `configVO.clientId` 走统一装配获取的 `ChatClient`

断言：
- 给定有效 registry 映射时，`routeUnified(...)` 可成功返回结果
- 返回结果与原逻辑一致

#### TC-302：Few-Shot 检索失败时仍能通过统一装配 client 正常降级执行
目标：
- 保留原有 few-shot 降级兼容性

当前已有类似测试：

```196:228:ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingServiceTest.java
@Test
public void testRouteUnified_WhenFewshotFails_StillWorks() throws Exception {
    doThrow(new RuntimeException("vector store unavailable"))
            .when(intentFewshotService).retrieveTopK("解释向量数据库", 5);
    ...
    MultiIntentRoutingResult result = intentRoutingService.routeUnified("解释向量数据库", List.of());
    ...
}
```

改造后：
- 调用改为三参版
- 保持断言不变
- 确保 few-shot 失败不影响统一装配 client 链路

#### TC-303：当指定 client 未注册时统一降级为 fallback
目标：
- 验证 `getChatClientByClientId(...)` 抛错时，`routeUnified(...)` 能进入 catch 并返回 fallback 结果
- 避免因为注册缺失导致整个主链抛未捕获异常

建议断言：
- `result.getTaskList().size() == 1`
- `result.getTaskList().get(0).getIntent() == GENERAL_CHAT`
- `result.getReasoning()` 包含 `LLM调用异常`

#### TC-304：Node 主链上下文兼容性不变
现有测试已经覆盖一部分：

```219:230:ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNodeTest.java
public void should_keep_single_task_context_mapping_compatible_after_unified_routing() throws Exception {
    ...
}
```

本次改造后应继续保留，并适配新签名，验证：
- `RECOGNIZED_INTENT_KEY`
- `ROUTING_RESULT_KEY`
- `BASE_SLOT_KEY`
- `INTENT_SPECIFIC_SLOTS_KEY`

不受统一装配 client 改造影响

#### TC-305：Node 下游路由选择兼容性不变
现有测试：

```233:243:ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/routing/IntentRoutingNodeTest.java
public void should_keep_downstream_node_selection_unchanged_after_mainline_switch() throws Exception {
    ...
}
```

继续保留并适配新签名，确保：
- `PE_REASONING -> step1AnalyzerNode`
- `GENERAL_CHAT -> generalChatNode`
- `INSPECTION -> intelligentInspection`
- `STOCK_ANALYSIS -> tradingIntentRoutingNode / fallback generalChatNode`

行为不变

---

## 7. 验证方式

status: pass

代码完成后需要至少执行：

```bash
mvn -pl ai-agent-study-domain -Dtest=IntentRoutingServiceTest,IntentRoutingNodeTest test
```

如测试依赖模块联编，也可改为：

```bash
mvn -pl ai-agent-study-domain -am -Dtest=IntentRoutingServiceTest,IntentRoutingNodeTest test
```

验证通过标准：

1. `IntentRoutingServiceTest` 全部通过
2. `IntentRoutingNodeTest` 全部通过
3. 新增兼容性测试全部通过
4. 无新增编译错误
5. 无新增近期编辑文件 lint 问题

---

## 8. 任务清单

status: pass

### Task 1：改造 `IntentRoutingService` 统一装配 `ChatClient`
status: pass

- 删除默认 `ChatClient` 字段注入
- 继承 `AbstractExecuteSupport`
- 增加抽象方法最小实现
- 调整 `routeUnified(...)` 与 `doRoute(...)` 签名及内部实现

### Task 2：调整 `IntentRoutingNode` 透传 `AiAgentClientFlowConfigVO`
status: pass

- 调整 `routeUnified(...)` 调用
- 修正异常文案中的 client type 描述

### Task 3：补齐 `IntentRoutingServiceTest`
status: pass

- 改为 mock 统一装配链路
- 适配 `routeUnified(...)` 新签名
- 新增 client 未注册 fallback 测试
- 保留 few-shot 降级兼容性验证

### Task 4：补齐 `IntentRoutingNodeTest`
status: pass

- 适配 `routeUnified(...)` 新签名
- 新增配置透传正确性验证
- 保留上下文兼容与下游路由兼容测试

---

## 9. 风险与取舍

status: pass

### 风险 1：`IntentRoutingService` 继承 `AbstractExecuteSupport` 的语义不够纯
说明：
- 这是一个 service 继承节点基类的折中方案

取舍：
- 优点是改动最小，最贴近你“保留原调用方式”的目标
- 缺点是架构上不如独立 resolver 干净

本次建议接受该取舍，后续如需要可再抽 `ChatClientResolver`

### 风险 2：单测从字段注入切换到统一装配 mock 后，初始化方式变化较大
说明：
- `IntentRoutingServiceTest` 改动会比 NodeTest 大

取舍：
- 这是必要调整，目的是让测试方式与生产链路一致

---

## 10. 结论

status: pass

本次改造推荐采用以下最终方案：

- `IntentRoutingService` 继承 `AbstractExecuteSupport`
- 去除默认注入 `ChatClient`
- `routeUnified(...)` / `doRoute(...)` 增加 `AiAgentClientFlowConfigVO configVO`
- 内部统一通过 `getChatClientByClientId(configVO.getClientId(), 0)` 获取模型客户端
- `IntentRoutingNode` 继续保留主流程控制，只透传 `configVO`
- 补充兼容性测试，确保主链行为与上下文语义保持稳定
