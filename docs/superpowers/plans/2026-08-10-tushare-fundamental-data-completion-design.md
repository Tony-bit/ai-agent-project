# Tushare 基本面数据补全设计

## 背景

当前 `TushareStockDataProvider#getFundamentalData` 主要从 `fina_indicator` 读取基本面数据，但请求了不属于该接口的 `revenue`、`net_profit`、`total_assets`、`div_ratio` 等字段。现金流查询也使用了与 Tushare 实际响应不一致的字段名，并且没有给 `operatingCashFlow` 赋值。因此模型收到的 `FundamentalDataVO` 存在大量 `null`，即使当前 2000 积分账号能够访问对应财务接口。

本次改动以现有 `FundamentalDataVO` 和基本面分析报告实际消费的数据为边界，补齐最新报告期的财务质量、利润表、资产负债表、现金流和估值数据。不扩展历史季度趋势模型，不接入当前业务未消费的预测、业绩快报和披露日历接口。

## 目标

- 新增 `income` 和 `balancesheet` 查询。
- 修正并扩展 `fina_indicator`、`cashflow`、`daily_basic` 的字段映射。
- 以同一个最新报告期合并利润表、资产负债表和现金流量表，避免不同报告期的数据混用。
- 尽可能补齐现有 `FundamentalDataVO` 中报告会使用的字段。
- 使用单元测试逐字段验证映射和计算规则。
- 使用真实 Tushare 在线集成测试验证当前 token 确实能够取得这些数据。

## 非目标

- 不接入 `forecast`、`express`、`disclosure_date`。
- 不接入 `dividend`，因此本次不补 `dps`。股息率继续使用 `daily_basic.dv_ratio`。
- 不拉取最近八个季度，不建立财务历史序列或趋势缓存。
- 不修改技术指标窗口、MA120 或报告生成结构。
- 不修改 Tushare 积分、token 或应用配置。

## 数据源与字段映射

| 接口 | Tushare 字段 | 领域字段 | 口径 |
|---|---|---|---|
| `fina_indicator` | `roe` | `roe` | 百分数值 |
| `fina_indicator` | `roa` | `roa` | 百分数值 |
| `fina_indicator` | `grossprofit_margin` | `grossMargin` | 百分数值 |
| `fina_indicator` | `netprofit_margin` | `netMargin` | 百分数值 |
| `fina_indicator` | `debt_to_assets` | `debtToAssets` | 百分数值 |
| `fina_indicator` | `current_ratio` | `currentRatio` | 比率 |
| `fina_indicator` | `eps` | `eps` | 元/股 |
| `fina_indicator` | `bps` | `bookValuePerShare` | 元/股 |
| `fina_indicator` | `tr_yoy` | `revenueGrowth` | 百分数值 |
| `fina_indicator` | `netprofit_yoy` | `netIncomeGrowth`、`earningsGrowth` | 两个既有字段使用同一净利润同比口径 |
| `income` | `revenue` | `revenue` | Tushare 原始值为元，不做万元换算 |
| `income` | `n_income_attr_p` | `netIncome` | 归母净利润，原始值为元 |
| `balancesheet` | `total_assets` | `totalAssets` | 原始值为元 |
| `balancesheet` | `total_liab` | `totalDebt` | 领域旧字段名保持兼容，实际口径为负债合计 |
| `cashflow` | `n_cashflow_act` | `operatingCashFlow` | 经营活动产生的现金流量净额，原始值为元 |
| `cashflow` | `c_pay_acq_const_fiolta` | 自由现金流计算输入 | 购建长期资产支付的现金，原始值为元 |
| `daily_basic` | `pe`、`pe_ttm`、`pb` | `pe`、`peTtm`、`pb` | 最新交易日估值 |
| `daily_basic` | `ps_ttm` | `psRatio` | 默认使用滚动市销率 |
| `daily_basic` | `dv_ratio` | `dividendYield` | Tushare 百分数值，不转换为小数 |
| `daily_basic` | `total_mv`、`circ_mv` | `totalMv`、`circMv` | 保持 Tushare 万元单位 |

`pegRatio` 由 `peTtm / netIncomeGrowth` 计算。只有 `peTtm > 0` 且 `netIncomeGrowth > 0` 时才生成，否则保持 `null`，避免对亏损或负增长公司产生无意义 PEG。

`freeCashFlow` 按现有领域定义计算：

```text
freeCashFlow = operatingCashFlow - abs(c_pay_acq_const_fiolta)
```

两个现金流输入都是元，因此不再乘以 10000。

## 报告期选择与合并

1. 查询 `fina_indicator`，按 `end_date`、`ann_date` 选择最新有效记录。
2. 以选中的 `end_date` 作为财务报告期锚点。
3. `income`、`balancesheet`、`cashflow` 均使用同一 `ts_code` 和报告期查询。
4. 同一报告期存在重复或修订记录时，优先选择公告日期较新且 `update_flag=1` 的记录；无该标记时选择公告日期最新记录。
5. `daily_basic` 不属于财报口径，独立选择最新交易日，并保留 `valuationTradeDate`。

任何单个财务接口返回空列表时，对应字段保持 `null`，其他已成功取得的数据仍然返回。接口错误必须记录 `apiName`、`tsCode` 和报告期，不能把错误伪装成真实的空数据。

## 组件改动

### DTO

- 扩展 `TushareFinaIndicatorDTO`：增加 `updateFlag`、`roa`、`bps`、`trYoy`、`netprofitYoy`。
- 新增 `TushareIncomeDTO`：承载利润表报告期、公告日、更新标记、营业收入和归母净利润。
- 新增 `TushareBalanceSheetDTO`：承载资产负债表报告期、公告日、更新标记、资产总计和负债合计。
- 修正 `TushareCashFlowDTO`：改为真实字段 `nCashflowAct`、`cPayAcqConstFiolta`，金额保持元。
- 扩展 `TushareDailyBasicDTO`：增加 `ps`、`psTtm`、`dvRatio`。

### Provider

`TushareStockDataProvider#getFundamentalData` 负责选择报告期、发起各接口查询并合并为 `FundamentalDataVO`。选择最新财报记录和同报告期修订记录的规则提取为私有辅助方法，避免在四个财务接口中复制排序逻辑。

已有 `loadValuation` 继续复用，只扩展请求字段和 DTO 映射。保留现有 PE_TTM 权威口径，不使用季度 EPS 推算 PE。

## 错误处理

- token 无效、权限不足、参数错误等 API 错误必须在在线集成测试中直接失败。
- 生产 provider 保留部分成功能力：某张财务表暂未披露时，只让该表对应字段为空。
- DTO 解析错误视为协议错误，向上抛出，不退化为空字段。
- PEG 的输入不满足条件时返回 `null`，不抛异常。

## 测试策略

### 单元测试

扩展 `TushareStockDataProviderTest`，使用 mock API 响应逐项断言：

- 五个接口使用正确的接口名、参数和字段名。
- `fina_indicator` 映射 ROE、ROA、利润率、偿债指标、EPS、BPS 和两个增长率字段。
- `income` 映射营收和归母净利润，金额不发生 10000 倍误换算。
- `balancesheet` 映射总资产和负债合计。
- `cashflow` 映射经营现金流，并正确计算自由现金流。
- `daily_basic` 映射 PE、PE_TTM、PB、PS_TTM、股息率和市值。
- 正增长生成 PEG，零增长、负增长和缺失增长不生成 PEG。
- 多接口数据使用同一报告期；重复记录选择最新修订版本。
- 任一接口为空时只影响本接口字段，不清空其他接口结果。

### 在线集成测试

新增 `TushareFundamentalDataIntegrationTest`：

- token 只从环境变量 `TUSHARE_TOKEN` 读取，不在测试源码中写入 token。
- 没有环境变量时跳过；存在环境变量时必须真实调用，不能把空结果当作通过。
- 使用财务数据稳定且本次已验证的 `600285.SH`。
- 逐字段断言 `pe`、`peTtm`、`pb`、`psRatio`、`totalMv`、`circMv`、`valuationTradeDate`、`roe`、`roa`、`grossMargin`、`netMargin`、`revenue`、`netIncome`、`totalAssets`、`totalDebt`、`bookValuePerShare`、`eps`、`revenueGrowth`、`earningsGrowth`、`netIncomeGrowth`、`operatingCashFlow`、`freeCashFlow`、`debtToAssets`、`currentRatio`、`dividendYield`、`pegRatio` 均非空。
- 断言资产和现金流金额为正、总资产大于总负债、自由现金流计算结果与原始字段关系一致、两个净利润增长字段相等。
- 在线测试通过 Maven `integration` profile 执行，与默认单元测试隔离。

## 验收标准

- `600285.SH` 的基本面报告不再把上述可获取字段描述为缺失。
- 单元测试覆盖所有新增和修正字段，并验证单位与派生公式。
- 配置 `TUSHARE_TOKEN` 后，在线集成测试真实请求五个接口并通过逐字段断言。
- 默认测试不依赖外网；在线测试不包含硬编码 token。
- 不影响已有 PE_TTM、PB、市值和部分数据降级行为。
