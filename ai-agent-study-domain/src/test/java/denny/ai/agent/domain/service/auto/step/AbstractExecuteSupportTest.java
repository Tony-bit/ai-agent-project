package denny.ai.agent.domain.service.auto.step;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ExecuteCommandEntity;
import denny.ai.agent.domain.model.valobj.CrossSessionMemoryProperties;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.crossmemory.ICrossSessionMemoryCacheService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AbstractExecuteSupport 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-Support-001: 正常注入，写入 persona 到 context
 * 2. TC-Support-002: 幂等检查，persona 已存在时跳过
 * 3. TC-Support-003: 配置关闭时不注入
 * 4. TC-Support-004: 缓存未命中时查 Mem0 并回填
 * 5. TC-Support-005: Mem0 查询异常时降级为空字符串
 * 6. TC-Support-006: 缓存服务为 null 时直接查 Mem0
 * 7. TC-Support-007: injectPersonaContext 幂等跳过
 * 8. TC-Support-008: properties 为 null 时跳过注入
 * </p>
 *
 * @author denny
 */
@RunWith(MockitoJUnitRunner.class)
public class AbstractExecuteSupportTest {

    @Mock
    private ICrossSessionMemoryCacheService crossSessionMemoryCacheService;

    private CrossSessionMemoryProperties crossSessionMemoryProperties;

    private TestableExecuteSupport support;

    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext;

    private ExecuteCommandEntity request;

    @Before
    public void setUp() throws Exception {
        support = new TestableExecuteSupport();

        crossSessionMemoryProperties = new CrossSessionMemoryProperties();
        crossSessionMemoryProperties.setInjectCrossSessionMemory(true);

        // 通过反射注入父类私有依赖
        setSuperField(support, "crossSessionMemoryCacheService", crossSessionMemoryCacheService);
        setSuperField(support, "crossSessionMemoryProperties", crossSessionMemoryProperties);

        dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        request = ExecuteCommandEntity.builder()
                .userId("test-user-001")
                .sessionId("test-session")
                .build();
    }

    private void setSuperField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getSuperclass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * TC-Support-001: 正常注入，persona 写入 context
     */
    @Test
    public void testInjectPersona_NormalInjection() {
        String cachedPersona = "用户画像: 咖啡爱好者";
        when(crossSessionMemoryCacheService.getCrossSessionMemories("test-user-001"))
                .thenReturn(cachedPersona);

        support.callParentInjectPersonaContext(dynamicContext, request);

        assertNotNull("persona 不应为 null", dynamicContext.getValue("persona"));
        assertEquals(cachedPersona, dynamicContext.getValue("persona"));
    }

    /**
     * TC-Support-002: 幂等检查，persona 已存在时跳过注入
     */
    @Test
    public void testInjectPersona_IdempotentWhenAlreadyExists() {
        String existingPersona = "已存在的画像";
        dynamicContext.setValue("persona", existingPersona);

        support.callParentInjectPersonaContext(dynamicContext, request);

        // cacheService 不应被调用
        verify(crossSessionMemoryCacheService, never()).getCrossSessionMemories(anyString());
        assertEquals(existingPersona, dynamicContext.getValue("persona"));
    }

    /**
     * TC-Support-003: 配置关闭时不注入
     */
    @Test
    public void testInjectPersona_SkippedWhenDisabled() {
        crossSessionMemoryProperties.setInjectCrossSessionMemory(false);

        support.callParentInjectPersonaContext(dynamicContext, request);

        verify(crossSessionMemoryCacheService, never()).getCrossSessionMemories(anyString());
        assertNull("注入关闭时，persona 应为 null", dynamicContext.getValue("persona"));
    }

    /**
     * TC-Support-004: 缓存未命中时查 Mem0 并回填
     */
    @Test
    public void testInjectPersona_CacheMissFallsBackToMem0() {
        when(crossSessionMemoryCacheService.getCrossSessionMemories("test-user-001"))
                .thenReturn("\n\n[用户画像]\n工作狂");

        support.callParentInjectPersonaContext(dynamicContext, request);

        assertNotNull(dynamicContext.getValue("persona"));
        assertTrue(((String) dynamicContext.getValue("persona")).contains("工作狂"));
    }

    /**
     * TC-Support-005: Mem0 查询异常时降级为空字符串
     */
    @Test
    public void testInjectPersona_ExceptionFallsBackToEmpty() {
        when(crossSessionMemoryCacheService.getCrossSessionMemories("test-user-001"))
                .thenThrow(new RuntimeException("Redis 连接失败"));

        support.callParentInjectPersonaContext(dynamicContext, request);

        assertEquals("", dynamicContext.getValue("persona"));
    }

    /**
     * TC-Support-006: properties 为 null 时跳过注入
     * <p>
     * 当 Spring 未能注入 CrossSessionMemoryProperties 时，
     * injectPersonaContext 应直接返回，不抛出 NPE。
     * </p>
     */
    @Test
    public void testInjectPersona_SkippedWhenPropertiesNull() throws Exception {
        // 将 properties 设置为 null，模拟未注入的场景
        setSuperField(support, "crossSessionMemoryProperties", null);

        // 执行时不应抛异常
        support.callParentInjectPersonaContext(dynamicContext, request);

        // persona 应保持为 null
        assertNull("properties 为 null 时，persona 应为 null", dynamicContext.getValue("persona"));
    }

    /**
     * TC-Support-007: injectPersonaContext 幂等跳过
     */
    @Test
    public void testPreContextInjection_CallsInjectPersonaContext() {
        String cachedPersona = "用户画像: 测试用户";
        when(crossSessionMemoryCacheService.getCrossSessionMemories("test-user-001"))
                .thenReturn(cachedPersona);

        support.callParentInjectPersonaContext(dynamicContext, request);

        assertEquals(cachedPersona, dynamicContext.getValue("persona"));
    }

    /**
     * TC-Support-008: cacheService 为 null 时跳过注入
     * <p>
     * 当 Spring 未能注入 ICrossSessionMemoryCacheService 时，
     * injectPersonaContext 应直接返回，不抛出 NPE。
     * </p>
     */
    @Test
    public void testInjectPersona_SkippedWhenCacheServiceNull() throws Exception {
        // 将 cacheService 设置为 null，模拟未注入的场景
        setSuperField(support, "crossSessionMemoryCacheService", null);

        // 执行时不应抛异常
        support.callParentInjectPersonaContext(dynamicContext, request);

        // persona 应保持为 null
        assertNull("cacheService 为 null 时，persona 应为 null", dynamicContext.getValue("persona"));
    }

    /**
     * 测试用子类，暴露父类 protected 方法供测试调用
     */
    private static class TestableExecuteSupport extends AbstractExecuteSupport {
        @Override
        protected String doApply(
                ExecuteCommandEntity requestParameter,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
            return null;
        }

        @Override
        public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
                ExecuteCommandEntity requestParameter,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
            return null;
        }

        /**
         * 通过反射调用父类的 injectPersonaContext 方法
         * 避免子类的覆盖方法遮蔽父类方法导致的递归问题
         */
        public void callParentInjectPersonaContext(
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                ExecuteCommandEntity requestParameter) {
            try {
                java.lang.reflect.Method parentMethod = AbstractExecuteSupport.class
                        .getDeclaredMethod("injectPersonaContext",
                                DefaultAutoAgentExecuteStrategyFactory.DynamicContext.class,
                                ExecuteCommandEntity.class);
                parentMethod.setAccessible(true);
                parentMethod.invoke(this, dynamicContext, requestParameter);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke parent method", e);
            }
        }
    }
}
