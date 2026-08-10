# 新闻五字段摘要级分析实现计划

> **致智能体工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 来按任务逐步实现本计划。步骤使用复选框（`- [ ]`）语法进行跟踪。

**目标：** 在不访问新闻详情页的前提下，仅让最近 15 天的新闻进入分析，并把标题、摘要、来源、发布时间、链接和 `sentimentScore` 完整传给 LLM。

**架构：** 新增一个领域预处理组件，在关键词评分前完成 15 天时间窗口过滤，并返回可观测的过滤统计。现有结构化处理器继续负责清洗和精确去重，同时补充 `url` 和摘要级分析约束；运行时 Prompt 通过已有 `stockData` 占位符接收这些约束，不改写历史 Prompt 迁移。

**技术栈：** Java 17、Spring、JUnit 5、Fastjson、Maven

---

## 文件结构

- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/signal/NewsAnalysisPreprocessor.java`
  - 负责解析发布时间、执行 15 天窗口过滤、保留未知时间新闻，并在过滤后调用现有关键词评分增强器。
- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/signal/NewsAnalysisPreprocessorTest.java`
  - 锁定时间边界、未来容差、未知时间保留和“先过滤后评分”的行为。
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalystNode.java`
  - 使用预处理组件替代直接调用 `NewsItemSentimentEnricher`。
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessor.java`
  - 将 `url` 写入 LLM 新闻条目，并补充五字段与摘要级证据约束。
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessorTest.java`
  - 验证五字段透传、URL 降级、去重后的 URL 归属以及 Prompt 约束。

### 任务 1：实现 15 天新闻预处理器

| 任务 | status |
|------|------|
| 任务 1：实现 15 天新闻预处理器 | pass |

**文件：**
- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/signal/NewsAnalysisPreprocessor.java`
- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/signal/NewsAnalysisPreprocessorTest.java`

- [ ] **步骤 1：编写时间窗口和评分顺序测试**

创建测试类，使用固定的 `2026-08-10 12:00 Asia/Shanghai` 作为分析时间。测试必须覆盖：15 天边界保留、边界前 1 秒排除、未来 10 分钟保留、未来 10 分 1 秒排除、空时间保留、非法时间保留，以及过期新闻不被关键词评分。

```java
package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.NewsItemVO;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NewsAnalysisPreprocessorTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZonedDateTime NOW =
            ZonedDateTime.of(2026, 8, 10, 12, 0, 0, 0, SHANGHAI);

    private final NewsAnalysisPreprocessor preprocessor = new NewsAnalysisPreprocessor(
            new NewsItemSentimentEnricher(new NewsKeywordSentimentScorer()));

    @Test
    void keepsInclusiveFifteenDayBoundaryAndFutureTolerance() {
        List<NewsItemVO> items = new ArrayList<>(List.of(
                item("2026-07-26 12:00:00", "公司回购"),
                item("2026-08-10 12:10:00", "公司获批新药")
        ));

        NewsAnalysisPreprocessor.Result result = preprocessor.prepare(items, NOW);

        assertEquals(2, result.newsItems().size());
        assertEquals(0, result.staleCount());
        assertEquals(0, result.futureCount());
        assertNotNull(result.newsItems().get(0).getSentimentScore());
        assertNotNull(result.newsItems().get(1).getSentimentScore());
    }

    @Test
    void excludesOlderAndClearlyFutureNewsBeforeScoring() {
        NewsItemVO stale = item("2026-07-26 11:59:59", "公司回购");
        NewsItemVO future = item("2026-08-10 12:10:01", "公司获批新药");

        NewsAnalysisPreprocessor.Result result = preprocessor.prepare(
                new ArrayList<>(List.of(stale, future)), NOW);

        assertTrue(result.newsItems().isEmpty());
        assertEquals(1, result.staleCount());
        assertEquals(1, result.futureCount());
        assertNull(stale.getSentimentScore());
        assertNull(future.getSentimentScore());
    }

    @Test
    void keepsMissingAndUnparseableTimesAsUnknown() {
        List<NewsItemVO> items = new ArrayList<>(List.of(
                item(null, "公司回购"),
                item("时间未知", "公司减持")
        ));

        NewsAnalysisPreprocessor.Result result = preprocessor.prepare(items, NOW);

        assertEquals(2, result.newsItems().size());
        assertEquals(2, result.unknownTimeCount());
        assertNotNull(result.newsItems().get(0).getSentimentScore());
        assertNotNull(result.newsItems().get(1).getSentimentScore());
    }

    @Test
    void acceptsMinutePrecisionFromCurrentProvider() {
        NewsAnalysisPreprocessor.Result result = preprocessor.prepare(
                new ArrayList<>(List.of(item("2026-08-04 15:48", "公司解除质押"))), NOW);

        assertEquals(1, result.newsItems().size());
        assertEquals(0, result.unknownTimeCount());
    }

    private NewsItemVO item(String publishTime, String title) {
        return NewsItemVO.builder().publishTime(publishTime).title(title).build();
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=NewsAnalysisPreprocessorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：编译失败，提示 `NewsAnalysisPreprocessor` 不存在。

- [ ] **步骤 3：实现最小预处理器**

创建组件。只支持当前 provider 已产生的 `yyyy-MM-dd HH:mm:ss` 和 `yyyy-MM-dd HH:mm`，无法解析时按未知时间保留。返回不可变列表副本，避免调用方意外改变过滤结果。

```java
package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.NewsItemVO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Component
public class NewsAnalysisPreprocessor {

    public static final ZoneId ANALYSIS_ZONE = ZoneId.of("Asia/Shanghai");
    static final int LOOKBACK_DAYS = 15;
    static final int FUTURE_TOLERANCE_MINUTES = 10;

    private static final List<DateTimeFormatter> TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    );

    private final NewsItemSentimentEnricher sentimentEnricher;

    public NewsAnalysisPreprocessor(NewsItemSentimentEnricher sentimentEnricher) {
        this.sentimentEnricher = sentimentEnricher;
    }

    public Result prepare(List<NewsItemVO> items, ZonedDateTime analysisTime) {
        ZonedDateTime normalizedTime = analysisTime.withZoneSameInstant(ANALYSIS_ZONE);
        LocalDateTime earliest = normalizedTime.minusDays(LOOKBACK_DAYS).toLocalDateTime();
        LocalDateTime latest = normalizedTime.plusMinutes(FUTURE_TOLERANCE_MINUTES).toLocalDateTime();
        List<NewsItemVO> included = new ArrayList<>();
        int staleCount = 0;
        int futureCount = 0;
        int unknownTimeCount = 0;

        for (NewsItemVO item : items == null ? List.<NewsItemVO>of() : items) {
            if (item == null) {
                continue;
            }
            LocalDateTime publishedAt = parse(item.getPublishTime());
            if (publishedAt == null) {
                unknownTimeCount++;
                included.add(item);
            } else if (publishedAt.isBefore(earliest)) {
                staleCount++;
            } else if (publishedAt.isAfter(latest)) {
                futureCount++;
            } else {
                included.add(item);
            }
        }

        sentimentEnricher.enrich(included);
        return new Result(List.copyOf(included), staleCount, futureCount, unknownTimeCount);
    }

    private LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (DateTimeFormatter formatter : TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(value.trim(), formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next provider-supported format.
            }
        }
        return null;
    }

    public record Result(List<NewsItemVO> newsItems,
                         int staleCount,
                         int futureCount,
                         int unknownTimeCount) {
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行任务 1 步骤 2 的同一命令。

预期：`NewsAnalysisPreprocessorTest` 的 4 个测试全部通过，输出 `BUILD SUCCESS`。

- [ ] **步骤 5：提交预处理器**

```powershell
git add ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/signal/NewsAnalysisPreprocessor.java ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/signal/NewsAnalysisPreprocessorTest.java
git commit -m "feat: filter stale news before scoring"
```

### 任务 2：接入新闻分析节点

| 任务 | status |
|------|------|
| 任务 2：接入新闻分析节点 | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalystNode.java:45-96`
- 测试：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/signal/NewsAnalysisPreprocessorTest.java`

- [ ] **步骤 1：增强预处理结果测试，锁定输入顺序和原生评分保留行为**

在 `NewsAnalysisPreprocessorTest` 增加测试，证明过滤结果保持 provider 顺序，现有原生评分不会被覆盖。

```java
@Test
void preservesProviderOrderAndNativeScore() {
    NewsItemVO first = item("2026-08-10 10:00:00", "普通公告");
    first.setSentimentScore(0.25);
    NewsItemVO second = item("2026-08-09 10:00:00", "公司回购");

    NewsAnalysisPreprocessor.Result result = preprocessor.prepare(
            new ArrayList<>(List.of(first, second)), NOW);

    assertSame(first, result.newsItems().get(0));
    assertSame(second, result.newsItems().get(1));
    assertEquals(0.25, first.getSentimentScore(), 0.000001);
    assertTrue(second.getSentimentScore() > 0);
}
```

- [ ] **步骤 2：运行测试并确认通过**

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=NewsAnalysisPreprocessorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：5 个测试全部通过；此步骤锁定现有预处理器契约后再修改节点。

- [ ] **步骤 3：让节点使用预处理组件**

将 `NewsAnalystNode` 对 `NewsItemSentimentEnricher` 的依赖替换为 `NewsAnalysisPreprocessor`：

```java
import denny.ai.agent.trading.domain.signal.NewsAnalysisPreprocessor;

import java.time.ZonedDateTime;
```

字段改为：

```java
@Resource private NewsAnalysisPreprocessor newsAnalysisPreprocessor;
```

将直接评分代码：

```java
List<NewsItemVO> newsItems = TargetBoundStockDataProvider
        .bind(dataProvider, context.getTargetContext()).getNews(10);
newsItemSentimentEnricher.enrich(newsItems);
```

替换为：

```java
List<NewsItemVO> fetchedNews = TargetBoundStockDataProvider
        .bind(dataProvider, context.getTargetContext()).getNews(10);
NewsAnalysisPreprocessor.Result preprocessing = newsAnalysisPreprocessor.prepare(
        fetchedNews,
        ZonedDateTime.now(NewsAnalysisPreprocessor.ANALYSIS_ZONE));
List<NewsItemVO> newsItems = preprocessing.newsItems();

log.info("新闻时间窗口过滤完成: ticker={}, fetched={}, retained={}, stale={}, future={}, unknownTime={}",
        ticker, fetchedNews == null ? 0 : fetchedNews.size(), newsItems.size(), preprocessing.staleCount(),
        preprocessing.futureCount(), preprocessing.unknownTimeCount());
```

保留后续 `generateReport(stockInfo, newsItems, dynamicContext)`，确保只有过滤后的列表进入结构化处理器和 LLM。

- [ ] **步骤 4：运行编译和相关测试**

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=NewsAnalysisPreprocessorTest,NewsItemSentimentEnricherTest,NewsKeywordSentimentScorerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：测试全部通过，`NewsAnalystNode` 编译成功。

- [ ] **步骤 5：提交节点接入**

```powershell
git add ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalystNode.java ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/signal/NewsAnalysisPreprocessorTest.java
git commit -m "feat: apply news analysis time window"
```

### 任务 3：将 URL 加入结构化 LLM 输入

| 任务 | status |
|------|------|
| 任务 3：将 URL 加入结构化 LLM 输入 | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessor.java:42-63,220-357`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessorTest.java:15-58,265-289`

- [ ] **步骤 1：编写五字段和 URL 降级测试**

在 `NewsAnalysisStructuredProcessorTest` 增加：

```java
@Test
void includesAllFiveSourceFieldsAndSentimentScore() {
    NewsItemVO news = NewsItemVO.builder()
            .publishTime("2026-08-04 15:48:00")
            .source("财中社")
            .title("<em>羚锐制药</em>解除部分质押")
            .summary("公司发布公告，股东解除部分质押。")
            .url("https://finance.eastmoney.com/a/202608043831176255.html")
            .sentimentScore(0.2)
            .build();

    JSONObject item = JSON.parseObject(
            new NewsAnalysisStructuredProcessor().buildLlmInput(
                    "600285.SH", "羚锐制药", List.of(news)))
            .getJSONArray("newsItems").getJSONObject(0);

    assertEquals("2026-08-04 15:48:00", item.getString("publishTime"));
    assertEquals("财中社", item.getString("source"));
    assertEquals("羚锐制药解除部分质押", item.getString("title"));
    assertEquals("公司发布公告，股东解除部分质押", item.getString("summary"));
    assertEquals("https://finance.eastmoney.com/a/202608043831176255.html", item.getString("url"));
    assertEquals(0.2, item.getDoubleValue("sentimentScore"), 0.000001);
}

@Test
void keepsNewsWhenUrlIsMissing() {
    JSONObject item = JSON.parseObject(
            new NewsAnalysisStructuredProcessor().buildLlmInput(
                    "600285.SH", "羚锐制药",
                    List.of(NewsItemVO.builder().title("羚锐制药发布公告").build())))
            .getJSONArray("newsItems").getJSONObject(0);

    assertFalse(item.containsKey("url"));
    assertEquals("羚锐制药发布公告", item.getString("title"));
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=NewsAnalysisStructuredProcessorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：`includesAllFiveSourceFieldsAndSentimentScore` 因缺少 `url` 失败。

- [ ] **步骤 3：实现 URL 清洗和透传**

在构建 JSON 时加入：

```java
putIfNotBlank(item, "url", prepared.url);
```

在 `prepareItems` 创建 `PreparedNewsItem` 时，于 `summary` 后传入：

```java
cleanBasic(news.getUrl()),
```

在 `PreparedNewsItem` 中增加字段并调整构造器：

```java
private final String url;

private PreparedNewsItem(String publishTime,
                         String source,
                         String title,
                         String summary,
                         String url,
                         Double sentimentScore,
                         String content,
                         Boolean fullTextFetched,
                         String contentQuality,
                         String sourceReliability,
                         String evidenceLevel,
                         String evidenceQuality) {
    this.publishTime = publishTime;
    this.source = source;
    this.title = title;
    this.summary = summary;
    this.url = url;
    this.sentimentScore = sentimentScore;
    this.content = content;
    this.fullTextFetched = fullTextFetched;
    this.contentQuality = contentQuality;
    this.sourceReliability = sourceReliability;
    this.evidenceLevel = evidenceLevel;
    this.evidenceQuality = evidenceQuality;
}
```

精确重复判断仍只使用标题和摘要，避免同一篇转载新闻仅因链接不同而绕过去重；保留第一条新闻的 URL。

- [ ] **步骤 4：运行结构化处理器测试**

运行任务 3 步骤 2 的同一命令。

预期：`NewsAnalysisStructuredProcessorTest` 全部通过。

- [ ] **步骤 5：提交 URL 透传**

```powershell
git add ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessor.java ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessorTest.java
git commit -m "feat: include news URLs in LLM input"
```

### 任务 4：补充摘要级分析约束

| 任务 | status |
|------|------|
| 任务 4：补充摘要级分析约束 | pass |

**文件：**
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessor.java:62-64`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessorTest.java`

- [ ] **步骤 1：编写 Prompt 约束测试**

在 `NewsAnalysisStructuredProcessorTest` 增加：

```java
@Test
void instructionsDefineFiveFieldSummaryOnlyAnalysis() {
    JSONObject input = JSON.parseObject(
            new NewsAnalysisStructuredProcessor().buildLlmInput(
                    "600285.SH", "羚锐制药",
                    List.of(NewsItemVO.builder()
                            .title("公司发布公告")
                            .summary("摘要")
                            .build())));

    String instructions = input.getString("instructions");
    assertTrue(instructions.contains("title and summary determine article sentiment"));
    assertTrue(instructions.contains("source, publishTime, and url affect report-level confidence and dataQuality"));
    assertTrue(instructions.contains("do not access url"));
    assertTrue(instructions.contains("summary-level evidence"));
    assertTrue(instructions.contains("missing or unparseable publishTime"));
    assertTrue(instructions.contains("title and summary conflict"));
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=NewsAnalysisStructuredProcessorTest#instructionsDefineFiveFieldSummaryOnlyAnalysis" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：测试失败，因为当前 `instructions` 尚未包含五字段职责和禁止访问链接的约束。

- [ ] **步骤 3：替换结构化输入说明**

将 `instructions` 更新为一个单一常量，避免在方法内维护超长字符串：

```java
private static final String LLM_INSTRUCTIONS = """
        The newsItems list contains cleaned source metadata and summary-level evidence.
        Use title and summary to determine article sentiment and event impact; sentimentScore is the only per-article stance score.
        Use source, publishTime, and url only to assess report-level confidence and dataQuality, never to change sentiment direction.
        Do not access url and do not claim that article full text or facts were verified merely because a link exists.
        Treat missing url, missing source, short summary, missing or unparseable publishTime, and title and summary conflict as data-quality limitations.
        Items with missing or unparseable publishTime may remain in the input, so disclose that their recency is unknown.
        Exact duplicates were removed only. Group semantically equivalent news in deduplicatedEvents without repeated weighting.
        sourceNewsIds and evidenceIds must reference newsItems.id values; duplicateOriginalIds records removed exact duplicate source rows.
        Unless optional content and fullTextFetched explicitly prove otherwise, keep evidenceLevel at summary-level evidence and do not assume full text was read.
        """;
```

构建输入时使用：

```java
input.put("instructions", LLM_INSTRUCTIONS);
```

不要修改 `AnalystPromptTemplate.NEWS_ANALYST_STRUCTURED_PROMPT`、`V2028__trading_prompt_target_context.sql` 或 `V2029__trading_prompt_v2_templates.sql`。运行时所有 Prompt 模式都会通过 `stockData` 得到上述 `instructions`，历史迁移保持不可变。

- [ ] **步骤 4：运行结构化处理器和 Prompt 测试**

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=NewsAnalysisStructuredProcessorTest,TradingPromptV3IntegrationTest,TradingPromptRendererTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：所有测试通过，输出 `BUILD SUCCESS`。

- [ ] **步骤 5：提交摘要级约束**

```powershell
git add ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessor.java ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessorTest.java
git commit -m "feat: constrain summary-level news analysis"
```

### 任务 5：完成回归验证

| 任务 | status |
|------|------|
| 任务 5：完成回归验证 | pass |

**文件：**
- 验证：`ai-agent-study-trading/ai-agent-study-trading-domain`
- 验证：`ai-agent-study-trading/ai-agent-study-trading-infra`

- [ ] **步骤 1：运行新闻专项测试组合**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=NewsAnalysisPreprocessorTest,NewsKeywordSentimentScorerTest,NewsItemSentimentEnricherTest,NewsAnalysisStructuredProcessorTest,DecisionSignalShadowServiceTest,DeterministicSignalAlgorithmsTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：所有新闻评分、过滤、结构化输入和决策信号测试通过，输出 `BUILD SUCCESS`。

- [ ] **步骤 2：运行交易领域模块全量测试**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am test
```

预期：Reactor 内所有相关模块测试通过，输出 `BUILD SUCCESS`。

- [ ] **步骤 3：运行新闻 provider 测试**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-infra -am "-Dtest=SinaNewsApiClientTest,SinaNewsDataProviderTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：搜索结果的标题、摘要、来源、发布时间和链接映射测试通过；测试期间不访问文章详情页。

- [ ] **步骤 4：检查最终差异和工作区状态**

```powershell
git diff --check
git status --short
git log --oneline -6
```

预期：`git diff --check` 无输出；只有本需求预期文件发生变化；提交历史包含任务 1 至任务 4 的独立提交。

- [ ] **步骤 5：记录验证结果**

在计划任务状态表中，将已完成且验证通过的任务状态从 `append` 改为 `pass`，并在计划末尾追加实际测试数量、Maven 结果和任何非阻塞警告。随后提交计划状态更新：

```powershell
git add docs/superpowers/plans/2026-08-10-news-five-field-summary-analysis-implementation.md
git commit -m "docs: record news summary analysis verification"
```

## 实际验证结果

- 新闻专项测试：32 个通过，0 失败，0 错误，0 跳过。
- 交易领域及其依赖模块全量测试：149 个通过，0 失败，0 错误，0 跳过。
- 新闻 provider 映射测试：19 个通过，0 失败，0 错误，0 跳过。
- 干净编译：`mvn clean compile -q` 成功。
- 差异检查：`git diff --check` 无输出，分支工作区无未提交业务代码。
- 非阻塞警告：现有 Maven 插件参数、弃用 API、unchecked 操作和 Lombok `equals/hashCode` 提示仍会输出，本需求未新增对应警告。
