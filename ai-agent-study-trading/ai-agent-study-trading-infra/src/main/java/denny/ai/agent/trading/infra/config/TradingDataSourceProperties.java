package denny.ai.agent.trading.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据源配置属性类。
 * <p>
 * 配置前缀: spring.ai.trading.data-source
 */
@Data
@Component
@ConfigurationProperties(prefix = "spring.ai.trading.data-source")
public class TradingDataSourceProperties {

    /**
     * 数据源类型: mock / tushare
     */
    private String provider = "mock";

    /**
     * Tushare 个人 Token，从 tushare.pro 注册获取
     */
    private String tushareToken;

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

    @Data
    public static class CacheConfig {
        /**
         * 历史 K 线数据缓存 TTL（秒），默认 1 天 = 86400
         */
        private long historicalBarsTtl = 86400;

        /**
         * 基本面数据缓存 TTL（秒），默认 1 小时 = 3600
         */
        private long fundamentalDataTtl = 3600;

        /**
         * 新闻数据缓存 TTL（秒），默认 30 分钟 = 1800
         */
        private long newsTtl = 1800;

        /**
         * 情绪数据缓存 TTL（秒），默认 30 分钟 = 1800
         */
        private long sentimentTtl = 1800;

        /**
         * 股票基本信息缓存 TTL（秒），默认 5 分钟 = 300
         */
        private long stockInfoTtl = 300;
    }
}
