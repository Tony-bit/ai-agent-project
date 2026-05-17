# 意图路由 Tool 调用修复 — 测试场景文档

**文档版本:** v1.0
**创建日期:** 2026-05-17
**关联需求文档:** `docs/superpowers/plans/2026-05-17-intent-routing-tool-fix.md`
**状态:** draft

---

## 1. 测试范围概述

### 1.1 测试目标

验证 `IntentRoutingNode` 中 LLM 能够正确调用 `search_stock_by_name` tool，实现稳定地将中文公司名转换为股票代码。

### 1.2 核心修复点

| 修复点 | 描述 | 影响范围 |
|-------|------|---------|
| `.call().content()` → `stream()` | 启用 LLM Tool 执行能力 | IntentRoutingNode |
| Prompt 精简 | 明确 tool 调用流程 | IntentRoutingPrompt |
| `extractCompanyName` 增强 | 2-4 字符长度校验，前缀去除 | IntentRoutingNode |
| Java 兜底逻辑 | ticker 为 null 时的搜索保障 | IntentRoutingNode + TradingIntentRoutingService |

### 1.3 测试模块映射

| 测试类/文件 | 测试类型 | 所属模块 |
|------------|---------|---------|
| `IntentRoutingNodeTest` | 单元测试 | trading-domain |
| `TradingIntentRoutingServiceTest` | 单元测试 | trading-domain |
| `TushareSearchByNameIntegrationTest` | 集成测试 | trading-infra |
| `IntentRoutingNodeE2ETest` | 端到端测试 | trading-api |

---

## 2. 单元测试场景

### 2.1 `IntentRoutingNode` 单元测试

**测试文件:** `IntentRoutingNodeTest.java`
**测试路径:** `ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/IntentRoutingNodeTest.java`

#### 2.1.1 `extractCompanyName` 方法测试

| 用例编号 | 用例名称 | 输入 | 期望输出 | 优先级 |
|---------|---------|------|---------|-------|
| UT-ECN-001 | 完整公司名-4字 | "帮我分析一下湖南裕能" | "湖南裕能" | P0 |
| UT-ECN-002 | 完整公司名-3字 | "分析一下贵州茅台" | "贵州茅台" | P0 |
| UT-ECN-003 | 完整公司名-2字 | "看看宁德时代" | "宁德时代" | P0 |
| UT-ECN-004 | 无前缀公司名 | "比亚迪最近怎么样" | "比亚迪" | P0 |
| UT-ECN-005 | 嵌套前缀 | "帮我看看工商银行" | "工商银行" | P0 |
| UT-ECN-006 | 带"的股票"后缀 | "分析一下中国平安的股票" | "中国平安" | P0 |
| UT-ECN-007 | 带"怎么样"后缀 | "帮我查一下药明康德怎么样" | "药明康德" | P0 |
| UT-ECN-008 | 公司名过长拒绝 | "帮我分析一下腾讯控股" | null | P0 |
| UT-ECN-009 | 公司名过短拒绝 | "帮我分析一下A" | null | P0 |
| UT-ECN-010 | 空字符串输入 | "" | null | P1 |
| UT-ECN-011 | null 输入 | null | null | P1 |
| UT-ECN-012 | 只有前缀无公司名 | "帮我分析一下" | null | P1 |
| UT-ECN-013 | 英文公司名 | "分析一下Tencent" | null（不做处理） | P2 |
| UT-ECN-014 | 混合前缀+后缀 | "请帮我看看宁德时代如何" | "宁德时代" | P0 |
| UT-ECN-015 | 前缀长度排序验证 | "我想了解招商银行" | "招商银行" | P1 |

**边界值分析:**
- 最小有效长度: 2 字符
- 最大有效长度: 4 字符
- 边界值: 1 字符（拒绝）、2 字符（通过）、4 字符（通过）、5 字符（拒绝）

#### 2.1.2 `handleStockAnalysisIntent` ticker 兜底逻辑测试

| 用例编号 | 用例名称 | 前置条件 | LLM返回ticker | Java搜索结果 | 最终ticker |
|---------|---------|---------|--------------|-------------|-----------|
| UT-HSI-001 | LLM成功-Java不触发 | LLM 返回 ticker=301358 | "301358" | 不调用 | "301358" |
| UT-HSI-002 | LLM失败-Java成功 | LLM 返回 ticker=null | null | "301358" | "301358" |
| UT-HSI-003 | LLM失败-Java失败 | LLM 返回 ticker=null | null | null | null + 警告日志 |
| UT-HSI-004 | 消息提取失败-Java不触发 | 无法提取公司名 | null | 不调用 | null |
| UT-HSI-005 | GENERAL_CHAT意图-不触发Java兜底 | intent=GENERAL_CHAT | null | 不调用 | null |

#### 2.1.3 `doApply` 方法 Tool 调用测试

| 用例编号 | 用例名称 | Mock 策略 | 验证点 |
|---------|---------|----------|-------|
| UT-APP-001 | stream()触发Tool调用 | Mock ChatClient.stream()，模拟tool返回 | 验证 tool search_stock_by_name 被调用 |
| UT-APP-002 | Tool返回结果被正确处理 | Mock tool返回 "301358" | 验证最终JSON中ticker="301358" |
| UT-APP-003 | Tool调用异常-降级到Java兜底 | Mock tool抛出异常 | 验证降级到Java搜索逻辑 |
| UT-APP-004 | 临时ChatMemory隔离性 | 连续两次请求 | 验证两次请求的ChatMemory不互相污染 |

---

### 2.2 `TradingIntentRoutingService` 单元测试

**测试文件:** `TradingIntentRoutingServiceTest.java`
**测试路径:** `ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/service/TradingIntentRoutingServiceTest.java`

#### 2.2.1 `searchTickerByName` 方法测试

| 用例编号 | 用例名称 | 输入 | Mock返回 | 期望输出 |
|---------|---------|------|---------|---------|
| UT-STN-001 | 精确匹配 | "湖南裕能" | 单条结果 301358 | "301358" |
| UT-STN-002 | 模糊匹配前缀 | "湖南" | 多条结果 | 返回第一条的ticker |
| UT-STN-003 | 搜索结果为空 | "不存在的公司XYZ" | 空列表 | null |
| UT-STN-004 | 搜索结果多条-取第一条 | "平安" | 平安银行、平安好医生等 | 第一条的ticker |
| UT-STN-005 | Tushare API异常 | "贵州茅台" | 抛出RemoteException | null + 错误日志 |
| UT-STN-006 | 返回结果无ticker字段 | "公司A" | ticker=null | null |

---

## 3. 集成测试场景

### 3.1 Tushare 按名称搜索集成测试

**测试文件:** `TushareSearchByNameIntegrationTest.java`
**测试路径:** `ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareSearchByNameIntegrationTest.java`

| 用例编号 | 用例名称 | 输入 | 验证点 | 优先级 |
|---------|---------|------|-------|-------|
| IT-SBN-001 | 4字公司名-湖南裕能 | "湖南裕能" | ticker=301358, name="湖南裕能" | P0 |
| IT-SBN-002 | 3字公司名-贵州茅台 | "贵州茅台" | ticker=600519 | P0 |
| IT-SBN-003 | 4字公司名-宁德时代 | "宁德时代" | ticker=300750 | P0 |
| IT-SBN-004 | 模糊匹配-前缀 | "宁德" | 结果包含宁德时代 | P1 |
| IT-SBN-005 | 模糊匹配-中缀 | "时代" | 结果包含宁德时代 | P2 |
| IT-SBN-006 | 上市公司-工商银行 | "工商银行" | ticker=601398 | P1 |
| IT-SBN-007 | 科创板股票 | "中芯国际" | ticker=688981 | P1 |
| IT-SBN-008 | 无搜索结果 | "虚构不存在的公司" | 返回空列表 | P1 |
| IT-SBN-009 | 创业板股票 | "宁德时代" | ticker=300750（深交所） | P1 |
| IT-SBN-010 | 特殊字符处理 | "贵州茅台  "（带空格） | 正常返回结果 | P2 |

### 3.2 LLM Tool 调用集成测试

**测试文件:** `IntentRoutingNodeToolCallIntegrationTest.java`
**测试路径:** `ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/node/IntentRoutingNodeToolCallIntegrationTest.java`

| 用例编号 | 用例名称 | 测试步骤 | 验证点 |
|---------|---------|---------|-------|
| IT-LLM-001 | 完整Tool调用流程 | 1. 发送 "帮我分析一下湖南裕能"<br>2. 验证 tool search_stock_by_name 被调用<br>3. 验证返回结果包含 ticker | tool被调用次数=1<br>ticker=301358 |
| IT-LLM-002 | Tool返回多结果处理 | 1. 发送 "帮我看看平安"<br>2. 验证 tool 被调用<br>3. 验证选择第一条结果 | tool返回多条，取第一条 |
| IT-LLM-003 | 意图分类-GENERAL_CHAT | 1. 发送 "今天天气怎么样"<br>2. 验证 tool 未被调用<br>3. 验证 intent=GENERAL_CHAT | tool调用次数=0 |
| IT-LLM-004 | 意图分类-STOCK_ANALYSIS | 1. 发送 "分析一下比亚迪"<br>2. 验证 tool 被调用<br>3. 验证 intent=STOCK_ANALYSIS | tool调用次数≥1 |
| IT-LLM-005 | 置信度-HIGH | 发送明确的股票分析请求 | confidence=HIGH |
| IT-LLM-006 | 置信度-MEDIUM | 发送模糊的股票分析请求 | confidence=MEDIUM或LOW |
| IT-LLM-007 | 分析类型提取-FUNDAMENTAL | 发送 "分析宁德时代的财务状况" | analysisType=FUNDAMENTAL |
| IT-LLM-008 | 分析类型提取-TECHNICAL | 发送 "看看比亚迪的K线走势" | analysisType=TECHNICAL |
| IT-LLM-009 | 分析类型提取-ALL | 发送 "帮我全面分析一下贵州茅台" | analysisType=ALL |

---

## 4. 端到端测试场景

### 4.1 完整流程 E2E 测试

**测试文件:** `IntentRoutingE2ETest.java`
**测试路径:** `ai-agent-study-trading-api/src/test/java/denny/ai/agent/trading/api/IntentRoutingE2ETest.java`

| 用例编号 | 用例名称 | 输入消息 | 验证点 |
|---------|---------|---------|-------|
| E2E-001 | 基础流程-4字公司 | "帮我分析一下湖南裕能" | ticker=301358, intent=STOCK_ANALYSIS, 无异常 |
| E2E-002 | 基础流程-3字公司 | "分析一下贵州茅台" | ticker=600519 |
| E2E-003 | 基础流程-2字公司 | "看看比亚迪" | ticker=002594 |
| E2E-004 | 带后缀消息 | "帮我查一下药明康德怎么样" | ticker=603259 |
| E2E-005 | 闲聊意图 | "给我讲个笑话" | intent=GENERAL_CHAT |
| E2E-006 | 未知意图 | "随机文本xyz123" | intent=UNKNOWN |
| E2E-007 | 多轮对话历史 | 用户A问"湖南裕能"→用户B问"继续分析" | 第二轮仍能正确识别ticker |
| E2E-008 | 带股票代码输入-直接提供ticker | "分析一下600519" | ticker=600519（无需tool调用） |

### 4.2 异常场景 E2E 测试

| 用例编号 | 用例名称 | 输入消息 | 异常注入方式 | 期望行为 |
|---------|---------|---------|-------------|---------|
| E2E-EX-001 | Tool调用超时 | "帮我分析一下湖南裕能" | Mock tool调用超时 | 降级到Java兜底搜索 |
| E2E-EX-002 | Tool返回空结果 | "帮我分析一下某不存在的公司" | Mock tool返回空 | Java兜底也失败，输出ticker=null但无崩溃 |
| E2E-EX-003 | LLM响应格式错误 | "帮我分析一下湖南裕能" | Mock LLM返回非JSON | 记录错误日志，返回UNKNOWN |
| E2E-EX-004 | Tushare API不可用 | "帮我分析一下贵州茅台" | Mock Tushare异常 | 降级处理，记录警告日志 |
| E2E-EX-005 | 公司名无法提取 | "帮我分析一下腾讯控股有限公司" | 正常流程 | 提取失败，返回null，输出警告日志 |

---

## 5. 回归测试场景

### 5.1 原有功能回归

| 用例编号 | 用例名称 | 描述 | 验证点 |
|---------|---------|------|-------|
| REG-001 | 股票分析意图识别 | 原有的股票分析识别功能 | intent 识别准确 |
| REG-002 | 闲聊意图识别 | 原有的闲聊识别功能 | intent=GENERAL_CHAT |
| REG-003 | 置信度计算 | 原有的置信度评估 | confidence 在合理范围 |
| REG-004 | 分析类型提取 | 原有的 analysisType 提取 | FUNDAMENTAL/TECHNICAL 等正确 |
| REG-005 | 直接提供ticker | 用户直接提供股票代码 | ticker 直接使用，无需搜索 |
| REG-006 | JSON输出格式 | 验证输出符合预期JSON Schema | 格式正确，字段完整 |

### 5.2 历史问题修复验证

| 用例编号 | 问题描述 | 验证方式 |
|---------|---------|---------|
| BUG-001 | ticker不能为空的Crash | 发送 "帮我分析一下湖南裕能"，确认无 IllegalArgumentException |
| BUG-002 | extractCompanyName 包含前缀 | 验证提取结果不包含 "帮我分析一下" 等前缀 |

---

## 6. 测试数据准备

### 6.1 测试股票数据

| 股票名称 | 股票代码 | 交易所 | 用途 |
|---------|---------|-------|------|
| 湖南裕能 | 301358 | 深交所-创业板 | 主要测试用例，4字公司名 |
| 贵州茅台 | 600519 | 上交所-主板 | 3字公司名 |
| 宁德时代 | 300750 | 深交所-创业板 | 4字公司名 |
| 比亚迪 | 002594 | 深交所-中小板 | 2字公司名 |
| 药明康德 | 603259 | 上交所-科创板 | 带后缀测试 |
| 工商银行 | 601398 | 上交所-主板 | 4字公司名 |
| 中国平安 | 601318 | 上交所-主板 | 4字公司名 |
| 中芯国际 | 688981 | 上交所-科创板 | 科创板股票 |

### 6.2 测试消息模板

| 模板类型 | 示例消息 |
|---------|---------|
| 基础请求 | "帮我分析一下{公司名}" |
| 简短请求 | "分析{公司名}" |
| 查看请求 | "看看{公司名}怎么样" |
| 查询请求 | "帮我查一下{公司名}" |
| 直接ticker | "分析一下{股票代码}" |
| 带后缀 | "帮我分析一下{公司名}的财务状况" |

---

## 7. 测试执行计划

### 7.1 执行顺序

```
单元测试 (Mock) → 集成测试 (真实组件) → 端到端测试 (完整流程)
```

### 7.2 执行命令

| 测试类型 | 执行命令 | 预期结果 |
|---------|---------|---------|
| 单元测试 | `mvn test -pl ai-agent-study-trading-domain -Dtest=IntentRoutingNodeTest,TradingIntentRoutingServiceTest` | 全部通过 |
| 集成测试 | `mvn test -pl ai-agent-study-trading-infra -Dtest=TushareSearchByNameIntegrationTest,IntentRoutingNodeToolCallIntegrationTest` | 全部通过 |
| 端到端测试 | `mvn test -pl ai-agent-study-trading-api -Dtest=IntentRoutingE2ETest` | 全部通过 |
| 全量测试 | `mvn test -pl ai-agent-study-trading-domain,ai-agent-study-trading-infra,ai-agent-study-trading-api` | 全部通过 |

### 7.3 验收标准

| 阶段 | 验收标准 |
|-----|---------|
| 单元测试 | 通过率 100%，覆盖率 ≥ 80% |
| 集成测试 | 通过率 100%，Tushare 真实调用成功 |
| 端到端测试 | `帮我分析一下湖南裕能` 返回 ticker=301358，无异常 |
| 回归测试 | 原有功能无退化 |

---

## 8. 测试文档清单

| 文档名称 | 路径 | 状态 |
|---------|------|------|
| 本测试场景文档 | `docs/superpowers/test/2026-05-17-intent-routing-tool-fix-test-plan.md` | draft |
| `IntentRoutingNodeTest.java` | 待创建 | pending |
| `TradingIntentRoutingServiceTest.java` | 待创建 | pending |
| `TushareSearchByNameIntegrationTest.java` | 待创建 | pending |
| `IntentRoutingNodeToolCallIntegrationTest.java` | 待创建 | pending |
| `IntentRoutingE2ETest.java` | 待创建 | pending |

---

## 9. 附录

### 9.1 原问题现象（用于回归验证）

- **Bug 现象:** 用户发送"帮我分析一下湖南裕能"，系统报错 `IllegalArgumentException: ticker 不能为空`，后续流程崩溃。
- **根因 1:** `.call().content()` 不触发 tool 执行，LLM 生成 ticker=null
- **根因 2:** `extractCompanyName` 返回整个消息（未正确去除前缀），传给 Tushare 搜索失败

### 9.2 修复验证检查清单

- [ ] Bug 现象不再复现
- [ ] `帮我分析一下湖南裕能` 正确返回 ticker=301358
- [ ] `extractCompanyName("帮我分析一下湖南裕能")` 返回 "湖南裕能"
- [ ] `extractCompanyName("帮我分析一下腾讯控股")` 返回 null（4字以上）
- [ ] Tool 调用日志出现在日志中
- [ ] 无 `IllegalArgumentException: ticker 不能为空` 异常
- [ ] 原有闲聊意图识别功能正常
