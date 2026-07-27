package denny.ai.agent.trading.infra.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Component;

/**
 * 交易数据缓存服务。
 * <p>
 * 使用 Spring Cache + Caffeine 实现缓存策略（TTL 由 {@link CaffeineCacheConfig} 配置）：
 * <ul>
 *   <li>日线数据：按 ticker+startDate+endDate 缓存，TTL=1天</li>
 *   <li>财务数据：按 ticker 缓存，TTL=1小时</li>
 *   <li>新闻数据：按 ticker 缓存，TTL=30分钟</li>
 *   <li>情绪数据：按 ticker 缓存，TTL=30分钟</li>
 *   <li>技术指标：按 ticker 缓存，TTL=1天</li>
 * </ul>
 *
 * @see CaffeineCacheConfig
 */
@Slf4j
@Component
public class TradingDataCache {

    public static final String CACHE_STOCK_INFO = "stockInfo";
    public static final String CACHE_HISTORICAL_BARS = "historicalBars";
    public static final String CACHE_FUNDAMENTAL_DATA = "fundamentalData";
    public static final String CACHE_NEWS = "news";
    public static final String CACHE_TECHNICAL_INDICATORS = "technicalIndicators";
    public static final String CACHE_SENTIMENT = "sentiment";

    private final CacheManager cacheManager;

    public TradingDataCache(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    // ==================== 股票基本信息 ====================

    @Cacheable(value = CACHE_STOCK_INFO,
            key = "T(denny.ai.agent.trading.api.cache.TradingNamespaceKeyFactory).rawData('stock-data','stock_info',#ticker,'latest',T(java.util.Map).of(),'v1')",
            unless = "#result == null")
    public Object getStockInfo(String ticker) {
        return null;
    }

    @CacheEvict(value = CACHE_STOCK_INFO,
            key = "T(denny.ai.agent.trading.api.cache.TradingNamespaceKeyFactory).rawData('stock-data','stock_info',#ticker,'latest',T(java.util.Map).of(),'v1')")
    public void evictStockInfo(String ticker) {
        log.debug("Evicted stock info cache for: {}", ticker);
    }

    // ==================== 历史K线数据 ====================

    @Cacheable(value = CACHE_HISTORICAL_BARS,
            key = "T(denny.ai.agent.trading.api.cache.TradingNamespaceKeyFactory).rawData('stock-data','daily',#ticker,(#startDate ?: 'default') + '-' + (#endDate ?: 'default'),T(java.util.Map).of(),'v1')",
            unless = "#result == null")
    public Object getHistoricalBars(String ticker, String startDate, String endDate) {
        return null;
    }

    @CacheEvict(value = CACHE_HISTORICAL_BARS, allEntries = true)
    public void evictHistoricalBars() {
        log.debug("Evicted all historical bars cache");
    }

    // ==================== 财务数据 ====================

    @Cacheable(value = CACHE_FUNDAMENTAL_DATA,
            key = "T(denny.ai.agent.trading.api.cache.TradingNamespaceKeyFactory).rawData('stock-data','fina_indicator',#ticker,'latest',T(java.util.Map).of(),'v1')",
            unless = "#result == null")
    public Object getFundamentalData(String ticker) {
        return null;
    }

    @CacheEvict(value = CACHE_FUNDAMENTAL_DATA,
            key = "T(denny.ai.agent.trading.api.cache.TradingNamespaceKeyFactory).rawData('stock-data','fina_indicator',#ticker,'latest',T(java.util.Map).of(),'v1')")
    public void evictFundamentalData(String ticker) {
        log.debug("Evicted fundamental data cache for: {}", ticker);
    }

    // ==================== 新闻数据 ====================

    @Cacheable(value = CACHE_NEWS,
            key = "T(denny.ai.agent.trading.api.cache.TradingNamespaceKeyFactory).rawData('stock-data','news',#ticker,'latest',T(java.util.Map).of('limit',#limit),'v1')",
            unless = "#result == null")
    public Object getNews(String ticker, int limit) {
        return null;
    }

    @CacheEvict(value = CACHE_NEWS,
            key = "T(denny.ai.agent.trading.api.cache.TradingNamespaceKeyFactory).rawData('stock-data','news',#ticker,'latest',T(java.util.Map).of('limit',#limit),'v1')")
    public void evictNews(String ticker, int limit) {
        log.debug("Evicted news cache for: {} limit: {}", ticker, limit);
    }

    // ==================== 情绪数据 ====================

    @Cacheable(value = CACHE_SENTIMENT,
            key = "T(denny.ai.agent.trading.api.cache.TradingNamespaceKeyFactory).rawData('stock-data','sentiment',#ticker,'latest',T(java.util.Map).of(),'v1')",
            unless = "#result == null")
    public Object getSentiment(String ticker) {
        return null;
    }

    @CacheEvict(value = CACHE_SENTIMENT,
            key = "T(denny.ai.agent.trading.api.cache.TradingNamespaceKeyFactory).rawData('stock-data','sentiment',#ticker,'latest',T(java.util.Map).of(),'v1')")
    public void evictSentiment(String ticker) {
        log.debug("Evicted sentiment cache for: {}", ticker);
    }

    // ==================== 技术指标 ====================

    @Cacheable(value = CACHE_TECHNICAL_INDICATORS,
            key = "T(denny.ai.agent.trading.api.cache.TradingNamespaceKeyFactory).rawData('stock-data','technical_indicators',#ticker,(#startDate ?: 'default') + '-' + (#endDate ?: 'default'),T(java.util.Map).of(),'v1')",
            unless = "#result == null")
    public Object getTechnicalIndicators(String ticker, String startDate, String endDate) {
        return null;
    }

    @CacheEvict(value = CACHE_TECHNICAL_INDICATORS, allEntries = true)
    public void evictTechnicalIndicators() {
        log.debug("Evicted all technical indicators cache");
    }

    // ==================== 批量清除 ====================

    @CacheEvict(value = {CACHE_STOCK_INFO, CACHE_HISTORICAL_BARS, CACHE_FUNDAMENTAL_DATA,
            CACHE_NEWS, CACHE_TECHNICAL_INDICATORS, CACHE_SENTIMENT}, allEntries = true)
    public void evictAll() {
        log.info("Evicted all trading data cache");
    }

    // ==================== 缓存统计 ====================

    public TradingCacheStats getStats() {
        return TradingCacheStats.builder()
                .stockInfo(getCacheStatInfo(CACHE_STOCK_INFO))
                .historicalBars(getCacheStatInfo(CACHE_HISTORICAL_BARS))
                .fundamentalData(getCacheStatInfo(CACHE_FUNDAMENTAL_DATA))
                .news(getCacheStatInfo(CACHE_NEWS))
                .technicalIndicators(getCacheStatInfo(CACHE_TECHNICAL_INDICATORS))
                .sentiment(getCacheStatInfo(CACHE_SENTIMENT))
                .build();
    }

    private CacheStatInfo getCacheStatInfo(String cacheName) {
        org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
        if (cache instanceof CaffeineCache caffeineCache) {
            Cache<Object, Object> nativeCache = caffeineCache.getNativeCache();
            com.github.benmanes.caffeine.cache.stats.CacheStats stats = nativeCache.stats();
            return CacheStatInfo.builder()
                    .size(nativeCache.estimatedSize())
                    .hitCount(stats.hitCount())
                    .missCount(stats.missCount())
                    .hitRate(stats.hitRate())
                    .build();
        }
        return CacheStatInfo.builder().size(0).hitCount(0).missCount(0).hitRate(0.0).build();
    }

    @lombok.Data
    @lombok.Builder
    public static class CacheStatInfo {
        private long size;
        private long hitCount;
        private long missCount;
        private double hitRate;
    }

    @lombok.Data
    @lombok.Builder
    public static class TradingCacheStats {
        private CacheStatInfo stockInfo;
        private CacheStatInfo historicalBars;
        private CacheStatInfo fundamentalData;
        private CacheStatInfo news;
        private CacheStatInfo technicalIndicators;
        private CacheStatInfo sentiment;
    }
}
