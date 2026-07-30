# Trading 流式调用输入输出调试日志实现计划

> **致智能体工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 来按任务逐步实现本计划。步骤使用复选框（`- [ ]`）语法进行跟踪。

**目标：** 在本地开启 `DEBUG` 时记录 Trading 聚合式 LLM 调用的完整 Prompt，并在流正常完成后一次性记录完整响应。

**架构：** 扩展 `AbstractExecuteSupport.collectStreamingResponse()` 的参数，使公共入口获得调用节点已经构建完成的 Prompt；12 个 Trading 节点只负责传参。公共入口在聚合前后分别记录输入和输出，异常路径不记录残缺输出。

**技术栈：** Java、SLF4J、Spring AI、Reactor、Maven

---

### 任务 1：增加集中式 DEBUG 内容日志

| 任务 | status |
|------|------|
| 任务 1：增加集中式 DEBUG 内容日志 | pass |

**文件：**
- 修改：`ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/auto/step/AbstractExecuteSupport.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/*Node.java` 中当前 12 个 `collectStreamingResponse()` 调用点
- 验证：`ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/auto/step/StreamingChatResponseCollectorTest.java`

- [x] **步骤 1：扩展公共方法并记录完整输入输出**

将签名扩展为：

```java
protected String collectStreamingResponse(ChatClient.ChatClientRequestSpec requestSpec,
                                          String operationName,
                                          String inputContent,
                                          SseEventSink sseEventSink)
```

在聚合调用前后加入：

```java
if (log.isDebugEnabled()) {
    log.debug("LLM streaming input | operation={} | content=\n{}", operationName, inputContent);
}
String response = collector.collect(requestSpec.stream().content(), operationName,
        sseEventSink == null ? null : sseEventSink.cancellationSignal());
if (log.isDebugEnabled()) {
    log.debug("LLM streaming output | operation={} | content=\n{}", operationName, response);
}
return response;
```

- [x] **步骤 2：更新 12 个 Trading 调用点**

每个节点把已经用于构造 `requestSpec` 的最终 `prompt` 作为第三个参数传入，例如：

```java
collectStreamingResponse(requestSpec, "BearResearcherNode", prompt, sseEventSink)
```

- [x] **步骤 3：运行针对性验证**

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am `
  -Dtest=StreamingChatResponseCollectorTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：测试通过，并且编译确认全部 12 个调用点适配新签名。

- [x] **步骤 4：检查变更范围**

运行：

```powershell
git diff --check
git diff --stat
```

预期：没有空白错误；代码变更仅包含公共入口、12 个调用点和本计划状态。
