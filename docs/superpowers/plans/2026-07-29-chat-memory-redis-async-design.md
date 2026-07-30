# 会话记忆 Redis 异步写入设计

## 背景

`ChatMemoryRepository.cacheRuntimeWindowToRedis(...)` 当前通过同步 `StringRedisTemplate` 写入 Redis。Spring AI 在保存运行时会话窗口时会直接等待 Redis 网络调用完成，日志显示该调用运行在 `boundedElastic` 业务线程上，已经阻塞交易分析流程。

## 目标

- Redis 会话消息写入不再阻塞调用线程。
- 保持 Redis 读取、删除、MySQL 持久化和现有异常处理语义不变。
- 采用项目已有的 `threadPoolExecutor`，不新增线程池和配置项。

## 方案

在 `ChatMemoryRepository` 的两个 Redis 写入口 `cacheMessagesToRedis(...)` 和 `cacheRuntimeWindowToRedis(...)` 上增加 `@Async("threadPoolExecutor")`。

外部 Bean 通过 `IChatMemoryRepository` 调用时会经过 Spring 异步代理，调用线程只负责提交任务，实际序列化和 Redis 写入在 `threadPoolExecutor` 中完成。`cacheMessagesToRedis(...)` 内部对 `cacheRuntimeWindowToRedis(...)` 的类内调用不会再次经过代理，但整个方法已经运行在异步线程中，因此不会重复提交任务。

## 错误处理

保留仓储方法现有的 `try/catch` 和错误日志。Redis 异步写失败不会反向中断业务流程，也不增加重试。

## 验证

- 反射检查两个 Redis 写入口均声明 `@Async("threadPoolExecutor")`。
- 运行基础设施模块相关测试并编译受影响模块。

## 范围限制

本次不处理任务排队、拒绝策略、重试、同一会话写入顺序和服务关闭时的任务排空。这些能力需要独立的可靠异步写入设计，不纳入本次紧急解阻。
