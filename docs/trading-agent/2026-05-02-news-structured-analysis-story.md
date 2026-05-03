# Story: NewsAnalystNode 新闻结构化分析增强

## 1. 背景

`NewsAnalystNode` 原先从第三方接口获取 `List<NewsItemVO>` 后，会把新闻标题和摘要拼成 Markdown 文本交给 LLM。
虽然 Prompt 要求模型输出 JSON，但节点侧没有真正解析模型返回结果：

- `rating` 仍由 `NewsItemVO.sentimentScore` 计算
- `overallSentiment` 仍由本地情绪分数推导
- LLM 返回内容整体写入 `summary`
- 新闻中 `<em>` 等 HTML 标记会直接进入 Prompt
- 多篇媒体重复报道同一事件时，节点没有显式要求模型做去重归因

在类似 `002511 中顺洁柔` 的新闻样本中，多篇报道都围绕“一季度净利润同比增长”展开。
如果直接把 10 条新闻平铺给 LLM，模型容易把重复报道误判为多个独立利好事件。

## 2. 目标

本次改动聚焦 `NewsAnalystNode` 自身使用，不做通用新闻中台，也不默认抓取新闻详情页正文。

目标是将流程调整为：

```text
第三方 NewsItemVO 列表
    -> 基于 title / summary 清洗并编号为稳定 JSON 输入
    -> LLM 输出摘要级结构化新闻分析 JSON
    -> 解析为 NewsReportVO
    -> 解析失败时使用本地 fallback
```

## 3. 设计

### 3.1 输入结构化

新增 `NewsAnalysisStructuredProcessor`，负责把新闻标题和摘要列表转换为 LLM 更容易消费的 JSON：

```json
{
  "ticker": "002511",
  "newsItems": [
    {
      "id": 1,
      "publishTime": "2026-05-01 16:48:11",
      "source": "中国证券报·中证网",
      "title": "中顺洁柔：2026年一季度净利润1.03亿元 同比增长53.76%",
      "summary": "中顺洁柔披露一季度报告。"
    }
  ],
  "instructions": "Use evidenceIds to reference the newsItems.id values..."
}
```

输入边界：

- 当前只传入第三方接口返回的 `title` 和 `summary`，不包含完整新闻正文。
- LLM 只能基于摘要级信息判断事件、情绪、风险和数据质量。
- Prompt 与 `dataQuality` 需要明确这是摘要级分析，避免模型假设已经读取全文。

处理规则：

- 去除 `<em>` 等 HTML 标签
- 规整空白字符
- 给每条新闻分配从 1 开始的 `id`
- 要求模型用 `evidenceIds` 引用新闻证据

### 3.2 输出结构化

`NewsReportVO` 保留已有字段，并新增结构化字段：

| 字段 | 说明 |
|------|------|
| `confidence` | LLM 对新闻结论的置信度，范围 0.0-1.0 |
| `newsThemes` | 结构化新闻主题列表 |
| `riskWarnings` | 结构化风险提示列表 |
| `dataQuality` | 新闻质量说明，例如重复报道、摘要过短、来源单一 |

`newsThemes` 中包含：

- `theme`
- `sentiment`
- `impactLevel`
- `evidenceIds`
- `reason`

`riskWarnings` 中包含：

- `risk`
- `impactLevel`
- `evidenceIds`
- `reason`

### 3.3 Prompt 调整

新增 `AnalystPromptTemplate.NEWS_ANALYST_STRUCTURED_PROMPT`。

新 Prompt 要求：

- 输入为新闻 JSON
- 明确新闻 JSON 只包含标题和摘要，不包含完整正文
- 识别重复报道并归并为同一主题
- 区分业绩、分红、机构评级、机构持仓、管理层变化、监管风险等信息
- `overallSentiment` 只能输出 `positive`、`negative`、`neutral`、`mixed`
- 仅输出完整 JSON 对象
- 不给出具体买卖价格、仓位或精确交易时点

### 3.4 解析与 fallback

`NewsAnalysisStructuredProcessor.parseReport()` 负责解析 LLM 输出：

1. 从响应中提取 JSON 对象
2. 反序列化为 `NewsReportVO`
3. 校验 `rating` 必须在 1-5
4. 归一化 `overallSentiment`
5. 限制 `confidence` 在 0.0-1.0
6. 补齐空集合和默认 `dataQuality`
7. 解析失败时走本地 fallback

fallback 逻辑保持轻量：

- 根据 `NewsItemVO.sentimentScore` 计算 `rating`
- 根据平均情绪分推导 `overallSentiment`
- `summary` 保留原始 LLM 响应
- `dataQuality` 标记为 `LLM response parse failed; used sentiment-score fallback.`
- `confidence` 设置为 `0.3`

## 4. 涉及文件

### API 模块

- `ai-agent-study-trading/ai-agent-study-trading-api/src/main/java/denny/ai/agent/trading/api/vo/NewsReportVO.java`

新增 `confidence`、`newsThemes`、`riskWarnings`、`dataQuality` 以及内部结构化 VO。

### Domain 模块

- `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalystNode.java`

将原来的 Markdown 新闻拼接替换为结构化 JSON 输入，并解析结构化 LLM 输出。

- `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessor.java`

新增新闻输入清洗、编号、LLM 响应解析和 fallback 处理器。

- `ai-agent-study-trading/ai-agent-study-trading-domain/src/main/java/denny/ai/agent/trading/domain/prompt/AnalystPromptTemplate.java`

新增 `NEWS_ANALYST_STRUCTURED_PROMPT`。

### 测试

- `ai-agent-study-trading/ai-agent-study-trading-domain/src/test/java/denny/ai/agent/trading/domain/node/NewsAnalysisStructuredProcessorTest.java`

覆盖：

- 构造清洗后的编号 JSON 输入
- 解析结构化 LLM JSON 到 `NewsReportVO`
- 非 JSON 响应时走 fallback

## 5. 验证

已执行：

```bash
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=NewsAnalysisStructuredProcessorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 6. 注意事项

本次只处理 `NewsAnalystNode` 的新闻结构化分析。
后续如果 `RecommendationNode` 需要更充分利用新闻结果，可以进一步读取 `newsThemes`、`riskWarnings` 和 `confidence`，而不是只依赖 `rating` 与 `summary`。

当前结论属于摘要级新闻分析。第三方摘要通常足够识别多数交易相关信号，但不能替代完整正文、上市公司公告或监管披露文件。

## 7. 二次增强：新闻去重与内容清洗

### 7.1 背景

第三方新闻接口返回的数据可能存在两类问题：

- 多家媒体重复报道同一事件，例如同一份一季报被不同来源反复转述
- 标题或摘要来自搜索结果片段，可能夹带其它股票、板块、行情数字等不相关内容

如果这些内容直接进入 LLM，模型可能会：

- 把重复报道误认为多个独立利好或利空
- 被其它股票代码、行业片段、排行榜文本干扰
- 在 `evidenceIds` 中引用噪声信息，降低结构化结论质量

### 7.2 处理策略

在 `NewsAnalysisStructuredProcessor` 中增强输入预处理：

1. 保留旧方法 `buildLlmInput(String ticker, List<NewsItemVO> newsItems)`，避免破坏已有调用。
2. 新增 `buildLlmInput(String ticker, String stockName, List<NewsItemVO> newsItems)`，允许基于股票代码和股票名称做相关性清洗。
3. `NewsAnalystNode` 改为传入 `stockInfo.getTicker()` 与 `stockInfo.getName()`。
4. 对标题和摘要按分号、竖线、句号、感叹号、换行等片段边界拆分。
5. 优先保留包含目标股票代码或股票名称的片段；如果无法识别相关片段，则保留原清洗文本，避免误删。
6. 对相似新闻做近重复合并：
   - 完全归一化文本相同，视为重复
   - 同时包含一季度/季度、净利润/利润等财报事件关键词，并共享关键数字，视为重复
   - token Jaccard 相似度达到阈值，视为重复
7. LLM 输入只保留去重后的新闻，并在保留项上追加 `duplicateOriginalIds`，记录被合并掉的原始行号。

### 7.3 示例

输入中如果存在：

```text
Market list: 000001 OtherBank up 5%; CleanSoft 002511 got buy rating; 000002 OtherTech down
```

清洗后进入 LLM 的标题为：

```text
CleanSoft 002511 got buy rating
```

如果两条新闻都描述：

```text
CleanSoft 002511 Q1 net profit reached 103 million, up 53.76%
CleanSoft(002511): Q1 net profit was 103 million yuan
```

LLM 输入中只保留第一条，并记录：

```json
"duplicateOriginalIds": [2]
```

### 7.4 新增测试

在 `NewsAnalysisStructuredProcessorTest` 中新增两类测试：

- `removesDuplicateNewsBeforeSendingToLlm`
- `removesUnrelatedSnippetNoiseFromTitleAndSummary`

### 7.5 验证

二次增强后重新执行：

```bash
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=NewsAnalysisStructuredProcessorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 8. 三次调整：语义去重交给 LLM

### 8.1 调整原因

规则匹配适合做低风险清洗，但不适合承担新闻语义去重。

例如下面几条新闻：

```text
CleanSoft 002511 Q1 net profit reached 103 million, up 53.76%
CleanSoft(002511): Q1 net profit was 103 million yuan
CleanSoft released its Q1 report. Net profit reached 103 million and increased 53.8%.
```

它们的标题、摘要、数字表达和来源都不同，但语义上属于同一个“季度业绩改善”事件。
如果继续依赖规则或关键词判断，容易出现两类问题：

- 误删：不同事件共享相同数字或关键词时被误判为重复
- 漏删：同一事件被改写表达时无法识别

因此最终设计调整为：**代码只做安全清洗和完全重复去重，语义事件归并由 LLM 完成。**

### 8.2 最新处理边界

代码侧负责：

1. 清理 HTML 标签、HTML entity、异常空白和控制字符。
2. 基于 `ticker` / `stockName` 清理搜索片段中的不相关股票内容。
3. 仅删除完全重复文本。
4. 保留语义相似但表达不同的新闻，让 LLM 进行事件级归并。
5. 完全重复被删除时，在保留项记录 `duplicateOriginalIds`。

LLM 侧负责：

1. 输出 `deduplicatedEvents`。
2. 每个事件用 `sourceNewsIds` 引用归并来源。
3. 对同一财报、分红、评级、机构持仓等事件只计为一个事件，不重复加权。
4. 基于归并后的事件生成 `newsThemes`、`riskWarnings`、`rating` 和 `summary`。

### 8.3 数据结构补充

`NewsReportVO` 新增：

```java
private List<NewsEventVO> deduplicatedEvents;
```

`NewsEventVO` 字段：

| 字段 | 说明 |
|------|------|
| `eventType` | 事件类型，如 earnings、dividend、rating、holding、management、regulatory |
| `eventTitle` | 归并后的事件标题 |
| `sentiment` | positive、negative、neutral、mixed |
| `impactLevel` | high、medium、low |
| `sourceNewsIds` | 该事件对应的 `newsItems.id` 列表 |
| `summary` | 事件摘要 |

### 8.4 Prompt 调整

`NEWS_ANALYST_STRUCTURED_PROMPT` 已更新：

- 明确说明系统只移除完全重复文本，不做语义去重
- 要求模型先输出 `deduplicatedEvents`
- 要求 `sourceNewsIds` 和 `evidenceIds` 都引用 `newsItems.id`
- 要求同一事件不要重复加权

### 8.5 测试调整

测试更新为 6 个用例：

- `buildsCleanNumberedJsonInputForLlm`
- `parsesStructuredLlmReportAndKeepsOriginalNewsItems`
- `fallsBackToNewsSentimentWhenLlmResponseIsNotJson`
- `keepsSemanticDuplicatesForLlmEventDeduplication`
- `removesExactDuplicateNewsBeforeSendingToLlm`
- `removesUnrelatedSnippetNoiseFromTitleAndSummary`

其中 `keepsSemanticDuplicatesForLlmEventDeduplication` 专门保证：语义相似但表达不同的新闻不会在代码侧被删除。

### 8.6 验证

调整后重新执行：

```bash
mvn -pl ai-agent-study-trading/ai-agent-study-trading-domain -am "-Dtest=NewsAnalysisStructuredProcessorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 9. 后续设计：摘要级主路径与按需正文增强

### 9.1 分层原则

新闻分析按成本和置信度分层处理：

```text
L1 摘要级新闻分析
    -> 使用第三方 title / summary
    -> 快速完成清洗、精确去重、语义归并和摘要级情绪判断

L2 重点新闻正文增强
    -> 仅对高影响、低置信度、摘要不足或风险类新闻抓取正文
    -> 从正文中提取事件事实，并回填到同一套结构化报告

L3 权威来源校验
    -> 对重大交易决策相关事件，优先校验公告、交易所、监管或公司披露来源
    -> 用于提高同一套结构化报告的证据等级和最终置信度
```

默认路径保持 L1。这样可以控制请求成本、响应延迟、正文抓取失败率、反爬风险和 LLM token 消耗。

无论是否读取完整正文，最终输出都保持同一种 `NewsReportVO` 格式。L2 / L3 不新增另一套“正文报告”或“公告报告”，只增强同一套结构化结果中的事件事实、证据等级、置信度和数据质量说明。

### 9.2 L1：摘要级新闻分析

L1 是当前 `NewsAnalysisStructuredProcessor` 的职责边界：

- 输入字段为 `publishTime`、`source`、`title`、`summary`。
- 清洗 HTML、entity、异常空白和搜索结果噪声。
- 只删除完全重复新闻，保留语义相似新闻给 LLM 做事件归并。
- 输出 `deduplicatedEvents`、`newsThemes`、`riskWarnings`、`rating`、`overallSentiment`、`confidence` 和 `dataQuality`。
- `dataQuality` 应说明结论基于标题和摘要，未读取完整正文。

L1 适合处理财报摘要、分红、评级、机构持仓、行业政策、普通舆情等多数场景。

### 9.3 L2：重点新闻正文增强

L2 不默认抓取所有新闻正文，只在 L1 发现下列情况时触发：

- 摘要为空、过短，或只包含泛化描述。
- LLM 给出高影响事件，但 `confidence` 偏低。
- 标题或摘要包含重大风险关键词，例如立案、处罚、诉讼、退市、重组、减持、业绩预告、回购、重大合同。
- 多条新闻描述同一事件但摘要信息互相矛盾。
- 最终交易建议高度依赖某一条新闻。

L2 的输出仍然服务于同一个 `NewsReportVO`。正文增强阶段不直接生成另一套最终报告，而是先做事实提取，再用这些事实更新或重算 `deduplicatedEvents`、`newsThemes`、`riskWarnings`、`confidence` 和 `dataQuality`。

正文事实提取可以使用中间结构：

```json
{
  "sourceNewsId": 1,
  "fullTextFetched": true,
  "contentQuality": "usable",
  "eventType": "regulatory",
  "facts": ["公司收到监管问询函"],
  "amounts": [],
  "dates": ["2026-05-02"],
  "uncertainties": ["正文未披露具体处罚金额"]
}
```

最终新闻报告消费这些结构化事实，而不是消费未清洗的长正文。报告格式保持一致，例如摘要阶段和正文增强阶段都输出：

```json
{
  "rating": 4,
  "overallSentiment": "positive",
  "confidence": 0.72,
  "deduplicatedEvents": [],
  "newsThemes": [],
  "riskWarnings": [],
  "dataQuality": "Structured from title and summary; selected articles were enhanced with full text."
}
```

为了让下游知道结论来自摘要还是正文，建议在事件或证据对象中补充证据元信息：

| 字段 | 说明 |
|------|------|
| `evidenceLevel` | `summary`、`full_text`、`authoritative` |
| `evidenceQuality` | `insufficient`、`usable`、`confirmed`、`conflicting` |
| `enhancedSourceNewsIds` | 已读取正文并参与增强的 `newsItems.id` 列表 |

其中 `evidenceLevel` 表示证据来源深度，`evidenceQuality` 表示当前证据是否足以支撑结论。下游节点仍读取统一的 `NewsReportVO`，只根据这些元信息调整使用权重。

### 9.4 L3：权威来源校验

L3 面向真正影响交易决策的重大事件，例如监管立案、财务造假、重大诉讼、资产重组、退市风险、业绩大幅修正。

优先级建议：

1. 上市公司公告、交易所披露、监管机构文件。
2. 主流财经媒体或证券报。
3. 第三方新闻聚合摘要。

如果 L3 校验成功，可以把相关事件的 `evidenceLevel` 提升为 `authoritative`，提高 `confidence`，并在 `dataQuality` 中标记权威来源已确认。若校验失败，不直接否定 L1 结论，但需要降低置信度、标记 `evidenceQuality=conflicting`，或写入风险提示。

### 9.5 数据模型预留

为了支持 L2 / L3，后续可以在 `NewsItemVO` 或扩展 VO 中预留字段：

```java
private String url;
private String content;
private Boolean fullTextFetched;
private String contentQuality;
private String sourceReliability;
private String evidenceLevel;
private String evidenceQuality;
```

当前不要求立即实现正文抓取。先把 Prompt、`instructions` 和 `dataQuality` 的口径改为摘要级分析，同时在设计上明确：后续即使引入 L2 正文增强和 L3 权威校验，也继续产出同一套结构化 `NewsReportVO`，不让下游节点适配多种报告格式。
