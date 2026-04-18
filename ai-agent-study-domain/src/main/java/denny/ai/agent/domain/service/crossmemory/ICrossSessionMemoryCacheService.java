package denny.ai.agent.domain.service.crossmemory;

/**
 * 跨会话长期记忆缓存服务接口
 * <p>
 * 提供跨会话记忆的缓存能力，优先从 Redis 取，未命中时查 Mem0 并写入缓存。
 * 调用方无需感知底层存储细节。
 * </p>
 *
 * @author denny
 */
public interface ICrossSessionMemoryCacheService {

    /**
     * Redis 缓存 Key 前缀
     */
    String CACHE_KEY_PREFIX = "mem0:cross-session:";

    /**
     * 默认缓存 TTL（分钟）
     */
    int DEFAULT_TTL_MINUTES = 30;

    /**
     * 获取跨会话记忆
     * <p>
     * 查询顺序：Redis 缓存 → Mem0 API → 回填 Redis
     * 每次命中缓存时自动刷新 TTL 至 30 分钟。
     * </p>
     *
     * @param userId 用户ID
     * @return 格式化后的记忆字符串，无记忆时返回空字符串
     */
    String getCrossSessionMemories(String userId);

    /**
     * 主动刷新指定用户的缓存 TTL
     *
     * @param userId 用户ID
     */
    void refreshTtl(String userId);
}
