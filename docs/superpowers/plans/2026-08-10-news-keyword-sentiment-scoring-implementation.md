# 新闻关键词情感评分实现计划

> **致智能体工作者：** 必需子技能：使用 `executing-plans` 按任务逐步实现本计划。步骤使用复选框（`- [ ]`）跟踪。

**目标：** 基于当前新闻标题和摘要，为缺失情感分数的新闻生成固定、可复现的 `[-1,1]` 分数，使现有 `news-rating-v1` 能输出可用信号。

**架构：** 在 Provider 和 `NewsAnalystNode` 之间增加纯函数关键词评分器与新闻条目增强器。Provider 保持外部适配职责，增强器只补空分数，现有 `DecisionSignalShadowService` 和 `NewsRatingAlgorithm` 继续负责聚合，不改变评分阈值。

**技术栈：** Java 17、Spring Framework、JUnit 5、Maven、Lombok。

---

## 文件结构

### 新建文件

- `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/signal/NewsKeywordSentimentScorer.java`
  - 保存 `news-keyword-score-v1` 的固定规则、文本清洗、标题/摘要权重、组合规则覆盖、程度调整和分数限制逻辑。
- `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/signal/NewsItemSentimentEnricher.java`
  - 遍历新闻快照，保留原生分数，只为缺失值调用规则评分器，并输出汇总日志。
- `ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/signal/NewsKeywordSentimentScorerTest.java`
  - 覆盖规则、组合语义、权重、HTML 清洗、空值和聚合接续。
- `ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/signal/NewsItemSentimentEnricherTest.java`
  - 覆盖原生分数保护、规则补分、无匹配、空条目和汇总计数。

### 修改文件

- `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalystNode.java`
  - 注入增强器，并在新闻获取后、构建 LLM 输入前执行补分。
- `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessor.java`
  - 将非空 `sentimentScore` 放入当前 LLM 新闻快照，使正文与确定性评分读取同一份增强数据。
- `ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessorTest.java`
  - 验证非空分数进入结构化输入，空分数不输出。

## 任务 1：实现纯函数关键词评分器

| 任务 | status |
|------|------|
| 任务 1：实现纯函数关键词评分器 | pass |

**文件：**

- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/signal/NewsKeywordSentimentScorer.java`
- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/signal/NewsKeywordSentimentScorerTest.java`

- [ ] **步骤 1：编写评分器失败测试**

创建测试类，先覆盖最关键的业务语义：

```java
package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.NewsItemVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NewsKeywordSentimentScorerTest {

    private final NewsKeywordSentimentScorer scorer = new NewsKeywordSentimentScorer();

    @Test
    void scoresPositiveAndNegativeHighConfidenceEvents() {
        var approved = scorer.score(item("公司新药获批", "收到药监局批准通知"));
        var investigated = scorer.score(item("公司被立案调查", "涉嫌重大违法"));

        assertTrue(approved.available());
        assertTrue(approved.score() > 0.5);
        assertTrue(investigated.available());
        assertTrue(investigated.score() < -0.8);
    }

    @Test
    void compositeRulesOverrideContainedKeywords() {
        var narrowingLoss = scorer.score(item("公司亏损收窄", "经营情况改善"));
        var belowExpectation = scorer.score(item("利润增长不及预期", ""));

        assertEquals(0.35, narrowingLoss.score(), 0.000001);
        assertEquals(-0.60, belowExpectation.score(), 0.000001);
    }

    @Test
    void combinesMixedEventsAndKeepsZeroAvailable() {
        var result = scorer.score(item(
                "股东解除质押975万股，同时新增质押508万股", ""));

        assertTrue(result.available());
        assertEquals(-0.10, result.score(), 0.000001);
    }

    @Test
    void titleWinsOverDuplicateSummaryMatch() {
        var result = scorer.score(item("公司获批新产品", "公司新产品已经获批"));

        assertEquals(0.60, result.score(), 0.000001);
        assertEquals(1, result.matchedRules().size());
    }

    @Test
    void appliesSummaryWeightDegreeModifierAndHtmlCleanup() {
        var summaryOnly = scorer.score(item("公司发布公告", "净利润增长"));
        var amplified = scorer.score(item("净利润大幅增长", ""));
        var html = scorer.score(item("<em>公司</em>新药获批", ""));

        assertEquals(0.30, summaryOnly.score(), 0.000001);
        assertEquals(0.60, amplified.score(), 0.000001);
        assertEquals(0.60, html.score(), 0.000001);
    }

    @Test
    void returnsUnavailableWithoutDirectionalEvidence() {
        assertFalse(scorer.score(item("公司发布一季报", "营业收入11.26亿元")).available());
        assertFalse(scorer.score(item(null, null)).available());
    }

    @Test
    void scorerOutputFeedsExistingNewsRatingAlgorithm() {
        NewsItemVO news = item("四个药品获批", "");
        news.setSentimentScore(scorer.score(news).score());

        var signals = new NewsRatingAlgorithm().calculate(java.util.List.of(news));

        assertTrue(signals.rating().isAvailable());
        assertEquals(5, signals.rating().value());
        assertEquals("positive", signals.overallSentiment().value());
    }

    private NewsItemVO item(String title, String summary) {
        return NewsItemVO.builder().title(title).summary(summary).build();
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=NewsKeywordSentimentScorerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：`FAIL`，编译器报告 `NewsKeywordSentimentScorer` 不存在。

- [ ] **步骤 3：实现固定规则评分器**

创建以下公共契约：

```java
public final class NewsKeywordSentimentScorer {

    public static final String VERSION = "news-keyword-score-v1";

    public ScoreResult score(NewsItemVO item) {
        // 清洗标题和摘要；标题先匹配，摘要跳过标题已命中的规则。
    }

    public record ScoreResult(Double score, List<String> matchedRules) {
        public boolean available() {
            return score != null;
        }
    }
}
```

内部使用不可变的 `Rule` 列表：

```java
private record Rule(String id, double score, List<String> phrases) {
    String firstMatch(String text) {
        return phrases.stream().filter(text::contains).findFirst().orElse(null);
    }
}
```

实现要求：

1. `COMPOSITE_RULES` 必须先于 `DIRECTIONAL_RULES` 执行。
2. 组合规则命中后，从当前工作文本移除命中短语，防止内部普通词重复计分。
3. 标题命中的规则 ID 放入集合；摘要计算时跳过这些 ID。
4. 标题得分权重 `1.0`，摘要得分权重 `0.6`。
5. 每个文本片段先识别程度词并保存倍率，再从用于规则匹配的副本中移除程度词；这样 `净利润大幅增长` 能匹配 `净利润增长`，同时应用强程度倍率 `1.2`。每个片段最多应用一次倍率，弱程度倍率为 `0.7`。
6. 至少命中一条规则时返回数值，正负抵消后允许返回 `0.0`；无命中返回 `new ScoreResult(null, List.of())`。
7. 最终分数使用 `Math.max(-1.0, Math.min(1.0, score))` 限制范围，并将绝对值小于 `1e-12` 的结果归一成 `0.0`。
8. 文本清洗沿用 `NewsAnalysisStructuredProcessor` 的语义：移除 HTML 标签、还原 `&nbsp;`、`&amp;`、`&lt;`、`&gt;`、合并空白。
9. 使用设计规格中的全部固定规则和权重，不从 YAML 或数据库读取。

- [ ] **步骤 4：运行评分器测试确认通过**

运行同步骤 2。

预期：`BUILD SUCCESS`，`NewsKeywordSentimentScorerTest` 全部通过。

- [ ] **步骤 5：提交评分器**

```powershell
git add -- `
  ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/signal/NewsKeywordSentimentScorer.java `
  ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/signal/NewsKeywordSentimentScorerTest.java
git commit -m "feat: add deterministic news keyword scoring"
```

## 任务 2：实现新闻条目评分增强器

| 任务 | status |
|------|------|
| 任务 2：实现新闻条目评分增强器 | append |

**文件：**

- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/signal/NewsItemSentimentEnricher.java`
- 创建：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/signal/NewsItemSentimentEnricherTest.java`

- [ ] **步骤 1：编写增强器失败测试**

```java
package denny.ai.agent.trading.domain.signal;

import denny.ai.agent.trading.api.vo.NewsItemVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NewsItemSentimentEnricherTest {

    private final NewsItemSentimentEnricher enricher =
            new NewsItemSentimentEnricher(new NewsKeywordSentimentScorer());

    @Test
    void preservesNativeScoresAndFillsOnlyRuleMatches() {
        NewsItemVO nativeScore = NewsItemVO.builder()
                .title("公司被处罚").sentimentScore(0.25).build();
        NewsItemVO scored = NewsItemVO.builder().title("公司新药获批").build();
        NewsItemVO unknown = NewsItemVO.builder().title("公司发布公告").build();

        var summary = enricher.enrich(List.of(nativeScore, scored, unknown));

        assertEquals(0.25, nativeScore.getSentimentScore());
        assertEquals(0.60, scored.getSentimentScore(), 0.000001);
        assertNull(unknown.getSentimentScore());
        assertEquals(3, summary.totalCount());
        assertEquals(1, summary.nativeScoreCount());
        assertEquals(1, summary.ruleScoreCount());
        assertEquals(1, summary.unscoredCount());
    }

    @Test
    void toleratesNullListNullEntriesAndBlankItems() {
        assertEquals(0, enricher.enrich(null).totalCount());

        List<NewsItemVO> items = new ArrayList<>(Arrays.asList(null,
                NewsItemVO.builder().build()));
        var summary = enricher.enrich(items);

        assertEquals(2, summary.totalCount());
        assertEquals(2, summary.unscoredCount());
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=NewsItemSentimentEnricherTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：`FAIL`，编译器报告 `NewsItemSentimentEnricher` 不存在。

- [ ] **步骤 3：实现增强器和汇总日志**

实现以下契约：

```java
@Slf4j
@Component
public class NewsItemSentimentEnricher {

    private final NewsKeywordSentimentScorer scorer;

    public NewsItemSentimentEnricher(NewsKeywordSentimentScorer scorer) {
        this.scorer = scorer;
    }

    public EnrichmentSummary enrich(List<NewsItemVO> items) {
        // 保留原生分数，仅给 null 分数补规则结果；单条失败时继续处理。
    }

    public record EnrichmentSummary(int totalCount,
                                    int nativeScoreCount,
                                    int ruleScoreCount,
                                    int unscoredCount) {
    }
}
```

同时将 `NewsKeywordSentimentScorer` 标注为 `@Component`，供 Spring 注入。

实现要求：

1. `items == null` 时返回四个计数均为 `0`。
2. 空条目计入 `totalCount` 和 `unscoredCount`。
3. 非空原生分数只计入 `nativeScoreCount`，不得覆盖。
4. 规则评分可用时设置 `item.setSentimentScore(result.score())`。
5. 单条评分出现 `RuntimeException` 时记录标题和错误摘要，计入未评分，不中断列表处理。
6. 完成后用 `INFO` 记录算法版本和四类计数，不记录正文或摘要。
7. 单条命中详情仅用 `DEBUG` 记录标题、规则 ID 和分数。

- [ ] **步骤 4：运行增强器测试确认通过**

运行同步骤 2。

预期：`BUILD SUCCESS`。

- [ ] **步骤 5：提交增强器**

```powershell
git add -- `
  ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/signal/NewsKeywordSentimentScorer.java `
  ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/signal/NewsItemSentimentEnricher.java `
  ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/signal/NewsItemSentimentEnricherTest.java
git commit -m "feat: enrich missing news sentiment scores"
```

## 任务 3：接入新闻节点和 LLM 新闻快照

| 任务 | status |
|------|------|
| 任务 3：接入新闻节点和 LLM 新闻快照 | append |

**文件：**

- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalystNode.java:38-45,83-90`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessor.java:42-58,220-244,318-352`
- 修改：`ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessorTest.java`

- [ ] **步骤 1：为结构化输入编写失败测试**

在 `NewsAnalysisStructuredProcessorTest` 增加：

```java
@Test
void includesAvailableSentimentScoresAndOmitsMissingOnes() {
    NewsAnalysisStructuredProcessor processor = new NewsAnalysisStructuredProcessor();
    List<NewsItemVO> items = List.of(
            NewsItemVO.builder().title("approved").sentimentScore(0.6).build(),
            NewsItemVO.builder().title("unknown").build());

    JSONObject root = JSON.parseObject(processor.buildLlmInput("600285.SH", "羚锐制药", items));
    JSONArray newsItems = root.getJSONArray("newsItems");

    assertEquals(0.6, newsItems.getJSONObject(0).getDoubleValue("sentimentScore"), 0.000001);
    assertFalse(newsItems.getJSONObject(1).containsKey("sentimentScore"));
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=NewsAnalysisStructuredProcessorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：`FAIL`，第一条新闻的 JSON 中没有 `sentimentScore`。

- [ ] **步骤 3：让结构化快照携带非空分数**

修改 `PreparedNewsItem`，增加：

```java
private final Double sentimentScore;
```

构造 `PreparedNewsItem` 时传入 `news.getSentimentScore()`；构造 LLM JSON 时增加：

```java
putIfNotNull(item, "sentimentScore", prepared.sentimentScore);
```

不得为缺失分数输出 JSON `null`，保持当前紧凑输入风格。

- [ ] **步骤 4：运行结构化输入测试确认通过**

运行同步骤 2。

预期：`BUILD SUCCESS`。

- [ ] **步骤 5：将增强器接入 `NewsAnalystNode`**

增加依赖：

```java
@Resource
private NewsItemSentimentEnricher newsItemSentimentEnricher;
```

在获取新闻后立即执行：

```java
List<NewsItemVO> newsItems = TargetBoundStockDataProvider
        .bind(dataProvider, context.getTargetContext()).getNews(10);
newsItemSentimentEnricher.enrich(newsItems);
```

调用必须发生在日志计数、SSE 进度事件和 `generateReport` 之前，保证后续消费者共享增强后的同一列表。不得在 `SinaNewsDataProvider` 中加入评分逻辑。

- [ ] **步骤 6：运行新闻评分相关测试**

运行：

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am `
  "-Dtest=NewsKeywordSentimentScorerTest,NewsItemSentimentEnricherTest,NewsAnalysisStructuredProcessorTest,DeterministicSignalAlgorithmsTest,DecisionSignalShadowServiceTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：`BUILD SUCCESS`，现有 `news-rating-v1` 边界测试保持通过。

- [ ] **步骤 7：提交节点接线**

```powershell
git add -- `
  ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalystNode.java `
  ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessor.java `
  ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessorTest.java
git commit -m "feat: wire keyword scores into news analysis"
```

## 任务 4：完成回归验证与交付检查

| 任务 | status |
|------|------|
| 任务 4：完成回归验证与交付检查 | append |

**文件：**

- 验证：`ai-agent-study-trading/ai-agent-study-trading-domain`
- 对照：`docs/superpowers/plans/2026-08-10-news-keyword-sentiment-scoring-design.md`

- [ ] **步骤 1：运行 trading-domain 全量测试**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am test "-Dsurefire.failIfNoSpecifiedTests=false"
```

预期：`BUILD SUCCESS`，无失败和错误测试。

- [ ] **步骤 2：运行编译和格式检查**

```powershell
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am -DskipTests compile
git diff --check
```

预期：Maven `BUILD SUCCESS`，`git diff --check` 无输出。

- [ ] **步骤 3：核对实现范围**

逐项确认：

1. `SinaNewsDataProvider` 未加入业务评分逻辑。
2. 非空原生 `sentimentScore` 不被覆盖。
3. 无关键词命中仍保留 `null`。
4. `NewsRatingAlgorithm.VERSION` 仍为 `news-rating-v1`，阈值没有变化。
5. 新规则版本为 `news-keyword-score-v1`。
6. 没有新增 YAML、数据库迁移或额外 LLM 调用。
7. 日志只记录计数、标题和规则 ID，不记录完整正文。

- [ ] **步骤 4：检查工作区和提交历史**

```powershell
git status --short
git log -4 --oneline
```

预期：仅保留用户原有的无关未提交改动；本功能对应三个独立提交，提交内容不包含 `application.yml`、`application-dev.yml` 或其他无关文件。

## 交付结果

完成后，当前新浪新闻链路中包含明确事件词的条目会获得可解释的确定性分数。至少一条新闻可评分时，现有 `news-rating-v1` 将返回 `AVAILABLE`；全部新闻缺少可靠规则证据时，系统仍返回 `UNAVAILABLE`，不会制造中性评分。
