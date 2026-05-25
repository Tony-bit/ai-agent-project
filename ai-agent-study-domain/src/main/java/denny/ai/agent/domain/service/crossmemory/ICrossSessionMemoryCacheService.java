package denny.ai.agent.domain.service.crossmemory;


/**
 * 跨会话长期记忆缓存服务接口
 *
 * @author denny
 */
public interface ICrossSessionMemoryCacheService {

    /**
     * Redis 缓存 Key 前缀
     */
    String CACHE_KEY_PREFIX = "mem0:persona:";

    /**
     * 默认缓存 TTL（分钟）。固定存 5 分钟，缓存到期后重新从 Mem0 查询。
     */
    int DEFAULT_TTL_MINUTES = 5;

    /**
     * 获取跨会话记忆
     * <p>
     * 查询顺序：Redis 缓存 → Mem0 API → 回填 Redis。
     * 缓存固定存 5 分钟，命中后不刷新 TTL，缓存到期后重新查询 Mem0 获取最新画像。
     * </p>
     *
     * @param userId 用户ID
     * @return 格式化后的记忆字符串，无记忆时返回空字符串
     */
    String getCrossSessionMemories(String userId);

}
