# PE_TTM 兼容性修复实现计划

> **致智能体工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 来按任务逐步实现本计划。步骤使用复选框（`- [ ]`）语法进行跟踪。

**目标：** 从 Tushare `daily_basic` 提供权威 `PE_TTM`，阻止基本面分析师用季度 EPS 生成错误 PE，同时保持跨角色、ToolCallback 和 JSON 契约兼容。

**架构：** API VO 增量增加语义明确的估值字段，并用双向兼容 getter 保留旧生产者和旧消费者。infra 层以最新交易日为边界读取 `daily_basic`；domain 层向基本面 `stockData` 注入估值政策，默认只使用 `peTtm`，缺失时不做 EPS 推算。

**技术栈：** Java 17、Spring Boot 3.5、Lombok、Jackson、Tushare Public API、JUnit 5、Mockito、Maven

---

## 文件结构

- 创建 `TushareDailyBasicDTO.java`：承载 `daily_basic` 原始估值快照。
- 修改 `StockInfoVO.java`、`FundamentalDataVO.java`：新增估值字段并保留旧字段兼容。
- 修改 `TushareStockDataProvider.java`：查询 `daily_basic`，从 `fina_indicator` 移除错误估值字段。
- 修改 `TradingToolCallbacks.java`、`MockStockDataProvider.java`：迁移共享工具输出和 Mock。
- 修改 `FundamentalAnalystNode.java`：注入 PE_TTM 口径约束。
- 创建或修改 API、infra、domain 测试，覆盖兼容契约、同交易日映射、工具输出和羚锐制药回归。

### 任务 1：建立新旧估值字段兼容契约

| 任务 | status |
|------|------|
| 任务 1：建立新旧估值字段兼容契约 | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/vo/StockInfoVO.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/vo/FundamentalDataVO.java`
- 创建：`ai-agent-study-trading/ai-agent-study-trading-api/src/test/java/denny/ai/agent/trading/api/vo/ValuationCompatibilityContractTest.java`

- [x] **步骤 1：编写失败的兼容契约测试**

```java
class ValuationCompatibilityContractTest {
    @Test
    void newFieldsAreVisibleThroughLegacyGetters() {
        StockInfoVO stock = StockInfoVO.builder().peTtm(16.6).pb(3.2).build();
        assertEquals(16.6, stock.getPeRatio());
        assertEquals(3.2, stock.getPbRatio());
    }

    @Test
    void legacyFieldsAreVisibleThroughNewGetters() {
        FundamentalDataVO data = FundamentalDataVO.builder()
                .peRatio(18.4).pbRatio(2.8).build();
        assertEquals(18.4, data.getPeTtm());
        assertEquals(2.8, data.getPb());
    }

    @Test
    void newFieldWinsWhenBothContractsArePresent() {
        FundamentalDataVO data = FundamentalDataVO.builder()
                .peTtm(16.6).peRatio(51.1).build();
        assertEquals(16.6, data.getPeTtm());
        assertEquals(16.6, data.getPeRatio());
    }
}
```

- [x] **步骤 2：运行测试验证失败**

```powershell
mvn -f ai-agent-study-trading/pom.xml -pl ai-agent-study-trading-api -am -Dtest=ValuationCompatibilityContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：编译失败，提示 `peTtm(double)`、`pb(double)` 或新 getter 不存在。

- [x] **步骤 3：增加新字段和兼容 getter**

两个 VO 均增加：

```java
private Double pe;
private Double peTtm;
private Double pb;
private BigDecimal totalMv;
private BigDecimal circMv;
private String valuationTradeDate;

public Double getPeTtm() {
    return peTtm != null ? peTtm : peRatio;
}

@Deprecated
public Double getPeRatio() {
    return peTtm != null ? peTtm : peRatio;
}

public Double getPb() {
    return pb != null ? pb : pbRatio;
}

@Deprecated
public Double getPbRatio() {
    return pb != null ? pb : pbRatio;
}
```

保留 `peRatio`、`pbRatio`、`marketCap` 及其 Builder 写入口；`marketCap` 不回退到 `totalMv`，避免单位静默改变。

- [x] **步骤 4：验证 JSON 兼容并使测试通过**

补充 Jackson 断言：

```java
@Test
void jsonKeepsLegacyFieldsWithoutChangingMarketCapUnit() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode json = mapper.valueToTree(StockInfoVO.builder()
            .peTtm(16.6)
            .totalMv(new BigDecimal("1257000"))
            .build());
    assertEquals(16.6, json.path("peTtm").doubleValue());
    assertEquals(16.6, json.path("peRatio").doubleValue());
    assertTrue(json.path("marketCap").isNull());
    assertEquals(new BigDecimal("1257000"), json.path("totalMv").decimalValue());
}
```

重跑步骤 2，预期 `PASS`。

- [x] **步骤 5：提交兼容契约**

```powershell
git add -- ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/vo/StockInfoVO.java ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/vo/FundamentalDataVO.java ai-agent-study-trading/ai-agent-study-trading-api/src/test/java/denny/ai/agent/trading/api/vo/ValuationCompatibilityContractTest.java
git commit -m "fix: add compatible PE TTM valuation contract"
```

### 任务 2：从 daily_basic 获取估值快照

| 任务 | status |
|------|------|
| 任务 2：从 daily_basic 获取估值快照 | pass |

**文件：**
- 创建：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareDailyBasicDTO.java`
- 创建：`ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareDailyBasicDTOTest.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/tushare/dto/TushareFinaIndicatorDTO.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/TushareStockDataProvider.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/TushareStockDataProviderTest.java`

- [x] **步骤 1：编写 DTO 映射失败测试**

```java
@Test
void mapsDailyBasicValuationFields() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    TushareDailyBasicDTO dto = mapper.readValue("""
        {"ts_code":"600285.SH","trade_date":"20260807","close":22.24,
         "pe":16.8,"pe_ttm":16.6,"pb":3.2,"total_mv":1257000,"circ_mv":1249000}
        """, TushareDailyBasicDTO.class);
    assertEquals(16.6, dto.getPeTtm());
    assertEquals(new BigDecimal("1257000"), dto.getTotalMv());
    assertEquals("2026-08-07", dto.getTradeDateFormatted());
}
```

- [x] **步骤 2：运行测试验证 DTO 不存在**

```powershell
mvn -f ai-agent-study-trading/pom.xml -pl ai-agent-study-trading-infra -am -Dtest=TushareDailyBasicDTOTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：编译失败，提示 `TushareDailyBasicDTO` 不存在。

- [x] **步骤 3：创建只负责反序列化的 DTO**

```java
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TushareDailyBasicDTO extends TushareBaseDTO {
    private String tradeDate;
    private BigDecimal close;
    private Double pe;
    private Double peTtm;
    private Double pb;
    private BigDecimal totalMv;
    private BigDecimal circMv;

    public String getTradeDateFormatted() {
        return fmtDate(tradeDate);
    }
}
```

重跑步骤 2，预期 `PASS`。

- [x] **步骤 4：编写 Provider 失败测试**

在 `TushareStockDataProviderTest` 增加：

```java
@Test
void getStockInfoUsesDailyBasicFromPriceTradeDate() {
    TushareApiClient client = createTestClient((apiName, params, fields) -> {
        if ("stock_basic".equals(apiName)) {
            return List.of(Map.of("ts_code", "600285.SH", "name", "羚锐制药", "exchange", "SSE"));
        }
        if ("daily".equals(apiName) && fields.contains("close")) {
            return List.of(Map.of("ts_code", "600285.SH", "trade_date", "20260807",
                    "close", "22.24", "vol", "92306"));
        }
        if ("daily".equals(apiName)) {
            return List.of(Map.of("high", "24.75", "low", "19.00"));
        }
        if ("daily_basic".equals(apiName)) {
            assertEquals("20260807", params.get("trade_date"));
            return List.of(Map.of("ts_code", "600285.SH", "trade_date", "20260807",
                    "pe", "16.8", "pe_ttm", "16.6", "pb", "3.2",
                    "total_mv", "1257000", "circ_mv", "1249000"));
        }
        return Collections.emptyList();
    });
    StockInfoVO result = new TushareStockDataProvider(
            client, indicatorCalculator, mockNewsSearchProvider).getStockInfo("600285.SH");
    assertEquals(16.6, result.getPeTtm());
    assertEquals(16.6, result.getPeRatio());
    assertEquals("2026-08-07", result.getValuationTradeDate());
}

@Test
void getFundamentalDataUsesLatestDailyBasicPeTtm() {
    TushareApiClient client = createTestClient((apiName, params, fields) -> {
        if ("fina_indicator".equals(apiName) && fields.contains("roe")) {
            return List.of(Map.of("eps", "0.435", "roe", "6.9887"));
        }
        if ("daily_basic".equals(apiName)) {
            return List.of(Map.of("trade_date", "20260807", "pe_ttm", "16.6"));
        }
        return Collections.emptyList();
    });
    FundamentalDataVO result = new TushareStockDataProvider(
            client, indicatorCalculator, mockNewsSearchProvider).getFundamentalData("600285.SH");
    assertEquals(16.6, result.getPeTtm());
    assertEquals(new BigDecimal("0.435"), result.getEps());
}

@Test
void missingDailyBasicKeepsValuationNull() {
    TushareApiClient client = createTestClient((apiName, params, fields) -> {
        if ("fina_indicator".equals(apiName) && fields.contains("roe")) {
            return List.of(Map.of("eps", "0.435", "roe", "6.9887"));
        }
        return Collections.emptyList();
    });
    FundamentalDataVO result = new TushareStockDataProvider(
            client, indicatorCalculator, mockNewsSearchProvider).getFundamentalData("600285.SH");
    assertNull(result.getPeTtm());
    assertEquals(new BigDecimal("0.435"), result.getEps());
}
```

测试用 `TestableTushareApiClient` 同时覆盖严格调用：

```java
@Override
public <T> List<T> callGenericStrict(Class<T> dtoClass, String apiName,
                                     Map<String, Object> params, String fields) {
    List<Map<String, String>> rows = handler.handle(apiName, params, fields);
    if (rows == null) {
        return super.callGenericStrict(dtoClass, apiName, params, fields);
    }
    return convertRows(dtoClass, rows);
}
```

将现有 `callGeneric` 中的 DTO 转换循环提取为 `convertRows`，供两个覆盖方法复用。

- [x] **步骤 5：运行 Provider 测试验证失败**

```powershell
mvn -f ai-agent-study-trading/pom.xml -pl ai-agent-study-trading-infra -am -Dtest=TushareStockDataProviderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：新增测试失败，尚未调用 `daily_basic`。

- [x] **步骤 6：实现估值查询与映射**

```java
private TushareDailyBasicDTO loadValuation(String tsCode, String tradeDate) {
    Map<String, Object> params = tradeDate == null
            ? Map.of("ts_code", tsCode,
                    "start_date", LocalDate.now().minusYears(1).format(TUSHARE_DATE_FORMAT),
                    "end_date", LocalDate.now().format(TUSHARE_DATE_FORMAT))
            : Map.of("ts_code", tsCode, "trade_date", tradeDate);
    List<TushareDailyBasicDTO> rows = apiClient.callGenericStrict(
            TushareDailyBasicDTO.class, "daily_basic", params,
            "ts_code,trade_date,close,pe,pe_ttm,pb,total_mv,circ_mv");
    return rows.stream()
            .max(Comparator.comparing(TushareDailyBasicDTO::getTradeDate,
                    Comparator.nullsLast(String::compareTo)))
            .orElse(null);
}
```

`getStockInfo` 使用最新 `daily.tradeDate` 精确查询；`getFundamentalData` 查询最新快照。Builder 写入 `pe`、`peTtm`、`pb`、`totalMv`、`circMv`、`valuationTradeDate`。精确日期不一致时记录警告且不合并。

- [x] **步骤 7：移除 fina_indicator 的错误估值请求**

当前期字段改为：

```java
"ann_date,end_date,roe,grossprofit_margin,netprofit_margin," +
"debt_to_assets,current_ratio,eps,revenue,net_profit,div_ratio,total_assets"
```

从 `TushareFinaIndicatorDTO` 删除 `pe`、`pbRatio`、`psRatio`、`peg`；`FundamentalDataVO.psRatio/pegRatio` 兼容字段保留为空。

- [x] **步骤 8：运行 DTO 和 Provider 测试**

```powershell
mvn -f ai-agent-study-trading/pom.xml -pl ai-agent-study-trading-infra -am -Dtest=TushareStockDataProviderTest,TushareDailyBasicDTOTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：全部 `PASS`。

- [x] **步骤 9：提交数据源修复**

```powershell
git add -- ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider
git commit -m "fix: load PE TTM from Tushare daily basic"
```

### 任务 3：迁移共享角色消费者和 ToolCallback

| 任务 | status |
|------|------|
| 任务 3：迁移共享角色消费者和 ToolCallback | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/tools/TradingToolCallbacks.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/TushareStockDataProvider.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/MockStockDataProvider.java`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/tools/TradingToolCallbacksTest.java`

- [x] **步骤 1：编写共享工具输出失败测试**

```java
@Test
void toolsLabelPeTtmAndMarketValueUnit() {
    when(mockProvider.getStockInfo("600285.SH")).thenReturn(StockInfoVO.builder()
            .ticker("600285.SH").pe(16.8).peTtm(16.6).pb(3.2)
            .totalMv(new BigDecimal("1257000")).build());
    when(mockProvider.getFundamentalData("600285.SH")).thenReturn(FundamentalDataVO.builder()
            .pe(16.8).peTtm(16.6).eps(new BigDecimal("0.435")).build());

    String stockText = tradingToolCallbacks.getStockInfoCallback()
            .call("{\"ticker\":\"600285.SH\"}");
    String fundamentalText = tradingToolCallbacks.getFundamentalDataCallback()
            .call("{\"ticker\":\"600285.SH\"}");

    assertTrue(stockText.contains("滚动市盈率(PE_TTM): 16.60"));
    assertTrue(stockText.contains("总市值(万元): 1257000"));
    assertTrue(fundamentalText.contains("滚动市盈率(PE_TTM): 16.60"));
    assertFalse(fundamentalText.contains("51"));
}
```

- [x] **步骤 2：运行测试验证旧标签仍存在**

```powershell
mvn -f ai-agent-study-trading/pom.xml -pl ai-agent-study-trading-infra -am -Dtest=TradingToolCallbacksTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：新增测试失败，输出仍使用旧 PE 标签或没有新市值字段。

- [x] **步骤 3：迁移格式化、情绪推导和 Mock**

```java
appendLine(sb, "静态市盈率(PE)", vo.getPe(), "%.2f");
appendLine(sb, "滚动市盈率(PE_TTM)", vo.getPeTtm(), "%.2f");
appendLine(sb, "市净率(PB)", vo.getPb(), "%.2f");
appendLine(sb, "总市值(万元)", vo.getTotalMv());
appendLine(sb, "流通市值(万元)", vo.getCircMv());
```

`totalMv/circMv` 不调用会按“元”缩放的 `appendMoney`。`deriveAnalystScore` 改读 `getPeTtm()`；Mock 使用新字段构造合理估值样本。ToolCallback 名称、数量、输入 Schema 和授权集合不变。

- [x] **步骤 4：运行 infra 模块测试**

```powershell
mvn -f ai-agent-study-trading/pom.xml -pl ai-agent-study-trading-infra -am test
```

预期：`BUILD SUCCESS`。

- [x] **步骤 5：提交共享消费者迁移**

```powershell
git add -- ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/tools/TradingToolCallbacks.java ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/TushareStockDataProvider.java ai-agent-study-trading/ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/MockStockDataProvider.java ai-agent-study-trading/ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/tools/TradingToolCallbacksTest.java
git commit -m "fix: migrate trading consumers to PE TTM"
```

### 任务 4：阻止基本面分析师从季度 EPS 补算 PE

| 任务 | status |
|------|------|
| 任务 4：阻止基本面分析师从季度 EPS 补算 PE | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/FundamentalAnalystNode.java`
- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/FundamentalAnalystValuationPolicyTest.java`

- [x] **步骤 1：编写羚锐制药回归测试**

```java
@Test
void stockDataMakesPeTtmAuthoritativeAndForbidsQuarterlyEpsDerivation() {
    String input = node.buildStockData(
            StockInfoVO.builder().ticker("600285.SH")
                    .currentPrice(new BigDecimal("22.24")).build(),
            FundamentalDataVO.builder().eps(new BigDecimal("0.435"))
                    .peTtm(16.6).build());
    assertTrue(input.contains("\"peTtm\":16.6"));
    assertTrue(input.contains("默认且权威的 PE 口径"));
    assertTrue(input.contains("禁止使用 currentPrice / eps"));
    assertFalse(input.contains("51.1"));
}

@Test
void missingPeTtmRequiresUnavailableMessage() {
    String input = node.buildStockData(
            StockInfoVO.builder().currentPrice(new BigDecimal("22.24")).build(),
            FundamentalDataVO.builder().eps(new BigDecimal("0.435")).build());
    assertTrue(input.contains("PE_TTM 不可用"));
    assertTrue(input.contains("不得自行补算"));
}
```

测试按以下方式注入现有编码器，不启动 Spring 或 LLM：

```java
@BeforeEach
void setUp() {
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    StructuredPayloadCodec codec = new StructuredPayloadCodec(new ObjectMapper(), validator);
    node = new FundamentalAnalystNode();
    ReflectionTestUtils.setField(node, "structuredPayloadCodec", codec);
}
```

- [x] **步骤 2：运行测试验证 buildStockData 不存在**

```powershell
mvn -f ai-agent-study-trading/pom.xml -pl ai-agent-study-trading-domain -am -Dtest=FundamentalAnalystValuationPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：编译失败，提示 `buildStockData` 不存在。

- [x] **步骤 3：实现可测试的估值输入政策**

```java
String buildStockData(StockInfoVO stockInfo, FundamentalDataVO data) {
    String policy = data.getPeTtm() == null
            ? "估值口径约束：PE_TTM 不可用；不得自行补算，禁止使用 currentPrice / eps。"
            : "估值口径约束：peTtm 是默认且权威的 PE 口径，必须标为滚动市盈率或 PE_TTM；" +
              "禁止使用 currentPrice / eps 生成其他 PE。";
    return policy + System.lineSeparator() + structuredPayloadCodec.toJson(Map.of(
            "stockInfo", stockInfo,
            "fundamentalData", data));
}
```

`generateReport` 改用该方法，日志改记 `peTtm`。不修改数据库中的 6002–6013 Prompt 集合，也不改变其他 role 的模板版本。

- [x] **步骤 4：运行 domain 模块测试**

```powershell
mvn -f ai-agent-study-trading/pom.xml -pl ai-agent-study-trading-domain -am test
```

预期：`BUILD SUCCESS`，现有 Prompt 快照测试不变。

- [x] **步骤 5：提交基本面防线**

```powershell
git add -- ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/FundamentalAnalystNode.java ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/FundamentalAnalystValuationPolicyTest.java
git commit -m "fix: prevent quarterly EPS PE derivation"
```

### 任务 5：执行兼容性回归和交付检查

| 任务 | status |
|------|------|
| 任务 5：执行兼容性回归和交付检查 | append |

**文件：**
- 修改：`docs/superpowers/plans/2026-08-09-pe-ttm-compatibility-bugfix.md`（仅更新状态）

- [ ] **步骤 1：扫描废弃字段依赖**

```powershell
rg -n -F -e 'getPeRatio' -e 'getPbRatio' -e 'getMarketCap' ai-agent-study-trading -g '*.java'
```

预期：命中仅限 VO 兼容实现、兼容测试和明确保留的旧契约；Provider、节点、工具和确定性计算不再读取旧 getter。

- [ ] **步骤 2：运行 trading 聚合回归**

```powershell
mvn -f ai-agent-study-trading/pom.xml test
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 3：运行应用层工具白名单测试**

```powershell
mvn -pl ai-agent-study-app -am -Dtest=TradingToolAllowlistConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`，6002–6013 的 Tool 集合没有增加、删除或改名。

- [ ] **步骤 4：检查工作区和提交范围**

```powershell
git status --short
git diff --check HEAD~4..HEAD
```

预期：用户原有配置、交易报告和 Python 缓存保持原状；实现提交不包含这些文件；`git diff --check` 无错误。

- [ ] **步骤 5：更新状态并提交交付记录**

将五个任务状态改为 `pass`，勾选复选框，仅提交本计划：

```powershell
git add -- docs/superpowers/plans/2026-08-09-pe-ttm-compatibility-bugfix.md
git commit -m "docs: complete PE TTM compatibility bugfix plan"
```
