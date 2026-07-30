# 会话记忆 Redis 异步写入实现计划

> **致实现人员：** 在当前会话中按以下步骤完成并验证。

**目标：** 将会话记忆的 Redis 写入移出业务调用线程，避免同步网络调用阻塞交易流程。

**架构：** 保持 `IChatMemoryRepository` 接口不变，在 Spring 管理的仓储实现写入口上使用 `@Async("threadPoolExecutor")`。复用应用现有的有界线程池，仓储内部继续捕获并记录 Redis 写入异常。

**技术栈：** Java、Spring Framework `@Async`、JUnit 5、Maven

---

### 任务 1：锁定 Redis 写入口的异步契约

| 任务 | status |
|------|------|
| 任务 1：锁定 Redis 写入口的异步契约 | pass |

**文件：**
- 创建：`ai-agent-study-infrastructure/src/test/java/denny/ai/agent/infrastructure/adapter/repository/ChatMemoryRepositoryAsyncTest.java`

- [ ] 编写反射测试，检查 `cacheMessagesToRedis(...)` 和 `cacheRuntimeWindowToRedis(...)` 上存在 `@Async`，且值为 `threadPoolExecutor`。
- [ ] 运行 `mvn -pl ai-agent-study-infrastructure -am -Dtest=ChatMemoryRepositoryAsyncTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认测试因缺少注解而失败。

### 任务 2：异步执行 Redis 写入

| 任务 | status |
|------|------|
| 任务 2：异步执行 Redis 写入 | pass |

**文件：**
- 修改：`ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/adapter/repository/ChatMemoryRepository.java`

- [ ] 导入 `org.springframework.scheduling.annotation.Async`。
- [ ] 在两个 Redis 写入口添加 `@Async("threadPoolExecutor")`，不修改读、删和 MySQL 方法。
- [ ] 重新运行定向测试，确认通过。
- [ ] 运行基础设施模块编译和现有会话记忆测试，确认无回归。

### 任务 3：核对最终变更

| 任务 | status |
|------|------|
| 任务 3：核对最终变更 | pass |

**文件：**
- 检查：`ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/adapter/repository/ChatMemoryRepository.java`
- 检查：`ai-agent-study-infrastructure/src/test/java/denny/ai/agent/infrastructure/adapter/repository/ChatMemoryRepositoryAsyncTest.java`

- [ ] 检查差异只包含异步注解、契约测试和本次设计/计划文档。
- [ ] 确认未覆盖工作区中已有的用户修改。
