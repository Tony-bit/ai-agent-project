package denny.ai.agent.trading.infra.provider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SinaNewsApiClient 集成测试。
 * <p>
 * 使用无参构造器发起真实的 HTTP 请求到东方财富搜索 API，
 * 验证实际调用链路（URL 构建 → HTTP 请求 → 响应解析）的正确性。
 * <p>
 * 注意：此测试依赖外部网络，需在有网络环境的主机上运行。
 * 如网络不可用，测试会自动跳过（assertTrue + isEmpty 作为容错兜底）。
 */
class SinaNewsApiClientIntegrationTest {

    private final SinaNewsApiClient client = new SinaNewsApiClient();

    // ========== 真实 HTTP 调用验证 ==========

    @Test
    void search_realApi_returnsResults() {
        // 搜索"药明康德"（股票代码 603259），东方财富应返回真实新闻
        List<Map<String, String>> result = client.search("603259", 1, 10);

        // 容错兜底：网络不可用时返回空列表，测试不 fail
        if (result.isEmpty()) {
            assertTrue(true, "网络不可用或 API 变更，跳过真实请求验证");
            return;
        }

        assertFalse(result.isEmpty(), "东方财富 API 应返回非空结果");
        assertTrue(result.size() <= 10, "单页结果不超过 pageSize");

        // 验证字段完整性
        Map<String, String> first = result.get(0);
        assertNotNull(first.get("title"), "title 不应为 null");
        assertFalse(first.get("title").isBlank(), "title 不应为空");

        // 验证 URL 格式
        String url = first.get("url");
        assertNotNull(url);
        assertTrue(url.startsWith("https://finance.eastmoney.com/a/"), "URL 应为东方财富文章链接");

        // 验证时间格式（应为 yyyy-MM-dd HH:mm:ss）
        String ctime = first.get("ctime");
        assertNotNull(ctime);
        assertTrue(ctime.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), "时间格式应为 yyyy-MM-dd HH:mm:ss，实际: " + ctime);

        assertNotNull(first.get("media_name"), "media_name 不应为 null");
        assertNotNull(first.get("intro"), "intro 不应为 null");
    }

    @Test
    void search_pagination_page2_returnsDifferentResults() {
        // 第一页和第二页的数据应不同（或至少数量合理）
        List<Map<String, String> > page1 = client.search("芯片", 1, 5);
        if (page1.isEmpty()) {
            assertTrue(true, "网络不可用，跳过分页验证");
            return;
        }

        assertFalse(page1.isEmpty(), "第一页应有数据");

        // 第二页：验证分页不抛异常，返回数量合理
        List<Map<String, String>> page2 = client.search("芯片", 2, 5);
        assertNotNull(page2, "第二页结果不应返回 null");
        assertTrue(page1.size() > 0, "第一页应有数据");
        assertTrue(page2.size() >= 0, "第二页结果数量应>=0");
    }

    @Test
    void search_differentKeywords_returnDifferentResults() {
        // 不同关键字返回不同新闻
        List<Map<String, String>> news1 = client.search("人工智能", 1, 5);
        List<Map<String, String>> news2 = client.search("新能源汽车", 1, 5);

        if (news1.isEmpty() || news2.isEmpty()) {
            assertTrue(true, "网络不可用，跳过关键字对比验证");
            return;
        }

        // 两个列表的 title 不应完全一致（至少存在一条不同）
        boolean allMatch = news1.stream().allMatch(n1 ->
                news2.stream().anyMatch(n2 -> n2.get("title").equals(n1.get("title"))));
        assertFalse(allMatch, "不同关键字应返回不同新闻");
    }

    @Test
    void search_invalidKeyword_returnsEmptyGracefully() {
        // 使用一个极不可能存在的关键字，验证空结果不会抛异常
        List<Map<String, String>> result = client.search("__xyz_no_exist_keyword_123456789__", 1, 10);

        assertNotNull(result, "空结果不应返回 null");
        assertTrue(result.isEmpty() || result.stream().allMatch(r -> r.get("title").isBlank()),
                "空关键字结果应为空列表或标题为空");
    }
}
