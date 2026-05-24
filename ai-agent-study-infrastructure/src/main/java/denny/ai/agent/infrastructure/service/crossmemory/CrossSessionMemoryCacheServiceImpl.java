package denny.ai.agent.infrastructure.service.crossmemory;

import denny.ai.agent.domain.model.valobj.CrossSessionMemoryProperties;
import denny.ai.agent.domain.service.crossmemory.ICrossSessionMemoryCacheService;
import denny.ai.agent.infrastructure.mem0.Mem0RestClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 跨会话长期记忆缓存服务实现
 * <p>
 * 缓存策略：Redis 命中则刷新 TTL，未命中则查 Mem0 → 回填 Redis。
 * </p>
 *
 * @author denny
 */
@Slf4j
@Service
public class CrossSessionMemoryCacheServiceImpl implements ICrossSessionMemoryCacheService {

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Mem0RestClient mem0RestClient;

    @Resource
    private CrossSessionMemoryProperties crossSessionMemoryProperties;

    @Override
    public String getCrossSessionMemories(String userId) {
        if (stringRedisTemplate == null) {
            log.warn("StringRedisTemplate 未配置，直接查 Mem0");
            return queryFromMem0(userId);
        }

        String cacheKey = CACHE_KEY_PREFIX + userId;
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                // 命中缓存，刷新 TTL
                Boolean updated = stringRedisTemplate.expire(cacheKey, DEFAULT_TTL_MINUTES, TimeUnit.MINUTES);
                log.info("跨会话记忆命中缓存，已刷新 TTL, userId={}, updated={}", userId, updated);
                return cached;
            }

            // 未命中，查 Mem0 并写入缓存
            String persona = mem0RestClient.getPersona(userId);
            if (persona != null && !persona.isEmpty()) {
                stringRedisTemplate.opsForValue().set(cacheKey, persona, DEFAULT_TTL_MINUTES, TimeUnit.MINUTES);
                log.info("跨会话记忆已写入缓存, userId={}, ttl={}min", userId, DEFAULT_TTL_MINUTES);
                return formatPersonaResult(persona);
            }
            return "";

        } catch (Exception e) {
            log.error("跨会话记忆缓存异常，降级查 Mem0, userId={}, error={}", userId, e.getMessage(), e);
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
            stringRedisTemplate.expire(cacheKey, DEFAULT_TTL_MINUTES, TimeUnit.MINUTES);
            log.debug("跨会话记忆 TTL 已刷新, userId={}", userId);
        } catch (Exception e) {
            log.warn("刷新 TTL 失败, userId={}, error={}", userId, e.getMessage());
        }
    }

    /**
     * 从 Mem0 查询用户画像并格式化
     */
    private String queryFromMem0(String userId) {
        try {
            String persona = mem0RestClient.getPersona(userId);
            if (persona != null && !persona.isEmpty()) {
                return formatPersonaResult(persona);
            }
            return "";
        } catch (Exception e) {
            log.warn("Mem0 查询用户画像失败，降级返回空, userId={}, error={}", userId, e.getMessage());
            return "";
        }
    }

    /**
     * 将用户画像格式化为 Prompt 上下文字符串
     */
    private String formatPersonaResult(String persona) {
        if (persona == null || persona.isEmpty()) {
            return "";
        }
        return "\n\n[用户画像]\n" + persona;
    }
}
