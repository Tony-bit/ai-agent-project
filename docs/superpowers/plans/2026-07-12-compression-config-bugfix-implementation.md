# 强制上下文压缩配置修复实施计划

> **执行要求：** 使用 `executing-plans` 按任务顺序实施，复选框用于记录步骤状态。

**目标：** 补齐强制上下文压缩的生产配置与 armory 装配闭环，同时兼容旧版 Retry JSON。

**架构：** 将重试与压缩参数作为同一模型扩展配置解析，并提供强制压缩默认值。armory 加载唯一的系统级压缩客户端并注册稳定别名；所有业务模型均由 RetryChatModel 包装，压缩调用优先从 Registry、其次从 Spring 获取该别名。

**技术栈：** Java 17、Spring Boot/Spring AI、Fastjson、MyBatis、JUnit 5、Mockito、Maven。

---

### 任务一：配置契约与校验

| Task | status |
|------|------|
| 任务一：配置契约与校验 | pass |

**Files:**
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/model/valobj/AiClientModelVO.java`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/element/CompressionPolicy.java`
- Modify: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/adapter/repository/AgentRepository.java`
- Test: `ai-agent-study-infrastructure/src/test/java/denny/ai/agent/infrastructure/adapter/repository/AgentRepositoryCompressionConfigTest.java`
- Test: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/CompressionConfigTest.java`

- [x] 补充空值、复合结构、旧扁平结构和非法 `ext_param` 测试。
- [x] 验证旧实现不满足新契约。
- [x] 删除压缩开关和模型 ID，加入默认配置、兼容解析及显式参数校验。
- [x] 配置专项测试通过。

### 任务二：全局压缩助手加载

| Task | status |
|------|------|
| 任务二：全局压缩助手加载 | pass |

**Files:**
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/adapter/repository/IAgentRepository.java`
- Modify: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/dao/IAiAgentFlowConfigDao.java`
- Modify: `ai-agent-study-infrastructure/src/main/resources/mapper/AiAgentFlowConfigMapper.xml`
- Modify: `ai-agent-study-infrastructure/src/main/java/denny/ai/agent/infrastructure/adapter/repository/AgentRepository.java`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/business/data/impl/AiClientLoadDataStrategy.java`
- Test: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/business/data/impl/AiClientLoadDataStrategyTest.java`

- [x] 覆盖零个、同 ID 多条、多个不同 ID 及 command 列表不可变场景。
- [x] 增加不使用 `status` 字段的 clientType Repository/DAO 查询。
- [x] 写入 `globalCompressionClientId`，使用副本合并加载范围并校验唯一性。
- [x] 数据加载策略专项测试通过。

### 任务三：稳定 Registry 别名与默认提示词

| Task | status |
|------|------|
| 任务三：稳定 Registry 别名与默认提示词 | pass |

**Files:**
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/factory/ArmoryObjectRegistry.java`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiClientNode.java`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/armory/AiClientModelNode.java`
- Modify: `ai-agent-study-domain/src/main/java/denny/ai/agent/domain/service/compression/DefaultPromptCompressionService.java`
- Test: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/AiClientNodeCompressionTest.java`
- Test: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/compression/DefaultPromptCompressionServiceTest.java`

- [x] 覆盖 clientId/taskType 精确匹配、别名冲突、Registry 优先和 Spring 回退。
- [x] 原子注册 `compressionChatClient` 及不可变的全局 clientId，并校验真实 Registry。
- [x] 恢复代码默认提示词，所有模型均获得强制 policy 和 RetryChatModel 包装。
- [x] 压缩客户端按 Registry → Spring 顺序解析，缺失时报明确异常。
- [x] 节点及压缩服务专项测试通过。

### 任务四：Query 闭环与回归验证

| Task | status |
|------|------|
| 任务四：Query 闭环与回归验证 | pass |

**Files:**
- Modify: `ai-agent-study-domain/src/test/java/denny/ai/agent/domain/service/armory/CompressionRetryIntegrationTest.java`
- Modify: `docs/superpowers/test/2026-07-12-compression-config-bugfix-test.md`

- [x] 使用真实 holder/状态机和 Mock LLM 输出覆盖主动阈值及 1261 被动恢复。
- [x] 专项测试确认 Prompt 调用顺序、响应一致性和上下文清理。
- [x] `mvn -pl ai-agent-study-domain clean test` 零失败。
- [x] `mvn clean compile -DskipTests` 编译成功。
- [x] 已完成任务和可验证测试状态更新为 `pass`。
