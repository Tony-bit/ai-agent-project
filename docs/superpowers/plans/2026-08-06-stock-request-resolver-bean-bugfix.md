# StockRequestResolver Bean 缺失修复实施计划

> **致智能体工作者：** 使用 `executing-plans` 技能逐步实施本计划，并用复选框跟踪步骤。

**目标：** 补齐 `StockRequestResolver` 的 Spring 装配，恢复应用上下文启动，并用配置测试防止回归。

**架构：** 保持 domain 类不依赖组件扫描，由 `ai-agent-study-app` 组合领域服务、基础设施仓储和统一时钟。配置测试显式提供外部依赖，验证完整 Bean 图。

**技术栈：** Java、Spring Boot、JUnit 5、`ApplicationContextRunner`、Maven

---

### 任务 1：补齐配置测试

| 任务 | status |
|------|------|
| 任务 1：补齐配置测试 | pass |

**文件：**
- 修改：`ai-agent-study-app/src/test/java/denny/ai/agent/config/StockNameIndexConfigurationTest.java`

- [x] 为测试上下文提供 `StockResolutionPendingRepository` 和 `AnalysisDepthFollowUpResolver`。
- [x] 断言上下文中存在 `StockRequestResolver`。
- [x] 运行配置测试并确认当前因 Bean 缺失而失败。

### 任务 2：注册 StockRequestResolver

| 任务 | status |
|------|------|
| 任务 2：注册 StockRequestResolver | pass |

**文件：**
- 修改：`ai-agent-study-app/src/main/java/denny/ai/agent/config/StockNameIndexConfiguration.java`

- [x] 添加 `StockRequestResolver`、`AnalysisDepthFollowUpResolver` 和 `StockResolutionPendingRepository` 导入。
- [x] 新增 `stockRequestResolver(...)` Bean 方法，复用 `stockNameIndexClock`。
- [x] 运行配置测试并确认通过。
- [x] 运行相关模块测试，确认没有 Spring 装配回归。
