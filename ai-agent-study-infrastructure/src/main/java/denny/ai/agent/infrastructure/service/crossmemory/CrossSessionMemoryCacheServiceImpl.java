package denny.ai.agent.infrastructure.service.crossmemory;

import com.alibaba.cloud.ai.memory.mem0.core.Mem0ServiceClient;
import com.alibaba.cloud.ai.memory.mem0.model.Mem0ServerRequest;
import com.alibaba.cloud.ai.memory.mem0.model.Mem0ServerResp;
import denny.ai.agent.domain.model.valobj.CrossSessionMemoryProperties;
import denny.ai.agent.domain.service.crossmemory.ICrossSessionMemoryCacheService;
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
    private Mem0ServiceClient mem0ServiceClient;

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
            String memories = queryFromMem0(userId);
            if (!memories.isEmpty()) {
                stringRedisTemplate.opsForValue().set(cacheKey, memories, DEFAULT_TTL_MINUTES, TimeUnit.MINUTES);
                log.info("跨会话记忆已写入缓存, userId={}, ttl={}min", userId, DEFAULT_TTL_MINUTES);
            }
            return memories;

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
     * 从 Mem0 查询跨会话记忆并格式化
     */
    private String queryFromMem0(String userId) {
        try {
            Mem0ServerRequest.SearchRequest searchRequest = Mem0ServerRequest.SearchRequest.mem0Builder()
                    .query("用户相关信息和偏好")
                    .userId(userId)
                    .topK(crossSessionMemoryProperties.getCrossSessionMemoryTopK())
                    .build();
            Mem0ServerResp resp = mem0ServiceClient.searchMemories(searchRequest);
            return formatMem0Result(resp);
        } catch (Exception e) {
            log.warn("Mem0 查询跨会话记忆失败，降级返回空, userId={}, error={}", userId, e.getMessage());
            return "";
        }
    }

    /**
     * 将 Mem0ServerResp 格式化为 Prompt 上下文字符串
     */
    private String formatMem0Result(Mem0ServerResp resp) {
        if (resp == null || resp.getResults() == null || resp.getResults().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n[用户跨会话长期记忆]\n");
        int i = 1;
        for (Mem0ServerResp.Mem0Results item : resp.getResults()) {
            sb.append(i++).append(". ").append(item.getMemory());
            if (item.getMetadata() != null && !item.getMetadata().isEmpty()) {
                sb.append(" (metadata: ").append(item.getMetadata()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
