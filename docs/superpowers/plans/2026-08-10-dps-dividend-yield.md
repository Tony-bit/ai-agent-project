# DPS 与股息率数据补全实现计划

> **致智能体工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 来按任务逐步实现本计划。步骤使用复选框（`- [ ]`）语法进行跟踪。

**目标：** 在现有基本面聚合契约中补齐最近一期已实施税前 DPS，并用单元测试、analyst 输入测试和真实 Tushare 集成测试验证 DPS 与股息率。

**架构：** `TushareStockDataProvider#getFundamentalData` 继续作为唯一的内部聚合入口，新增外部 `dividend` 查询并筛选最近一期 `div_proc=实施` 的 `cash_div_tax`。股息率保持现有 `daily_basic.dv_ratio` 映射，`FundamentalAnalystNode` 继续把完整 `FundamentalDataVO` 序列化到 `stockData`，不修改数据库 prompt。

**技术栈：** Java 17、Jackson、JUnit 5、Maven、Tushare HTTP API

---

## 文件结构

- 创建 `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareDividendDTO.java`：承载分红状态、报告期、日期和税前每股现金分红。
- 修改 `ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/TushareStockDataProvider.java`：查询并选择最近一期已实施分红，映射到已有 `dps` 字段。
- 修改 `ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareStockDataProviderTest.java`：验证接口契约、状态筛选、排序、零值、空结果和股息率映射。
- 修改 `ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/FundamentalAnalystValuationPolicyTest.java`：验证 analyst 输入 JSON 包含 DPS 与股息率。
- 修改 `ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareFundamentalDataIntegrationTest.java`：真实调用断言 DPS 与股息率非空且有效。
- 修改 `docs/superpowers/plans/2026-08-10-dps-dividend-yield.md`：执行后回填任务状态和验证结果。

### 任务 1：以测试锁定分红选择和映射契约

| 任务 | status |
|------|------|
| 任务 1：以测试锁定分红选择和映射契约 | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareStockDataProviderTest.java`
- 创建：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareDividendDTO.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/TushareStockDataProvider.java`

- [x] **步骤 1：在成功场景中增加接口契约和字段断言**

在 `getFundamentalData_success()` 的测试 client 中增加：

```java
if ("dividend".equals(apiName)) {
    assertEquals("600285.SH", params.get("ts_code"));
    assertEquals("ts_code,end_date,ann_date,div_proc,cash_div_tax,record_date,ex_date,pay_date", fields);
    return List.of(Map.of(
            "ts_code", "600285.SH",
            "end_date", "20251231",
            "ann_date", "20260428",
            "div_proc", "实施",
            "cash_div_tax", "1.10",
            "record_date", "20260610",
            "ex_date", "20260611",
            "pay_date", "20260611"));
}
```

并在现有 `assertAll` 中加入：

```java
() -> assertEquals(new BigDecimal("1.10"), result.getDps()),
() -> assertEquals(4.0468, result.getDividendYield())
```

- [x] **步骤 2：增加状态、日期排序和边界测试**

增加三个独立测试：

```java
@Test
void getFundamentalDataSelectsLatestImplementedDividend() {
    TushareApiClient client = createTestClient((apiName, params, fields) -> {
        if (!"dividend".equals(apiName)) {
            return Collections.emptyList();
        }
        return List.of(
                Map.of("end_date", "20261231", "ann_date", "20270401",
                        "div_proc", "预案", "cash_div_tax", "2.00"),
                Map.of("end_date", "20251231", "ann_date", "20260428",
                        "div_proc", "实施", "cash_div_tax", "1.10"),
                Map.of("end_date", "20241231", "ann_date", "20250428",
                        "div_proc", "实施", "cash_div_tax", "0.80"));
    });

    FundamentalDataVO result = new TushareStockDataProvider(
            client, indicatorCalculator, mockNewsSearchProvider).getFundamentalData("600285.SH");

    assertEquals(new BigDecimal("1.10"), result.getDps());
}
```

```java
@Test
void getFundamentalDataPreservesZeroImplementedDividend() {
    TushareApiClient client = createTestClient((apiName, params, fields) ->
            "dividend".equals(apiName)
                    ? List.of(Map.of("end_date", "20251231", "ann_date", "20260428",
                            "div_proc", "实施", "cash_div_tax", "0"))
                    : Collections.emptyList());

    FundamentalDataVO result = new TushareStockDataProvider(
            client, indicatorCalculator, mockNewsSearchProvider).getFundamentalData("600285.SH");

    assertEquals(BigDecimal.ZERO, result.getDps());
}
```

```java
@Test
void getFundamentalDataKeepsDpsNullWithoutImplementedDividend() {
    TushareApiClient client = createTestClient((apiName, params, fields) ->
            "dividend".equals(apiName)
                    ? List.of(Map.of("end_date", "20251231", "ann_date", "20260428",
                            "div_proc", "股东大会通过", "cash_div_tax", "1.10"))
                    : Collections.emptyList());

    FundamentalDataVO result = new TushareStockDataProvider(
            client, indicatorCalculator, mockNewsSearchProvider).getFundamentalData("600285.SH");

    assertNull(result.getDps());
}
```

- [x] **步骤 3：运行目标测试并确认失败**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-infra -am "-Dtest=TushareStockDataProviderTest#getFundamentalData_success+getFundamentalDataSelectsLatestImplementedDividend+getFundamentalDataPreservesZeroImplementedDividend+getFundamentalDataKeepsDpsNullWithoutImplementedDividend" -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：FAIL；provider 尚未调用 `dividend`，DPS 断言得到 `null`。

- [x] **步骤 4：创建分红 DTO**

```java
package denny.ai.agent.trading.infra.provider.tushare.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** Tushare dividend 接口响应。 */
@Data
@EqualsAndHashCode(callSuper = false)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TushareDividendDTO extends TushareBaseDTO {
    private String tsCode;
    private String endDate;
    private String annDate;
    private String divProc;
    private BigDecimal cashDivTax;
    private String recordDate;
    private String exDate;
    private String payDate;
}
```

- [x] **步骤 5：实现查询、筛选和映射**

在 `getFundamentalData()` 中加载分红并映射：

```java
TushareDividendDTO dividend = selectLatestImplementedDividend(
        apiClient.callGenericStrict(
                TushareDividendDTO.class,
                "dividend",
                Map.of("ts_code", tsCode),
                "ts_code,end_date,ann_date,div_proc,cash_div_tax,record_date,ex_date,pay_date"));
```

在 builder 链中增加：

```java
.dps(dividend == null ? null : dividend.getCashDivTax())
```

增加私有选择函数：

```java
private TushareDividendDTO selectLatestImplementedDividend(List<TushareDividendDTO> rows) {
    if (rows == null || rows.isEmpty()) {
        return null;
    }
    Comparator<String> dates = Comparator.nullsFirst(Comparator.naturalOrder());
    return rows.stream()
            .filter(Objects::nonNull)
            .filter(row -> "实施".equals(row.getDivProc()))
            .filter(row -> row.getCashDivTax() != null)
            .max(Comparator.comparing(TushareDividendDTO::getEndDate, dates)
                    .thenComparing(TushareDividendDTO::getAnnDate, dates))
            .orElse(null);
}
```

- [x] **步骤 6：运行 provider 全量测试并提交**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-infra -am -Dtest=TushareStockDataProviderTest -Dsurefire.failIfNoSpecifiedTests=false test
git add ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareDividendDTO.java ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/TushareStockDataProvider.java ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareStockDataProviderTest.java
git commit -m "feat: populate implemented DPS from Tushare"
```

预期：`TushareStockDataProviderTest` 全部 PASS。

### 任务 2：验证基本面分析师收到股东回报字段

| 任务 | status |
|------|------|
| 任务 2：验证基本面分析师收到股东回报字段 | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/FundamentalAnalystValuationPolicyTest.java`

- [x] **步骤 1：增加 analyst 输入序列化断言**

```java
@Test
void stockDataIncludesShareholderReturnFields() {
    String input = node.buildStockData(
            StockInfoVO.builder().ticker("600285.SH").build(),
            FundamentalDataVO.builder()
                    .dps(new BigDecimal("1.10"))
                    .dividendYield(4.0468)
                    .build());

    assertTrue(input.contains("\"dps\":1.10"));
    assertTrue(input.contains("\"dividendYield\":4.0468"));
}
```

- [x] **步骤 2：运行 domain 目标测试并提交**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am -Dtest=FundamentalAnalystValuationPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
git add ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/FundamentalAnalystValuationPolicyTest.java
git commit -m "test: verify analyst receives shareholder returns"
```

预期：测试 PASS，证明无需修改 prompt 渲染接口即可传入两个字段。

### 任务 3：增加真实 Tushare 在线断言

| 任务 | status |
|------|------|
| 任务 3：增加真实 Tushare 在线断言 | append |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareFundamentalDataIntegrationTest.java`

- [ ] **步骤 1：增加 DPS 和股息率有效性断言**

在现有 `assertAll` 中加入：

```java
() -> assertNotNull(result.getDps(), "dps"),
() -> assertNotNull(result.getDividendYield(), "dividendYield"),
() -> assertTrue(result.getDps().compareTo(BigDecimal.ZERO) >= 0, "dps 应大于等于零"),
() -> assertTrue(result.getDividendYield() >= 0, "dividendYield 应大于等于零")
```

保留已有逐字段断言，不写死 `600285.SH` 的具体 DPS，避免公司下一期已实施分红后造成无意义失败。

- [ ] **步骤 2：运行真实在线集成测试**

```powershell
mvn -Pintegration -pl ai-agent-study-trading/ai-agent-study-trading-infra -am -Dit.test=TushareFundamentalDataIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify
```

预期：存在 `TUSHARE_TOKEN` 时真实请求 PASS 且 `Tests skipped: 0`；权限、参数、响应协议或字段缺失均使测试失败。

- [ ] **步骤 3：提交在线测试**

```powershell
git add ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareFundamentalDataIntegrationTest.java
git commit -m "test: verify DPS and dividend yield online"
```

### 任务 4：全量验证与计划回填

| 任务 | status |
|------|------|
| 任务 4：全量验证与计划回填 | append |

**文件：**
- 修改：`docs/superpowers/plans/2026-08-10-dps-dividend-yield.md`

- [ ] **步骤 1：运行相关模块默认测试**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-infra,ai-agent-study-trading/ai-agent-study-trading-domain -am -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：BUILD SUCCESS，所有默认测试通过。

- [ ] **步骤 2：运行项目编译与差异检查**

```powershell
mvn clean compile -q
git diff --check
git status --short --branch
```

预期：编译退出码为 0，`git diff --check` 无输出，仅计划状态回填尚未提交。

- [ ] **步骤 3：回填任务状态和验证结果**

把四个任务表的 `status` 从 `append` 改为 `pass`，把已执行步骤的 `- [ ]` 改为 `- [x]`，并在文末记录 provider 测试、domain 测试、在线集成测试及编译的实际结果。

- [ ] **步骤 4：提交计划回填**

```powershell
git add docs/superpowers/plans/2026-08-10-dps-dividend-yield.md
git commit -m "docs: record DPS verification results"
git status --short --branch
```

预期：工作树干净，分支只包含本需求的设计、实现、测试和验证提交。
