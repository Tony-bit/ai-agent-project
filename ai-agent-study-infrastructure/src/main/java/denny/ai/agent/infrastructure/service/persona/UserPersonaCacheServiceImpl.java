package denny.ai.agent.infrastructure.service.persona;

import denny.ai.agent.domain.model.valobj.MemoryProperties;
import denny.ai.agent.domain.service.persona.IUserPersonaCacheService;
import denny.ai.agent.infrastructure.mem0.Mem0RestClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 用户画像缓存服务实现
 * <p>
 * 缓存策略：Redis 命中则直接返回，未命中则查 Mem0 → 回填 Redis（TTL=5分钟）。
 * </p>
 *
 * @author denny
 */
@Slf4j
@Service
public class UserPersonaCacheServiceImpl implements IUserPersonaCacheService {

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Mem0RestClient mem0RestClient;

    @Resource
    private MemoryProperties memoryProperties;

    @Override
    public String getUserPersona(String userId) {
        if (stringRedisTemplate == null) {
            return queryFromMem0(userId);
        }

        String cacheKey = CACHE_KEY_PREFIX + userId;
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                log.debug("用户画像命中缓存, userId={}", userId);
                return cached;
            }

            String result = queryFromMem0(userId);
            if (result != null && !result.isEmpty()) {
                stringRedisTemplate.opsForValue().set(cacheKey, result,
                        memoryProperties.getPersonaTtlMinutes(), TimeUnit.MINUTES);
            }
            return result != null ? result : "";

        } catch (Exception e) {
            log.warn("用户画像缓存异常，降级查 Mem0, userId={}, error={}", userId, e.getMessage());
            return queryFromMem0(userId);
        }
    }

    @Override
    public void refreshTtl(String userId) {
        if (stringRedisTemplate == null) {
            return;
        }
        String cacheKey = CACHE_KEY_PREFIX + userId;
        try {
            stringRedisTemplate.expire(cacheKey, memoryProperties.getPersonaTtlMinutes(), TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("刷新缓存TTL失败, userId={}, error={}", userId, e.getMessage());
        }
    }

    private String queryFromMem0(String userId) {
        try {
            String persona = mem0RestClient.getPersona(userId);
            return persona != null ? persona : "";
        } catch (Exception e) {
            log.warn("Mem0 查询用户画像失败，降级返回空, userId={}, error={}", userId, e.getMessage());
            return "";
        }
    }
}
