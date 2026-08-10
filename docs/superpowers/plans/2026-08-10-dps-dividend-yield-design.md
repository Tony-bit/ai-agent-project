# DPS 与股息率数据补全设计

## 背景

当前 `FundamentalDataVO` 已定义 `dps` 和 `dividendYield`。`TushareStockDataProvider#getFundamentalData` 已将 `daily_basic.dv_ratio` 映射为 `dividendYield`，但没有调用 Tushare `dividend` 接口，因此真实 provider 返回的 `dps` 始终为空。

基本面分析师通过 `FundamentalAnalystNode#buildStockData` 把完整的 `StockInfoVO` 和 `FundamentalDataVO` 序列化为 JSON，再渲染数据库中的 `prompt_id=6002`。因此 provider 补齐 `dps` 后，分析师无需新增内部接口即可收到该字段。

## 目标

- 从 Tushare 获取最近一期已实施的税前每股现金分红，并写入 `FundamentalDataVO.dps`。
- 保持现有 `daily_basic.dv_ratio -> FundamentalDataVO.dividendYield` 映射，并补足针对该字段的测试。
- 通过真实 Tushare 在线集成测试证明当前 token 能取得非空、有效的 DPS 和股息率。
- 验证基本面分析师构造的 `stockData` 同时包含 `dps` 和 `dividendYield`。
- 不新增 `IStockDataProvider` 方法，不改变现有调用方契约。

## 非目标

- 不计算过去十二个月多次分红的合计 DPS。
- 不采用“预案”或“股东大会通过”状态的尚未实施分红。
- 不根据股价和 DPS 自行反推股息率；股息率继续采用 Tushare `daily_basic.dv_ratio` 权威口径。
- 不新增 DPS 报告期、除权日或支付日等领域字段。
- 不修改数据库中的 `Fundamental Analyst V3` prompt。本次只确保已有完整 JSON 输入包含新增数据；是否要求模型固定输出“股东回报”章节应作为单独的 prompt 变更处理。

## 方案比较

### 方案一：最近一期已实施 DPS（采用）

调用 `dividend`，只保留 `div_proc=实施` 且 `cash_div_tax` 非空的记录，再按报告期和公告日期从新到旧选择一条。该值表示最近一期已实施方案的税前每股现金分红。

优点是语义明确，不会把尚未落地的预案当成股东已获得的回报；实现也与当前单期基本面快照一致。缺点是它不是过去十二个月累计分红，不能直接与所有口径的股息率做等式校验。

### 方案二：过去十二个月已实施 DPS 合计

按除权日或支付日汇总最近十二个月的 `cash_div_tax`。该口径更接近部分行情系统的滚动股息率，但需要明确时间窗口、处理同一报告期多次派息和边界日期，超出当前单字段补全需求。

### 方案三：最近公告的分红方案

直接取最新公告记录，无论进度。它能更早展示预期分红，但会把预案或待审议方案暴露为 DPS，容易让分析师把未落地数据当成事实，因此不采用。

## 数据源与字段映射

| Tushare 接口 | Tushare 字段 | 领域字段 | 口径 |
|---|---|---|---|
| `dividend` | `cash_div_tax` | `dps` | 最近一期 `div_proc=实施` 的税前每股现金分红，单位为元/股 |
| `daily_basic` | `dv_ratio` | `dividendYield` | Tushare 原始百分数值，不转换为小数 |

`dividend` 请求字段限定为：

```text
ts_code,end_date,ann_date,div_proc,cash_div_tax,record_date,ex_date,pay_date
```

DTO 保留日期和状态字段用于筛选及测试，但本次只把 `cash_div_tax` 写入领域对象。

## 数据流与组件改动

1. 新增 `TushareDividendDTO`，使用现有 snake_case 映射规则承载分红响应。
2. `TushareStockDataProvider#getFundamentalData` 在现有基本面聚合中调用外部 `dividend` 接口。
3. provider 使用独立的私有选择函数过滤非“实施”记录和空 `cash_div_tax`，再按 `end_date`、`ann_date` 降序选择最新记录。
4. 将选中记录的 `cashDivTax` 写入已有 `FundamentalDataVO.dps`。
5. `dividendYield` 继续由现有 `loadValuation` 从最新交易日 `daily_basic.dv_ratio` 写入。
6. `FundamentalAnalystNode#buildStockData` 继续序列化完整 `FundamentalDataVO`，无需新增传参或 analyst 接口。

这里的“新增接口”仅指 provider 多调用一个外部 Tushare API；工程内部仍使用现有的 `getFundamentalData()` 契约。

## 错误与空数据处理

- `dividend` 正常返回空列表，或没有符合条件的记录时，`dps` 保持 `null`，其他基本面字段照常返回。
- `cash_div_tax=0` 是有效值，不得当成缺失过滤。
- Tushare 返回权限、参数或协议错误时沿用 `callGenericStrict` 行为向上抛出，避免把调用失败伪装成公司没有分红。
- `dividendYield` 是否为空只由最新 `daily_basic` 响应决定，不使用 DPS 回填或推算。
- DPS 与股息率口径不同：前者是最近一期实施方案，后者是 Tushare 行情估值字段，测试不要求两者通过当前股价互相推导。

## 基本面分析师输入

当前活动 prompt 为 `Fundamental Analyst V3`（`prompt_id=6002`，`RELAXED_V3`）：

```text
{{targetContext}}

基于以下数据撰写基本面分析，覆盖盈利、成长、偿债、现金流、数据质量和主要风险。只引用输入中存在的事实，以自然语言输出，不要输出 JSON。
{{stockData}}
```

运行时输入包括：

- `targetContext`：股票代码、TS Code、股票名称、行业、分析基准日期和标的锁定约束。
- `stockData.stockInfo`：完整股票基础信息。
- `stockData.fundamentalData`：估值、盈利、成长、资产负债、现金流和股东回报字段，其中本次补齐 `dps`，并继续提供 `dividendYield`。
- PE_TTM 权威口径约束：存在 `peTtm` 时要求使用滚动市盈率，不允许用当前价除以 EPS 另算 PE。

本轮完成后 role 能取得 DPS 和股息率，但现有 prompt 没有强制要求逐次讨论股东回报。数据供给和输出要求分开处理，避免把 provider 变更与数据库 prompt 发布耦合。

## 测试策略

### 单元测试

扩展 `TushareStockDataProviderTest`，覆盖：

- 发起 `dividend` 请求时使用正确的接口名、`ts_code` 和字段列表。
- 同时存在“预案”“股东大会通过”“实施”记录时，只选择“实施”。
- 多条已实施记录按 `end_date`、`ann_date` 选择最新一条，并正确映射 `cash_div_tax -> dps`。
- `cash_div_tax=0` 映射为零而不是 `null`。
- 无已实施记录时 `dps=null`，现有其他来源字段不受影响。
- `daily_basic.dv_ratio -> dividendYield` 映射保持不变。

扩展 domain 层测试，断言 `FundamentalAnalystNode#buildStockData` 生成的 JSON 中存在传入的 `dps` 和 `dividendYield` 数值，证明 analyst role 实际收到这两个字段。

### 在线集成测试

扩展 `TushareFundamentalDataIntegrationTest`：

- 继续只从环境变量读取 `TUSHARE_TOKEN`；无 token 时跳过，有 token 时必须真实调用。
- 使用已经验证可返回稳定财务与分红数据的 `600285.SH`。
- 新增 `dps` 非空且大于等于零的断言。
- 保留 `dividendYield` 非空断言，并增加其大于等于零的有效性断言。
- 不把权限错误或外部调用错误当作跳过或通过。

## 验收标准

- `getFundamentalData("600285.SH")` 通过真实 Tushare 调用返回非空 `dps` 和 `dividendYield`。
- 单元测试证明 DPS 只来自最近一期已实施方案，并覆盖零值与空结果。
- 单元测试证明股息率仍来自最新 `daily_basic.dv_ratio`。
- analyst 的 `stockData` JSON 包含两个字段，且不新增内部 provider 方法。
- provider、domain 相关测试与项目编译全部通过。
