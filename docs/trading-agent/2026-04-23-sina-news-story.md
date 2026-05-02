# Story 任务跟踪文档

> **Story 名称**：数据源扩展 - 新浪财经新闻搜索接入（免费，无需 Token）
> **创建日期**：2026-04-23
> **背景**：`IStockDataProvider.getNews()` 依赖 Tushare `news` 接口，需要专业版（2000积分）且结果为股票公告而非财经新闻。现改接新浪财经 `feed.mix.sina.com.cn` 接口，支持任意关键字搜索，完全免费无需 Token。
> **Agent 执行说明**：本文档为 Story 设计文档，各 Task 供 Agent 对照执行。每个 Task 包含改动前（现有代码）和改动后（新代码）两部分，Agent 应逐 Task 完成并更新状态列。

---

## 任务清单

| # | 任务 | 状态 | 备注 |
|---|---|---|---|
| 1 | 新增 `SinaNewsApiClient.java` - 新浪财经 HTTP 客户端 | pass | |
| 2 | 新增 `INewsSearchProvider.java` - 新闻搜索接口 | pass | |
| 3 | 新增 `SinaNewsDataProvider.java` - 新浪新闻 Provider 实现 | pass | |
| 4 | 修改 `TradingDataSourceProperties.java` - 增加新浪配置项 | pass | |
| 5 | 修改 `ProviderFactory.java` - 注册新浪新闻 Provider + 修改 createTushareProvider 三参构造 | pass | 新增 sinaNewsSearchProvider() Bean；同步修改 createTushareProvider() 注入 INewsSearchProvider |
| 6 | 修改 `TushareStockDataProvider.java` - `getNews()` 替换为新浪调用 | pass | 移除 @RequiredArgsConstructor，增加 INewsSearchProvider 字段和构造函数，getNews 替换实现；createTushareProvider 三参构造在 Task 5 完成 |
| 7 | 新增 `SinaNewsDataProviderTest.java` - 单元测试（Mock 网络请求） | pass | 5 个测试用例全部通过 |
| 8 | 修改 `application.yml` - 增加新浪配置项（可选，默认启用） | pass | |
| 9 | 编译验证 `mvn compile` + `mvn test` | pass | BUILD SUCCESS，30 个测试全部通过 |

---

## 新浪财经接口规格

> **接口**：`GET https://feed.mix.sina.com.cn/api/roll/get`
> **鉴权**：无需 Token，无需登录
> **频率限制**：非官方接口，参考同类新浪接口约 1000次/小时/IP，建议每分钟不超过 5 次
> **请求头**：必须带 `Referer: https://finance.sina.com.cn/` 和标准 `User-Agent`
> **返回格式**：JSON，各字段含义如下：

| 字段 | 说明 |
|---|---|
| `title` | 新闻标题 |
| `ctime` | 发布时间（Unix 时间戳，秒） |
| `url` | 新闻链接 |
| `intro` | 新闻摘要 |
| `media_name` | 新闻来源（如"市场资讯"、"财经频道"） |

**请求示例**：

```
GET https://feed.mix.sina.com.cn/api/roll/get?pageid=153&lid=2509&k=工商银行&num=20&page=1
```

**响应示例**：

```json
{
  "result": {
    "status": { "code": 0, "msg": "succ" },
    "total": 100480,
    "data": [
      {
        "title": "工商银行股价创历史新高",
        "ctime": "1776926109",
        "url": "https://finance.sina.com.cn/...",
        "intro": "受业绩提振，工商银行今日大涨...",
        "media_name": "市场资讯"
      }
    ]
  }
}
```

---

## 详细任务说明

### Task 1 - SinaNewsApiClient.java（新增）

**文件路径**：`ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/SinaNewsApiClient.java`

**职责**：封装对 `feed.mix.sina.com.cn` 的 HTTP GET 调用。

**核心功能**：
- GET 请求，UTF-8 URL 编码关键字
- 设置 `Referer` 和 `User-Agent` 请求头（防盗链）
- 连接超时 5s，读取超时 15s
- 解析 JSON 响应，返回 `List<Map<String, String>>`
- 调用失败时记录日志并返回空列表

**主要方法**：

```java
public List<Map<String, String>> search(String keyword, int page, int pageSize)
```

**参数说明**：
- `keyword`：搜索关键字（如股票名称、行业主题）
- `page`：页码，从 1 开始
- `pageSize`：每页条数，建议 20-50

**返回说明**：
- 返回 `List<Map<String, String>>`，每项包含：`title`、`ctime`、`url`、`intro`、`media_name`
- 调用失败或解析失败返回空列表

**代码模板**：

```java
package denny.ai.agent.trading.infra.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.util.*;

/**
 * 新浪财经新闻搜索 HTTP 客户端。
 * <p>
 * 调用 feed.mix.sina.com.cn 获取财经新闻，支持关键字搜索。
 * 完全免费，无需 Token。
 */
@Slf4j
public class SinaNewsApiClient {

    private static final String BASE_URL = "https://feed.mix.sina.com.cn/api/roll/get";
    private static final String REFERER = "https://finance.sina.com.cn/";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public SinaNewsApiClient() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public List<Map<String, String>> search(String keyword, int page, int pageSize) {
        try {
            String encodedKeyword = URLEncoder.encode(keyword, "UTF-8");
            String url = BASE_URL + "?pageid=153&lid=2509&k=" + encodedKeyword
                    + "&num=" + pageSize + "&page=" + page;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Referer", REFERER);
            headers.set("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            String response = restTemplate.getForObject(url, String.class);

            return parseResponse(response);
        } catch (Exception e) {
            log.error("新浪财经新闻搜索失败: keyword={}, error={}", keyword, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Map<String, String>> parseResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode dataNode = root.path("result").path("data");
            if (!dataNode.isArray()) {
                return Collections.emptyList();
            }

            List<Map<String, String>> result = new ArrayList<>();
            for (JsonNode item : dataNode) {
                Map<String, String> news = new LinkedHashMap<>();
                news.put("title", item.path("title").asText(""));
                news.put("ctime", item.path("ctime").asText(""));
                news.put("url", item.path("url").asText(""));
                news.put("intro", item.path("intro").asText(""));
                news.put("media_name", item.path("media_name").asText(""));
                result.add(news);
            }
            return result;
        } catch (Exception e) {
            log.error("解析新浪新闻响应失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
```

> **注意**：本类使用 `new RestTemplate()` 初始化，不依赖 Spring Bean 注入，保持无状态。可后续优化为注入 Spring Bean 复用餐数池。

---

### Task 2 - INewsSearchProvider.java（新增）

**文件路径**：`ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/INewsSearchProvider.java`

**职责**：定义新闻搜索的标准接口，独立于 `IStockDataProvider`。

**代码模板**：

```java
package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.trading.api.vo.NewsItemVO;

import java.util.List;

/**
 * 新闻搜索 Provider 接口。
 * <p>
 * 定义关键字搜索新闻的标准方法。
 * Phase 1-5 使用 {@link denny.ai.agent.trading.infra.provider.MockStockDataProvider} 中的空实现，
 * Phase 6 替换为 {@link SinaNewsDataProvider} 接入新浪财经。
 */
public interface INewsSearchProvider {

    /**
     * 按关键字搜索新闻。
     *
     * @param keyword 搜索关键字（如股票名称、行业主题、政策关键词）
     * @param limit   返回条数上限
     * @return 新闻列表，按发布时间倒序排列
     */
    List<NewsItemVO> searchNews(String keyword, int limit);
}
```

---

### Task 3 - SinaNewsDataProvider.java（新增）

**文件路径**：`ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/SinaNewsDataProvider.java`

**职责**：实现 `INewsSearchProvider`，调用新浪财经接口获取新闻，将结果映射为 `NewsItemVO`。

**字段映射**：

| `NewsItemVO` 字段 | 新浪接口来源 | 说明 |
|---|---|---|
| `title` | `title` | ✅ 直接映射 |
| `source` | `media_name` | ✅ 直接映射 |
| `publishTime` | `ctime`（Unix 时间戳→yyyy-MM-dd HH:mm） | ✅ 需转换 |
| `summary` | `intro` | ✅ 直接映射 |
| `url` | `url` | ✅ 直接映射 |
| `sentimentScore` | - | 返回 null（暂不做情感分析） |
| `relatedTickers` | - | 返回 null（暂不关联股票代码） |

**翻页策略**：
- 每次调用新浪接口最多获取 20 条（新浪默认 pageSize）
- 如果 `limit > 20`，自动翻页直到满足条数或无更多数据
- 如果单页返回不足 20 条，说明已到最后一页，停止翻页

**异常处理**：
- 关键字为空：记录 warn 日志，返回空列表
- API 调用失败：记录 error 日志，返回空列表（不抛异常，避免阻断业务流程）

**代码模板**：

```java
package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.trading.api.vo.NewsItemVO;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 新浪财经新闻搜索 Provider 实现。
 * <p>
 * 调用新浪 feed.mix.sina.com.cn 接口实现关键字搜索。
 * 完全免费，支持任意中文关键字，无需 Token。
 */
@Slf4j
public class SinaNewsDataProvider implements INewsSearchProvider {

    private static final DateTimeFormatter OUTPUT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final SinaNewsApiClient apiClient;

    public SinaNewsDataProvider() {
        this.apiClient = new SinaNewsApiClient();
    }

    public SinaNewsDataProvider(SinaNewsApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public List<NewsItemVO> searchNews(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            log.warn("搜索关键字为空，直接返回空列表");
            return Collections.emptyList();
        }

        try {
            int page = 1;
            int fetched = 0;
            List<NewsItemVO> allNews = new ArrayList<>();

            while (fetched < limit) {
                int pageSize = Math.min(DEFAULT_PAGE_SIZE, limit - fetched);
                List<Map<String, String>> rawList = apiClient.search(keyword, page, pageSize);

                if (rawList.isEmpty()) {
                    break;
                }

                for (Map<String, String> raw : rawList) {
                    allNews.add(convertToNewsItem(raw));
                    fetched++;
                    if (fetched >= limit) {
                        break;
                    }
                }

                if (rawList.size() < DEFAULT_PAGE_SIZE) {
                    break;
                }
                page++;
            }

            log.info("新浪新闻搜索完成: keyword={}, limit={}, actual={}", keyword, limit, allNews.size());
            return allNews;
        } catch (Exception e) {
            log.error("新浪财经新闻搜索异常: keyword={}, error={}", keyword, e.getMessage());
            return Collections.emptyList();
        }
    }

    private NewsItemVO convertToNewsItem(Map<String, String> raw) {
        return NewsItemVO.builder()
                .title(raw.get("title"))
                .source(raw.get("media_name"))
                .publishTime(formatPublishTime(raw.get("ctime")))
                .summary(raw.get("intro"))
                .url(raw.get("url"))
                .sentimentScore(null)
                .relatedTickers(null)
                .build();
    }

    private String formatPublishTime(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            long epochSecond = Long.parseLong(timestamp);
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(epochSecond), ZoneId.of("Asia/Shanghai"));
            return dateTime.format(OUTPUT_DATE_FORMAT);
        } catch (Exception e) {
            return timestamp;
        }
    }
}
```

---

### Task 4 - TradingDataSourceProperties.java

**文件路径**：`ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/config/TradingDataSourceProperties.java`

**改动内容**：在 `CacheConfig` 之前增加新浪财经配置字段。

**改动前**（第 26-28 行）：

```java
    /**
     * 缓存配置子对象
     */
    private CacheConfig cache = new CacheConfig();
```

**改动后**（在 `cache` 字段之前插入）：

```java
    // ======== 新增：新浪财经新闻配置 ========
    /**
     * 是否启用新浪财经新闻搜索，默认 true。
     * 禁用后 getNews() 返回空列表。
     */
    private boolean sinaNewsEnabled = true;

    /**
     * 新闻搜索单次请求最大条数，默认 50。
     * 新浪接口每次最多返回 20 条，实际返回条数受此值和翻页策略共同限制。
     */
    private int sinaNewsPageSize = 50;

    /**
     * 缓存配置子对象
     */
    private CacheConfig cache = new CacheConfig();
```

---

### Task 5 - ProviderFactory.java

**文件路径**：`ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/ProviderFactory.java`

**改动内容**：
1. 新增 `sinaNewsSearchProvider()` Bean 方法，注册 `SinaNewsDataProvider` 实例
2. 修改 `createTushareProvider()` 方法，注入 `INewsSearchProvider` 实现三参构造

**当前实际代码**（第 35-59 行，`stockDataProvider()` 直接内嵌逻辑，无 `getProvider()` 方法）：

```java
    @ConditionalOnMissingBean(IStockDataProvider.class)
    @Bean
    public IStockDataProvider stockDataProvider() {
        return getProvider();
    }
        String provider = properties.getProvider();
        if ("tushare".equalsIgnoreCase(provider)) {
            return createTushareProvider();
        }
        return createMockProvider();
    }

    private IStockDataProvider createMockProvider() {
        return new MockStockDataProvider();
    }

    private IStockDataProvider createTushareProvider() {
        String token = properties.getTushareToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Tushare Token 未配置，请设置 spring.ai.trading.data-source.tushare-token");
        }
        TushareApiClient apiClient = new TushareApiClient(token);
        return new TushareStockDataProvider(apiClient, indicatorCalculator);  // ⚠️ 当前两参构造
    }
```

> ⚠️ **注意**：`ProviderFactory` 第 35-38 行存在语法问题，`stockDataProvider()` 方法中有未闭合的 `{`，导致第 39 行 `String provider = ...` 无法编译通过。需将第 35 行的 `@ConditionalOnMissingBean` 和 `@Bean` 注解移至第 39 行的 `getProvider()` 方法上，或改为直接返回。请先检查当前文件的实际结构，确保修复语法问题后再进行本 Task 的修改。

**正确写法**（修复语法问题后的 `stockDataProvider()` 方法）：

```java
    @ConditionalOnMissingBean(IStockDataProvider.class)
    @Bean
    public IStockDataProvider stockDataProvider() {
        return getProvider();
    }

    private IStockDataProvider getProvider() {
        String provider = properties.getProvider();
        if ("tushare".equalsIgnoreCase(provider)) {
            return createTushareProvider();
        }
        return createMockProvider();
    }
```

**第一步：新增新浪新闻 Provider Bean（在 `createMockProvider()` 之前插入）**：

```java
    // ======== 新增：新浪新闻 Provider ========
    @ConditionalOnMissingBean(INewsSearchProvider.class)
    @Bean
    public INewsSearchProvider sinaNewsSearchProvider() {
        return new SinaNewsDataProvider();
    }

    private IStockDataProvider createMockProvider() {
        return new MockStockDataProvider();
    }
```

**第二步：修改 `createTushareProvider()` 方法，注入 `INewsSearchProvider`（三参构造）**：

改动前（第 51-58 行）：

```java
    private IStockDataProvider createTushareProvider() {
        String token = properties.getTushareToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Tushare Token 未配置，请设置 spring.ai.trading.data-source.tushare-token");
        }
        TushareApiClient apiClient = new TushareApiClient(token);
        return new TushareStockDataProvider(apiClient, indicatorCalculator);  // 两参构造
    }
```

改动后：

```java
    private IStockDataProvider createTushareProvider() {
        String token = properties.getTushareToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Tushare Token 未配置，请设置 spring.ai.trading.data-source.tushare-token");
        }
        TushareApiClient apiClient = new TushareApiClient(token);
        INewsSearchProvider newsSearchProvider = new SinaNewsDataProvider();
        return new TushareStockDataProvider(apiClient, indicatorCalculator, newsSearchProvider);
    }
```

> ⚠️ **重要**：`createTushareProvider()` 中直接 `new SinaNewsDataProvider()` 而非注入 Bean，是因为 `TushareStockDataProvider` 比 `INewsSearchProvider` 更早初始化（取决于字段注入顺序），直接注入可避免循环依赖问题。

---

### Task 6 - TushareStockDataProvider.java

**文件路径**：`ai-agent-study-trading-infra/src/main/java/denny/ai/agent/trading/infra/provider/TushareStockDataProvider.java`

**改动内容**：
1. 新增 `INewsSearchProvider` 成员变量
2. 构造函数增加 `INewsSearchProvider` 参数
3. `getNews()` 方法替换为空实现 → 真实调用新浪新闻

**改动前**（第 29-36 行，成员变量和构造函数）：

```java
@Slf4j
@RequiredArgsConstructor
public class TushareStockDataProvider implements IStockDataProvider {

    private static final DateTimeFormatter TUSHARE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter OUTPUT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TushareApiClient apiClient;
    private final TechnicalIndicatorCalculator indicatorCalculator;
```

**改动后**：

```java
@Slf4j
public class TushareStockDataProvider implements IStockDataProvider {

    private static final DateTimeFormatter TUSHARE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter OUTPUT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TushareApiClient apiClient;
    private final TechnicalIndicatorCalculator indicatorCalculator;
    private final INewsSearchProvider newsSearchProvider;

    public TushareStockDataProvider(TushareApiClient apiClient,
                                    TechnicalIndicatorCalculator indicatorCalculator,
                                    INewsSearchProvider newsSearchProvider) {
        this.apiClient = apiClient;
        this.indicatorCalculator = indicatorCalculator;
        this.newsSearchProvider = newsSearchProvider;
    }
```

**改动前**（第 276-281 行，`getNews` 方法）：

```java
    @Override
    public List<NewsItemVO> getNews(String ticker, int limit) {
        // getNews 暂不实现，由独立需求接入第三方资讯接口
        log.info("getNews 暂不实现: ticker={}", ticker);
        return Collections.emptyList();
    }
```

**改动后**：

```java
    @Override
    public List<NewsItemVO> getNews(String ticker, int limit) {
        if (newsSearchProvider == null) {
            log.warn("新闻搜索 Provider 未配置，getNews 返回空列表: ticker={}", ticker);
            return Collections.emptyList();
        }
        try {
            return newsSearchProvider.searchNews(ticker, limit);
        } catch (Exception e) {
            log.error("新闻搜索失败: ticker={}, error={}", ticker, e.getMessage());
            return Collections.emptyList();
        }
    }
```

> **注意**：`INewsSearchProvider` 在同包下无需 import。

---

### Task 7 - SinaNewsDataProviderTest.java（新增）

**文件路径**：`ai-agent-study-trading-infra/src/test/java/denny/ai/agent/trading/infra/provider/SinaNewsDataProviderTest.java`

**测试策略**：通过匿名内部类覆盖 `SinaNewsApiClient.search()` 方法注入 Mock 数据，不依赖外部网络。

**测试用例**：

| 用例 | 测试内容 |
|---|---|
| `searchNews_success` | Mock 新浪返回 2 条数据，验证 `NewsItemVO` 字段映射正确（title、source、publishTime、summary、url） |
| `searchNews_emptyKeyword` | 关键字为空字符串，验证返回空列表 |
| `searchNews_nullKeyword` | 关键字为 null，验证返回空列表 |
| `searchNews_emptyResult` | Mock 新浪返回空列表，验证返回空列表 |
| `searchNews_pagination` | Mock 第一页 20 条、第二页 5 条，请求 25 条，验证触发 2 次调用、返回 25 条 |

**代码模板**：

```java
package denny.ai.agent.trading.infra.provider;

import denny.ai.agent.trading.api.vo.NewsItemVO;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SinaNewsDataProvider 单元测试。
 * <p>
 * 通过匿名内部类覆盖 search() 方法注入模拟数据，
 * 不依赖外部网络。
 */
class SinaNewsDataProviderTest {

    @FunctionalInterface
    interface SinaSearchHandler {
        List<Map<String, String>> handle(String keyword, int page, int pageSize);
    }

    private SinaNewsDataProvider createTestProvider(SinaSearchHandler handler) {
        return new SinaNewsDataProvider(new SinaNewsApiClient() {
            @Override
            public List<Map<String, String>> search(String keyword, int page, int pageSize) {
                return handler.handle(keyword, page, pageSize);
            }
        });
    }

    @Test
    void searchNews_success() {
        SinaNewsDataProvider provider = createTestProvider((keyword, page, pageSize) -> {
            assertEquals("人工智能", keyword);
            return List.of(
                    Map.of("title", "人工智能概念股集体涨停",
                            "ctime", "1776926109",
                            "url", "https://finance.sina.com.cn/test",
                            "intro", "受政策利好影响，AI板块大涨",
                            "media_name", "市场资讯"),
                    Map.of("title", "人工智能助力企业数字化转型",
                            "ctime", "1776925344",
                            "url", "https://finance.sina.com.cn/test2",
                            "intro", "多家企业引入AI技术",
                            "media_name", "财经频道")
            );
        });

        List<NewsItemVO> result = provider.searchNews("人工智能", 10);

        assertNotNull(result);
        assertEquals(2, result.size());

        NewsItemVO item1 = result.get(0);
        assertEquals("人工智能概念股集体涨停", item1.getTitle());
        assertEquals("市场资讯", item1.getSource());
        assertEquals("https://finance.sina.com.cn/test", item1.getUrl());
        assertEquals("受政策利好影响，AI板块大涨", item1.getSummary());
        assertNotNull(item1.getPublishTime());
    }

    @Test
    void searchNews_emptyKeyword() {
        SinaNewsDataProvider provider = createTestProvider((keyword, page, pageSize) -> Collections.emptyList());
        List<NewsItemVO> result = provider.searchNews("", 10);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void searchNews_nullKeyword() {
        SinaNewsDataProvider provider = createTestProvider((keyword, page, pageSize) -> Collections.emptyList());
        List<NewsItemVO> result = provider.searchNews(null, 10);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void searchNews_emptyResult() {
        SinaNewsDataProvider provider = createTestProvider((keyword, page, pageSize) -> Collections.emptyList());
        List<NewsItemVO> result = provider.searchNews("不存在的关键字", 10);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void searchNews_pagination() {
        final int[] callCount = {0};
        SinaNewsDataProvider provider = createTestProvider((keyword, page, pageSize) -> {
            callCount[0]++;
            if (page == 1) {
                List<Map<String, String>> page1 = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    page1.add(Map.of("title", "第1页第" + i + "条",
                            "ctime", "1776926109", "url", "http://test.com/1",
                            "intro", "摘要", "media_name", "来源"));
                }
                return page1;
            }
            if (page == 2) {
                List<Map<String, String>> page2 = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    page2.add(Map.of("title", "第2页第" + i + "条",
                            "ctime", "1776925344", "url", "http://test.com/2",
                            "intro", "摘要", "media_name", "来源"));
                }
                return page2;
            }
            return Collections.emptyList();
        });

        // 请求 25 条，应触发 2 页获取（第一页 20 条 + 第二页 5 条）
        List<NewsItemVO> result = provider.searchNews("测试", 25);
        assertEquals(25, result.size());
        assertEquals(2, callCount[0]);
    }
}
```

---

### Task 8 - application.yml

**文件路径**：`ai-agent-study-app/src/main/resources/application.yml`

**改动内容**：在 `data-source` 配置块下增加新浪财经配置项。

**改动前**（参考位置，`data-source` 块内）：

```yaml
spring:
  ai:
    trading:
      data-source:
        provider: tushare
        tushare-token: ${TUSHARE_TOKEN:}
        cache:
          historical-bars-ttl: 86400
          fundamental-data-ttl: 3600
          sentiment-ttl: 1800
          stock-info-ttl: 300
```

**改动后**：

```yaml
spring:
  ai:
    trading:
      data-source:
        provider: tushare
        tushare-token: ${TUSHARE_TOKEN:}
        sina-news-enabled: true            # 新增：是否启用新浪财经新闻搜索，默认 true
        sina-news-page-size: 50            # 新增：新闻搜索最大返回条数，默认 50
        cache:
          historical-bars-ttl: 86400
          fundamental-data-ttl: 3600
          sentiment-ttl: 1800
          stock-info-ttl: 300
          news-ttl: 1800                   # 新增：新闻数据缓存 TTL，30 分钟
```

---

### Task 9 - 编译验证

**执行命令**：

```bash
mvn clean compile -f ai-agent-study-trading/pom.xml
mvn test -f ai-agent-study-trading/pom.xml -Dtest=SinaNewsDataProviderTest
```

**验证点**：
- [ ] `mvn compile` 成功，无 error
- [ ] `SinaNewsDataProviderTest` 所有 5 个测试用例通过
- [ ] `TushareStockDataProviderTest` 原有测试用例全部通过（确认 `getNews` 替换未破坏其他方法）

---

## 架构设计

```
IStockDataProvider
├── TushareStockDataProvider
│   ├── getStockInfo()         → Tushare API（股票数据）
│   ├── getHistoricalBars()    → Tushare API（K线数据）
│   ├── getTechnicalIndicators()→ 本地计算
│   ├── getFundamentalData()   → Tushare API（财务数据）
│   ├── getSentiment()         → 本地推导
│   └── getNews(ticker,limit)  → SinaNewsDataProvider（新浪财经）
│
└── MockStockDataProvider（Phase 1-5 回退）

INewsSearchProvider
└── SinaNewsDataProvider
    └── SinaNewsApiClient → feed.mix.sina.com.cn（HTTP GET，JSON）
```

---

## 频率限制与容错策略

| 场景 | 建议频率 | 容错策略 |
|---|---|---|
| 每次 AI 分析时搜索 | ≤ 5 次/分钟 | 本地缓存 5 分钟内不重复请求相同关键字 |
| 定时热点监控 | ≤ 1 次/5 分钟 | 缓存命中则直接返回 |
| 突发新闻事件 | 临时提频，用完即停 | 触发 429/403 时指数退避（1s→2s→4s） |

> ⚠️ **重要限制**：新浪财经接口为非官方接口，无 SLA 保障。建议生产环境自行评估风险。

---

## 执行记录

|| 时间 | 执行人 | Task # | 状态变更 | 备注 |
|---|---|---|---|---|
|| 2026-04-23 15:28 | Agent | 1-9 | pending → pass | Task 1-3 新增三个文件，Task 4-6 修改既有文件，Task 7 新增测试，Task 8 修改配置，Task 9 编译通过，30 个测试全部通过 |

> ⚠️ **附加修复**：执行过程中发现预存文件 `tools/` 包（`TradingToolCallbackProvider`、`TradingToolCallbacks`）存在编译问题：(1) 缺少 `spring-ai-model` 依赖；(2) `TradingToolCallbacks` 使用了不存在的 `ToolInputSchema` 类，改为 JSON 字符串；(3) `formatVolume` 方法名写错为 `fvol`。均已修复。预存测试文件 `TushareStockDataProviderTest` 因构造函数签名变更（两参→三参）导致编译失败，已同步修复，新增 `mockNewsSearchProvider` 字段。

---

## 状态说明

- **pending**：待执行
- **pass**：已通过
- **fail**：执行失败（需记录失败原因）
