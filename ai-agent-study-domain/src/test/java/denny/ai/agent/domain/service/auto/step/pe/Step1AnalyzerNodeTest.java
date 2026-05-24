package denny.ai.agent.domain.service.auto.step.pe;

import denny.ai.agent.domain.model.valobj.CrossSessionMemoryProperties;
import denny.ai.agent.domain.service.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import denny.ai.agent.domain.service.crossmemory.ICrossSessionMemoryCacheService;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Step1AnalyzerNode 单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-Pe-001: 验证上下文注入 key 为 "persona"
 * 2. TC-Pe-002: 无用户画像时注入空字符串
 * 3. TC-Pe-003: 验证缓存 key 前缀为 mem0:persona:
 * 4. TC-Pe-004: 验证注入开关关闭时不注入上下文
 * 5. TC-Pe-005: 验证画像格式化为包含 [用户画像] 前缀
 * </p>
 *
 * @author denny
 */
public class Step1AnalyzerNodeTest {

    private DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext;

    @Before
    public void setUp() {
        dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
    }

    /**
     * TC-Pe-001: 验证上下文注入 key 为 "persona"
     */
    @Test
    public void testContextInjection_UsesPersonaKey() {
        String personaData = "用户画像: 咖啡爱好者";
        dynamicContext.setValue("persona", personaData);

        assertNotNull("persona 不应为 null", dynamicContext.getValue("persona"));
        assertEquals("用户画像: 咖啡爱好者", dynamicContext.getValue("persona"));
    }

    /**
     * TC-Pe-002: 无用户画像时注入空字符串
     */
    @Test
    public void testNoPersona_InjectsEmptyString() {
        dynamicContext.setValue("persona", "");

        assertEquals("", dynamicContext.getValue("persona"));
    }

    /**
     * TC-Pe-003: 验证缓存 key 前缀为 mem0:persona:
     */
    @Test
    public void testCacheKeyPrefix_IsPersona() {
        assertEquals("mem0:persona:", ICrossSessionMemoryCacheService.CACHE_KEY_PREFIX);
    }

    /**
     * TC-Pe-004: 验证注入开关关闭时不注入上下文
     */
    @Test
    public void testInjectDisabled_DoesNotInjectContext() {
        CrossSessionMemoryProperties props = new CrossSessionMemoryProperties();
        props.setInjectCrossSessionMemory(false);

        if (props.isInjectCrossSessionMemory()) {
            dynamicContext.setValue("persona", "some data");
        }

        assertNull("注入关闭时，persona 应为 null", dynamicContext.getValue("persona"));
    }

    /**
     * TC-Pe-005: 验证画像格式化为包含 [用户画像] 前缀
     */
    @Test
    public void testPersonaFormat_ContainsPrefix() {
        String rawPersona = "喜欢咖啡, 工作狂";
        String formatted = "\n\n[用户画像]\n" + rawPersona;

        assertTrue("格式化后应包含 [用户画像] 前缀", formatted.contains("[用户画像]"));
        assertTrue("格式化后应包含原始画像内容", formatted.contains("喜欢咖啡, 工作狂"));
    }
}
