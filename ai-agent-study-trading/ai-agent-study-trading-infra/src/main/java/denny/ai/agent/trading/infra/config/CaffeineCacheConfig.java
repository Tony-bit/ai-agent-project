package denny.ai.agent.trading.infra.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 交易数据缓存配置。
 * <p>
 * 使用 Caffeine 本地缓存，为每类数据配置独立的 TTL。
 */
@Configuration
@EnableCaching
public class CaffeineCacheConfig {

    public static final String CACHE_STOCK_INFO = "stockInfo";
    public static final String CACHE_HISTORICAL_BARS = "historicalBars";
    public static final String CACHE_FUNDAMENTAL_DATA = "fundamentalData";
    public static final String CACHE_NEWS = "news";
    public static final String CACHE_TECHNICAL_INDICATORS = "technicalIndicators";
    public static final String CACHE_SENTIMENT = "sentiment";

    @Bean
    public CacheManager tradingCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // 默认规格：最大10000条，写入后1小时过期
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(1, TimeUnit.HOURS)
        );
        // 禁止缓存 null 值
        manager.setAllowNullValues(false);
        return manager;
    }
}
