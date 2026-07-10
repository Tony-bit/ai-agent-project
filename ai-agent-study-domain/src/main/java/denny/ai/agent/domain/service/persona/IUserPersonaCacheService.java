package denny.ai.agent.domain.service.persona;

/**
 * 用户画像缓存服务接口
 * <p>
 * 提供用户画像的缓存查询能力，优先从 Redis 取缓存，未命中时查 Mem0 并回填缓存。
 * 缓存 key = mem0:persona:{userId}，TTL = 30 分钟。
 * </p>
 *
 * @author denny
 */
public interface IUserPersonaCacheService {

    String CACHE_KEY_PREFIX = "mem0:persona:";

    /**
     * 获取用户画像
     *
     * @param userId 用户ID
     * @return 格式化后的用户画像字符串，无画像时返回空字符串
     */
    String getUserPersona(String userId);

    /**
     * 刷新缓存 TTL
     *
     * @param userId 用户ID
     */
    void refreshTtl(String userId);
}
