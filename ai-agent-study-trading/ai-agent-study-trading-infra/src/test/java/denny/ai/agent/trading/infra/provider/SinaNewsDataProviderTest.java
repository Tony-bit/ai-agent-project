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
