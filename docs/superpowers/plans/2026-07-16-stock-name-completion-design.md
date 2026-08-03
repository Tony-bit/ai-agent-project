# A股股票名称补全与定时缓存设计

## 状态

- 日期：2026-07-16
- 状态：待书面审阅
- 对应 Story：`docs/trading-agent/2026-07-16-stock-name-completion-story.md`

## 已确认决策

1. 使用 Tushare `stock_basic(list_status=L)` 全量获取当前上市股票。
2. 权威缓存记录只保存股票名称和标准交易所代码，例如
   `北方华创 -> 002371.SZ`。
3. 使用本地持久化快照和 JVM 内存不可变索引；刷新时原子替换，不拆成约 6,000 个
   独立 Caffeine 条目。
4. 支持精确、前缀、后缀和任意位置连续子串匹配；前后缀基础权重相同。
5. 采用“候选检索 + 受限 Agent 判断”：Agent 只能选择本地候选中的代码。
6. 精确名称、精确别名或唯一子串候选自动补全；多个普通子串候选无明确上下文时向用户追问。
7. 每天定时刷新，启动时先加载快照再刷新；刷新失败保留上一版。
8. 第一版不支持拼音、错别字、编辑距离、向量检索或退市股票。

## 架构

```text
Tushare stock_basic(list_status=L)
  -> StockNameRefreshService
  -> validate(name, tsCode, uniqueness, non-empty)
  -> build immutable StockNameIndex
  -> atomically write local snapshot
  -> atomically publish in-memory index

User query / entityMention
  -> normalize
  -> exact name / exact alias / contains search
  -> ranked candidate set
  -> search_stock_by_name tool
  -> Intent Router constrained selection
       -> unique or clear: START_TRADING_ANALYSIS
       -> ambiguous: ASK_DISAMBIGUATION
       -> empty: ASK_CLARIFICATION
```

## 数据与内存

当前 5,529 条真实响应约 353 KiB。Java 对象、规范化名称、Map 和刷新双缓冲预计峰值
10-24 MB，为本能力预留 32 MB 堆内存。缓存实体保持最小化，只持有 `name` 和 `tsCode`；
6 位 ticker 从 `tsCode` 派生。

## 匹配与安全边界

候选召回不依赖 Tushare `name` 参数的模糊行为。标准名称精确匹配优先，其次是受配置约束的
别名，再进行任意位置连续子串扫描。前缀和后缀都属于同一 contains 匹配层级。

多候选时不允许服务端直接取第一条。精确别名可以高于普通子串；多个普通子串只有在原始 query
或会话历史提供唯一指向信息时，Agent 才可在候选内消歧，否则必须追问。最终 ticker 必须属于
工具返回候选，否则按解析失败处理。此约束保证 LLM 只负责候选内消歧，不负责生成证券代码。

## 刷新与失败语义

默认每天 03:30（Asia/Shanghai）刷新。启动先读快照；有快照时立即可查询并后台刷新，无快照时
执行首次加载。只有完整拉取、校验和快照写入全部成功后才发布新索引。任何失败均不清空可用旧索引。

## 测试边界

测试覆盖精确、前缀、后缀、中间子串、别名、唯一候选、多候选、无结果、候选外 ticker 拒绝、
快照恢复、定时刷新、非法新数据拒绝、并发读取与原子替换。真实 Tushare 集成测试必须区分
精确查询成功与部分名称为空，不能再把空结果视为模糊匹配通过。

## 详细规格

字段、配置、错误处理、验收标准和实施任务以对应 Story 为准。
