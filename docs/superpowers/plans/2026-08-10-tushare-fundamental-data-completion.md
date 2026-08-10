# Tushare 基本面数据补全实现计划

> **致智能体工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 来按任务逐步实现本计划。步骤使用复选框（`- [ ]`）语法进行跟踪。

**目标：** 从五个 Tushare 接口补齐 `FundamentalDataVO` 的最新报告期基本面字段，并用单元测试和真实在线集成测试逐字段验证。

**架构：** `fina_indicator` 负责选定最新财报期并提供质量指标，`income`、`balancesheet`、`cashflow` 使用同一报告期查询并合并，`daily_basic` 独立提供最新交易日估值。原始金额保持 Tushare 的元口径，派生字段只在输入有效时计算。

**技术栈：** Java 17、Spring、Jackson、JUnit 5、Maven、Tushare HTTP API

---

## 文件结构

- 创建 `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareIncomeDTO.java`：利润表最小字段载体。
- 创建 `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareBalanceSheetDTO.java`：资产负债表最小字段载体。
- 修改 `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareFinaIndicatorDTO.java`：增加报告期选择和质量字段。
- 修改 `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareCashFlowDTO.java`：改为真实字段与元单位。
- 修改 `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareDailyBasicDTO.java`：增加 PS 和股息率。
- 修改 `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/TushareStockDataProvider.java`：同报告期查询、合并和派生计算。
- 修改 `ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareStockDataProviderTest.java`：逐字段、报告期和边界单测。
- 创建 `ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareFundamentalDataIntegrationTest.java`：真实 API 逐字段断言。

### 任务 1：建立 DTO 字段契约

| 任务 | status |
|------|------|
| 任务 1：建立 DTO 字段契约 | pass |

**文件：**
- 创建：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareIncomeDTO.java`
- 创建：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareBalanceSheetDTO.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareFinaIndicatorDTO.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareCashFlowDTO.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareDailyBasicDTO.java`
- 测试：`ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareStockDataProviderTest.java`

- [x] **步骤 1：把成功场景改成真实字段并增加逐字段断言**

```java
assertAll(
        () -> assertEquals(6.9887, result.getRoe()),
        () -> assertEquals(4.815, result.getRoa()),
        () -> assertEquals(new BigDecimal("6.4341"), result.getBookValuePerShare()),
        () -> assertEquals(new BigDecimal("1126074496.05"), result.getRevenue()),
        () -> assertEquals(new BigDecimal("246333622.73"), result.getNetIncome()),
        () -> assertEquals(new BigDecimal("5965684837.28"), result.getTotalAssets()),
        () -> assertEquals(new BigDecimal("2280359405.02"), result.getTotalDebt()),
        () -> assertEquals(new BigDecimal("268450155.61"), result.getOperatingCashFlow()),
        () -> assertEquals(new BigDecimal("255318894.38"), result.getFreeCashFlow()),
        () -> assertEquals(10.3177, result.getRevenueGrowth()),
        () -> assertEquals(13.6133, result.getNetIncomeGrowth()),
        () -> assertEquals(result.getNetIncomeGrowth(), result.getEarningsGrowth()),
        () -> assertEquals(3.1861, result.getPsRatio()),
        () -> assertEquals(4.0468, result.getDividendYield()),
        () -> assertEquals(15.9852 / 13.6133, result.getPegRatio(), 0.0001));
```

- [x] **步骤 2：运行测试验证失败**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-infra -am -Dtest=TushareStockDataProviderTest#getFundamentalData_success -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：FAIL，新增字段仍为空或字段名不匹配。

- [x] **步骤 3：实现 DTO**

```java
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TushareIncomeDTO extends TushareBaseDTO {
    private String annDate;
    private String endDate;
    private String updateFlag;
    private BigDecimal revenue;
    private BigDecimal nIncomeAttrP;
}
```

`TushareBalanceSheetDTO` 使用相同注解并声明 `annDate`、`endDate`、`updateFlag`、`totalAssets`、`totalLiab`。其他 DTO 精确增加：

```java
// TushareFinaIndicatorDTO
private String updateFlag;
private Double roa;
private BigDecimal bps;
private Double trYoy;
private Double netprofitYoy;

// TushareCashFlowDTO
private String updateFlag;
private BigDecimal nCashflowAct;
private BigDecimal cPayAcqConstFiolta;

public BigDecimal calculateFreeCashFlow() {
    if (nCashflowAct == null) return null;
    return cPayAcqConstFiolta == null
            ? nCashflowAct
            : nCashflowAct.subtract(cPayAcqConstFiolta.abs());
}

// TushareDailyBasicDTO
private Double ps;
private Double psTtm;
private Double dvRatio;
```

- [x] **步骤 4：编译并提交 DTO**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-infra -am -DskipTests package
git add ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto
git commit -m "feat: add Tushare financial statement DTOs"
```

预期：BUILD SUCCESS，提交只包含 DTO。

### 任务 2：按统一报告期聚合基本面数据

| 任务 | status |
|------|------|
| 任务 2：按统一报告期聚合基本面数据 | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/TushareStockDataProvider.java:193`
- 测试：`ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareStockDataProviderTest.java:323`

- [x] **步骤 1：断言接口字段和统一报告期**

```java
if (Set.of("income", "balancesheet", "cashflow").contains(apiName)) {
    assertEquals("600285.SH", params.get("ts_code"));
    assertEquals("20260331", params.get("period"));
}
if ("income".equals(apiName)) {
    assertEquals("ts_code,ann_date,end_date,update_flag,revenue,n_income_attr_p", fields);
}
```

分别返回 `end_date=20260331` 的三张报表。

- [x] **步骤 2：运行成功场景并确认查询尚未发生**

运行任务 1 的单测试命令。预期：FAIL，`income`、`balancesheet` 未调用。

- [x] **步骤 3：实现报告期查询**

先从 `fina_indicator` 选择最新 `endDate`，再使用：

```java
Map.of("ts_code", tsCode, "period", period)
```

分别调用 `income`、`balancesheet`、`cashflow`。字段必须为：

```text
income: ts_code,ann_date,end_date,update_flag,revenue,n_income_attr_p
balancesheet: ts_code,ann_date,end_date,update_flag,total_assets,total_liab
cashflow: ts_code,ann_date,end_date,update_flag,n_cashflow_act,c_pay_acq_const_fiolta
```

选择记录时依次比较 `endDate`、`updateFlag == "1"`、`annDate`，所有比较允许 `null`。

- [x] **步骤 4：映射字段并计算 PEG**

```java
Double growth = fina == null ? null : fina.getNetprofitYoy();
Double peg = valuation != null && valuation.getPeTtm() != null
        && valuation.getPeTtm() > 0 && growth != null && growth > 0
        ? valuation.getPeTtm() / growth : null;
```

builder 同时写入 `roa`、`bps`、两个增长率别名、利润表金额、资产负债表金额、经营现金流、自由现金流、`psTtm`、`dvRatio` 和 PEG，并保留现有 PE/PB/市值字段。

- [x] **步骤 5：运行 Provider 单测并提交**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-infra -am -Dtest=TushareStockDataProviderTest -Dsurefire.failIfNoSpecifiedTests=false test
git add ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareStockDataProviderTest.java
git commit -m "feat: complete Tushare fundamental data mapping"
```

预期：BUILD SUCCESS，旧错误字段测试已迁移。

### 任务 3：覆盖修订记录和派生边界

| 任务 | status |
|------|------|
| 任务 3：覆盖修订记录和派生边界 | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareStockDataProviderTest.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/TushareStockDataProvider.java`

- [x] **步骤 1：测试修订记录选择**

同报告期返回 `update_flag=0` 和 `update_flag=1` 两条记录，断言使用后者的营收和增长率。

- [x] **步骤 2：测试部分数据**

让 `balancesheet` 返回空列表，断言 `totalAssets/totalDebt` 为空，而营收、现金流和估值仍非空。

- [x] **步骤 3：测试 PEG 边界**

分别构造 `netprofit_yoy` 为 `0`、`-5` 和缺失，断言 PEG 为 `null`；正增长断言等于 `peTtm / growth`。

- [x] **步骤 4：运行并提交边界测试**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-infra -am -Dtest=TushareStockDataProviderTest -Dsurefire.failIfNoSpecifiedTests=false test
git add ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/TushareStockDataProvider.java ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareStockDataProviderTest.java
git commit -m "test: cover Tushare financial data boundaries"
```

预期：BUILD SUCCESS。

### 任务 4：增加真实 Tushare 在线验证

| 任务 | status |
|------|------|
| 任务 4：增加真实 Tushare 在线验证 | append |

**文件：**
- 创建：`ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareFundamentalDataIntegrationTest.java`

- [ ] **步骤 1：编写环境变量门控的真实测试**

```java
@Test
void getFundamentalData_realApi_populatesSupportedFields() {
    String token = System.getenv("TUSHARE_TOKEN");
    Assumptions.assumeTrue(token != null && !token.isBlank(),
            "需要 TUSHARE_TOKEN 才能运行真实 Tushare 集成测试");
    TushareStockDataProvider provider = new TushareStockDataProvider(
            new TushareApiClient(token), new TechnicalIndicatorCalculator(),
            (keyword, limit) -> List.of());
    FundamentalDataVO result = provider.getFundamentalData("600285.SH");
    assertAll(
            () -> assertNotNull(result.getPeTtm()),
            () -> assertNotNull(result.getPsRatio()),
            () -> assertNotNull(result.getRoa()),
            () -> assertNotNull(result.getRevenue()),
            () -> assertNotNull(result.getNetIncome()),
            () -> assertNotNull(result.getTotalAssets()),
            () -> assertNotNull(result.getTotalDebt()),
            () -> assertNotNull(result.getOperatingCashFlow()),
            () -> assertNotNull(result.getFreeCashFlow()),
            () -> assertNotNull(result.getRevenueGrowth()),
            () -> assertNotNull(result.getNetIncomeGrowth()),
            () -> assertNotNull(result.getDividendYield()),
            () -> assertNotNull(result.getPegRatio()));
}
```

在同一个 `assertAll` 中继续明确断言 `pe`、`pb`、`totalMv`、`circMv`、`valuationTradeDate`、`roe`、`grossMargin`、`netMargin`、`bookValuePerShare`、`eps`、`earningsGrowth`、`debtToAssets`、`currentRatio` 均非空。另行断言 `totalAssets > totalDebt`、`earningsGrowth == netIncomeGrowth`、`freeCashFlow <= operatingCashFlow`。存在 token 时任何缺失都必须失败。

- [ ] **步骤 2：运行默认测试确认隔离**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-infra -am -Dtest=TushareStockDataProviderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：BUILD SUCCESS，未运行 `*IntegrationTest`。

- [ ] **步骤 3：运行真实在线测试**

```powershell
mvn -Pintegration -pl ai-agent-study-trading/ai-agent-study-trading-infra -am -Dit.test=TushareFundamentalDataIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify
```

预期：真实请求 `600285.SH`，测试 PASS 且不显示 skipped。

- [ ] **步骤 4：运行全量模块回归并提交**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-infra -am -Dsurefire.failIfNoSpecifiedTests=false test
git add ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareFundamentalDataIntegrationTest.java
git commit -m "test: verify Tushare fundamental fields online"
```

预期：BUILD SUCCESS。

### 任务 5：最终核验与计划回填

| 任务 | status |
|------|------|
| 任务 5：最终核验与计划回填 | append |

**文件：**
- 修改：`docs/superpowers/plans/2026-08-10-tushare-fundamental-data-completion.md`

- [ ] **步骤 1：检查 worktree 边界**

```powershell
git status --short
git diff --check
git log --oneline -6
```

预期：没有其他 agent 的文件，代码和测试均已提交。

- [ ] **步骤 2：回填五个任务状态和复选框**

仅在对应测试成功后把 `append` 改为 `pass` 并勾选步骤。

- [ ] **步骤 3：提交执行记录并确认干净**

```powershell
git add docs/superpowers/plans/2026-08-10-tushare-fundamental-data-completion.md
git commit -m "docs: record Tushare data completion verification"
git status --short
```

预期：worktree 干净，分支包含设计、实现、测试和验证记录。
