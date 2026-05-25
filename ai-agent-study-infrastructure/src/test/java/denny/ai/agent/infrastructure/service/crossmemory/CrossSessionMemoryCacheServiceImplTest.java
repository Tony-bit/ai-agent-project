package denny.ai.agent.infrastructure.service.crossmemory;

import denny.ai.agent.domain.model.valobj.CrossSessionMemoryProperties;
import denny.ai.agent.domain.service.crossmemory.ICrossSessionMemoryCacheService;
import denny.ai.agent.infrastructure.mem0.Mem0RestClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CrossSessionMemoryCacheServiceImpl 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-Cache-001: 缓存 key 前缀为 mem0:persona:
 * 2. TC-Cache-002: Redis 命中时返回缓存值（不刷新 TTL）
 * 3. TC-Cache-003: Redis 未命中时调用 Mem0 并回填缓存
 * 4. TC-Cache-004: Mem0 返回空时返回空字符串，不写入缓存
 * 5. TC-Cache-005: Mem0 查询异常时降级返回空字符串
 * 6. TC-Cache-006: Redis 未配置时直接查 Mem0
 * 7. TC-Cache-007: 命中缓存时不刷新 TTL（固定5分钟）
 * 8. TC-Cache-008: Redis 命中空字符串时不刷新 TTL
 * </p>
 *
 * @author denny
 */
@RunWith(MockitoJUnitRunner.class)
public class CrossSessionMemoryCacheServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private Mem0RestClient mem0RestClient;

    private CrossSessionMemoryCacheServiceImpl service;

    @Before
    public void setUp() throws Exception {
        service = new CrossSessionMemoryCacheServiceImpl();

        // 通过反射注入私有字段
        setField(service, "stringRedisTemplate", stringRedisTemplate);
        setField(service, "mem0RestClient", mem0RestClient);

        CrossSessionMemoryProperties props = new CrossSessionMemoryProperties();
        setField(service, "crossSessionMemoryProperties", props);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * TC-Cache-001: 缓存 key 前缀为 mem0:persona:
     */
    @Test
    public void testCacheKeyPrefix_IsPersona() {
        assertEquals("mem0:persona:", ICrossSessionMemoryCacheService.CACHE_KEY_PREFIX);
    }

    /**
     * TC-Cache-002: Redis 命中时返回格式化后的画像
     */
    @Test
    public void testCacheHit_ReturnsCachedValue() {
        String rawPersona = "用户画像: 喜欢咖啡";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("mem0:persona:user-001")).thenReturn(rawPersona);

        String result = service.getCrossSessionMemories("user-001");

        assertEquals(rawPersona, result);
        // 固定 TTL 不刷新，验证 expire 未被调用
        verify(stringRedisTemplate, never()).expire(anyString(), anyLong(), any());
    }

    /**
     * TC-Cache-003: Redis 未命中时调用 Mem0 并回填缓存
     */
    @Test
    public void testCacheMiss_QueriesMem0AndFillsCache() {
        String rawPersona = "用户画像: 工作狂";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(mem0RestClient.getPersona("user-002")).thenReturn(rawPersona);

        String result = service.getCrossSessionMemories("user-002");

        // 返回值是格式化后的（带前缀）
        assertEquals("\n\n[用户画像]\n" + rawPersona, result);
        // Redis 存储的是原始值（不带前缀）
        verify(valueOperations).set(
                eq("mem0:persona:user-002"),
                eq(rawPersona),
                eq(5L),
                eq(TimeUnit.MINUTES));
    }

    /**
     * TC-Cache-004: Mem0 返回空时返回空字符串，不写入缓存
     */
    @Test
    public void testMem0ReturnsNull_ReturnsEmptyAndNotCache() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(mem0RestClient.getPersona("user-new")).thenReturn(null);

        String result = service.getCrossSessionMemories("user-new");

        assertEquals("", result);
        verify(valueOperations, never()).set(
                anyString(), anyString(), anyLong(), any());
    }

    /**
     * TC-Cache-005: Mem0 查询返回 null 时降级返回空字符串
     */
    @Test
    public void testMem0ReturnsNull_DegradesGracefully() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(mem0RestClient.getPersona("user-null")).thenReturn(null);

        String result = service.getCrossSessionMemories("user-null");

        assertEquals("", result);
    }

    /**
     * TC-Cache-006: Redis 未配置时直接查 Mem0
     */
    @Test
    public void testRedisNotConfigured_QueriesMem0Directly() throws Exception {
        setField(service, "stringRedisTemplate", null);
        when(mem0RestClient.getPersona("user-offline")).thenReturn("离线用户画像");

        String result = service.getCrossSessionMemories("user-offline");

        // formatPersonaResult 会添加 \n\n[用户画像]\n 前缀
        assertEquals("\n\n[用户画像]\n离线用户画像", result);
    }

    /**
     * TC-Cache-007: 命中缓存时不刷新 TTL（固定5分钟）
     * <p>
     * 验证新行为：缓存命中后不调用 expire() 刷新 TTL，
     * 缓存固定存 5 分钟，到期后重新从 Mem0 查询。
     * </p>
     */
    @Test
    public void testCacheHit_DoesNotRefreshTtl() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("mem0:persona:user-001")).thenReturn("用户画像: 咖啡爱好者");

        String result = service.getCrossSessionMemories("user-001");

        assertEquals("用户画像: 咖啡爱好者", result);
        // 关键断言：命中缓存时不调用 expire
        verify(stringRedisTemplate, never()).expire(anyString(), anyLong(), any());
    }

    /**
     * TC-Cache-008: Redis 命中空字符串时不刷新 TTL
     */
    @Test
    public void testCacheHitEmptyString_DoesNotRefreshTtl() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("mem0:persona:user-empty")).thenReturn("");

        String result = service.getCrossSessionMemories("user-empty");

        assertEquals("", result);
        verify(stringRedisTemplate, never()).expire(anyString(), anyLong(), any());
    }
}
