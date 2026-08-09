# Tushare 每日估值字段映射设计

## 背景

当前 `StockInfoVO` 和 `FundamentalDataVO` 使用 `peRatio`、`pbRatio`、`marketCap` 表示估值数据。`TushareStockDataProvider` 又从 `fina_indicator` 请求 `pe`、`pb_ratio` 等字段，但这些估值字段实际由 Tushare `daily_basic` 返回。因此现有实现会产生两个问题：字段命名容易与历史百分位混淆，且运行时无法为 `StockInfoVO` 填充 PE、PB 和市值。

本设计将 Tushare 响应模型与领域模型分开：响应 DTO 保留数据源字段语义，Provider 负责在 Java 中完成显式映射。

## 目标

- 从 `daily_basic` 获取与最新价格同一交易日的估值快照。
- 用无歧义的名称表达绝对估值倍数，不把 PE、PB 表述为百分位。
- 补齐静态 PE、滚动 PE、PB、总市值和流通市值。
- 基本面分析默认使用 `peTtm` 作为 PE 口径；`pe` 只作为明确标注的静态 PE 补充展示。
- 当 `peTtm` 缺失时报告“PE_TTM 不可用”，禁止使用单季、累计季度或报告期不明的 EPS 自行补算 PE。
- 保持 Tushare 原始市值单位“万元”，避免映射层隐式换算。
- 从 `fina_indicator` 请求中移除不属于该接口的估值字段。
- 对空数据、接口错误和协议错误保留不同的错误语义。

## 非目标

- 本次不计算 PE 或 PB 的历史百分位。
- 本次不新增 `pePercentile`、`pbPercentile` 或估值历史窗口。
- 本次不改变 Tushare 的原始单位，也不自动转换为元或亿元。
- 本次不引入新的缓存层或批量取数机制。

## 字段契约

新增 `TushareDailyBasicDTO`，使用 `SnakeCaseStrategy` 完成 Tushare `snake_case` 到 Java 驼峰命名的反序列化：

| Tushare 字段 | Java DTO 字段 | 含义 | 单位 |
| --- | --- | --- | --- |
| `ts_code` | `tsCode` | Tushare 股票代码 | 无 |
| `trade_date` | `tradeDate` | 估值交易日 | `yyyyMMdd` |
| `close` | `close` | 当日收盘价 | 元 |
| `pe` | `pe` | 静态市盈率 | 倍 |
| `pe_ttm` | `peTtm` | 滚动市盈率 | 倍 |
| `pb` | `pb` | 市净率 | 倍 |
| `total_mv` | `totalMv` | 总市值 | 万元 |
| `circ_mv` | `circMv` | 流通市值 | 万元 |

`StockInfoVO` 和 `FundamentalDataVO` 统一使用以下估值字段：

- `pe`
- `peTtm`
- `pb`
- `totalMv`
- `circMv`

删除旧字段 `peRatio`、`pbRatio`、`marketCap`。这是一次明确的内部契约迁移，不保留语义重复的兼容字段。所有 Java 调用方、Builder、格式化输出、Mock 和测试同步迁移。

`StockInfoVO` 额外增加 `valuationTradeDate`，用于暴露估值快照日期。该字段采用现有对外日期风格 `yyyy-MM-dd`，由 DTO 的 `tradeDate` 转换得到。

## 数据流

### 股票信息

`getStockInfo` 首先按现有流程查询 `stock_basic`，然后查询截至当前日期的最新 `daily` 记录。取得日线记录的 `trade_date` 后，以相同的 `ts_code` 和 `trade_date` 查询 `daily_basic`，从而保证 `currentPrice`、成交量和估值属于同一交易日。

Provider 将 `daily_basic` 的 `pe`、`peTtm`、`pb`、`totalMv`、`circMv` 映射到 `StockInfoVO`，并设置 `valuationTradeDate`。52 周高低点仍由现有日线区间计算，不改变口径。

### 基本面信息

`getFundamentalData` 继续从 `fina_indicator` 获取 ROE、利润率、资产负债率等财务指标，但删除 `pe`、`pb_ratio`、`ps_ratio`、`peg` 等未由该接口返回的估值请求和错误 DTO 字段。

估值部分改为查询最新可用的 `daily_basic`，并映射 `pe`、`peTtm`、`pb`、`totalMv`、`circMv`。情绪推导中原来读取 `getPeRatio()` 的逻辑改为读取 `getPe()`，其评分规则本次不调整。

基本面分析提示词必须明确估值口径：默认引用 `peTtm` 并称为“滚动市盈率”或“PE_TTM”；只有在需要补充年度口径时才引用 `pe`，且必须称为“静态市盈率”。当 `peTtm` 为空时，模型只能说明该指标不可用，不得用 `currentPrice / eps` 补算，因为最新 `fina_indicator.eps` 可能是单季或年内累计口径。

## 组件边界

### `TushareDailyBasicDTO`

只负责承载 Tushare `daily_basic` 响应并进行字段反序列化，不负责单位换算、业务降级或跨接口合并。

### `TushareStockDataProvider`

负责选择最新交易日、调用 `daily_basic`、校验日期一致性，并将 DTO 映射为领域 VO。共用的最新估值查询提取为私有方法，避免 `getStockInfo` 和 `getFundamentalData` 重复请求构造与映射规则。

### 领域 VO 与调用方

领域 VO 使用清晰的业务字段名，但保留 Tushare 市值单位。工具输出必须在标签中注明“万元”，避免调用者把原始值解释为元或亿元。

## 空值与错误处理

- `daily_basic` 成功但无记录：保留股票基础信息和行情数据，估值字段为空，并记录包含股票代码和交易日的警告日志。
- Tushare 返回权限、参数或服务端错误：使用严格调用路径保留异常类型，不降级成普通空列表。
- 响应结构缺失或 DTO 转换失败：作为协议错误传播，不伪装成无数据。
- 最新日线为空：保持现有降级语义，但不能构造一个日期不明的估值快照。
- `daily` 与 `daily_basic` 返回日期不一致：不合并估值数据，记录数据一致性警告。
- PE 在亏损场景为空属于合法业务值，不用零代替。

## 输出与兼容性

序列化后的估值字段改为 `pe`、`peTtm`、`pb`、`totalMv`、`circMv` 和 `valuationTradeDate`。依赖旧 JSON 字段 `peRatio`、`pbRatio`、`marketCap` 的消费者需要同步升级；本项目内的提示词输入、工具格式化和测试在同一变更中完成迁移。

Mock 数据当前包含疑似百分位语义的 PE/PB 数字。本次迁移会把 Mock 调整为合理的绝对估值示例，并明确其单位，防止测试继续固化旧歧义。

## 测试策略

- DTO 映射测试：验证 `pe_ttm`、`total_mv`、`circ_mv` 正确反序列化。
- Provider 正常路径：验证日线与 `daily_basic` 使用同一交易日，并完整填充六个估值字段。
- 非交易日回退：验证采用最近交易日，且价格与估值日期一致。
- 空估值数据：验证股票信息仍可返回，估值字段为空且不会伪造零值。
- 严格错误路径：验证权限错误、协议错误不会被转换成空结果。
- 基本面路径：验证财务指标来自 `fina_indicator`，估值来自 `daily_basic`。
- 羚锐制药回归：构造 `currentPrice=22.24`、`eps=0.435`、`peTtm=16.6`，验证基本面提示词把 16.6 标为 PE_TTM，且不会要求或暗示按 EPS 生成 51 倍静态 PE。
- PE_TTM 缺失回归：构造 `peTtm=null` 且季度 EPS 有值，验证提示词明确禁止从 EPS 推算 PE，并要求输出“PE_TTM 不可用”。
- 调用方迁移：更新格式化工具、情绪推导、Mock 和现有断言，确保不再引用旧 getter 或 Builder 字段。
- 模块回归：运行 trading API、domain 和 infra 模块的相关单元测试。

## 验收标准

- 对 `002371.SZ` 查询时，`StockInfoVO` 能获得同一交易日的 `pe`、`peTtm`、`pb`、`totalMv`、`circMv` 和 `valuationTradeDate`。
- 项目源码中不再存在估值语义的 `peRatio`、`pbRatio`、`marketCap` 使用。
- `fina_indicator` 请求不再包含 PE、PB 和市值字段。
- 基本面分析默认展示并引用 `peTtm`；`peTtm` 缺失时不会再从季度 EPS 生成估值倍数。
- 所有市值输出明确标注单位为万元。
- 相关模块测试通过，且不修改用户已有的无关配置变更。
