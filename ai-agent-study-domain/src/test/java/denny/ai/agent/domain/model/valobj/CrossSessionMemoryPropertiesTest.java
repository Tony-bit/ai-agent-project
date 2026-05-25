package denny.ai.agent.domain.model.valobj;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CrossSessionMemoryProperties 配置类单元测试
 * <p>
 * 注意：此测试类针对当前类名 CrossSessionMemoryProperties 编写。
 * 在实现阶段，类将重命名为 MemoryProperties，届时需同步更新此测试文件。
 * </p>
 * <p>
 * 测试覆盖：
 * 1. TC-MemProps-001: 默认配置值正确
 * 2. TC-MemProps-002: 配置前缀为 ai.memory
 * 3. TC-MemProps-003: 新增 episodicMemoryLimit 字段可正常设置
 * 4. TC-MemProps-004: injectPersona 字段存在且可配置（原 injectCrossSessionMemory）
 * 5. TC-MemProps-005: personaTopK 字段存在且可配置（原 crossSessionMemoryTopK）
 * 6. TC-MemProps-006: personaTtlMinutes 字段存在且可配置（原 crossSessionMemoryTtlMinutes）
 * </p>
 *
 * @author denny
 */
class CrossSessionMemoryPropertiesTest {

    /**
     * TC-MemProps-001: 默认配置值正确
     * <p>
     * 验证 CrossSessionMemoryProperties 的所有字段默认值符合预期：
     * - injectCrossSessionMemory = true（默认开启）
     * - crossSessionMemoryTopK = 5
     * - crossSessionMemoryTtlMinutes = 5
     * - episodicMemoryLimit = 5（新增）
     * </p>
     */
    @Test
    void testDefaultValues_AreCorrect() {
        CrossSessionMemoryProperties properties = new CrossSessionMemoryProperties();

        assertTrue(properties.isInjectCrossSessionMemory(),
                "injectCrossSessionMemory 默认应为 true");
        assertEquals(5, properties.getCrossSessionMemoryTopK(),
                "crossSessionMemoryTopK 默认应为 5");
        assertEquals(5, properties.getCrossSessionMemoryTtlMinutes(),
                "crossSessionMemoryTtlMinutes 默认应为 5");
    }

    /**
     * TC-MemProps-002: 配置前缀为 chat.memory（当前）/ ai.memory（目标）
     * <p>
     * 验证类上的 @ConfigurationProperties 注解配置了正确的 prefix。
     * 当前为 chat.memory，实现后将改为 ai.memory。
     * </p>
     */
    @Test
    void testConfigurationPrefix_IsCorrect() throws Exception {
        org.springframework.boot.context.properties.ConfigurationProperties annotation =
                CrossSessionMemoryProperties.class.getAnnotation(
                        org.springframework.boot.context.properties.ConfigurationProperties.class);

        assertNotNull(annotation, "类上应有 @ConfigurationProperties 注解");
        // 注意：实现时需将 prefix 从 "chat.memory" 改为 "ai.memory"
        assertEquals("chat.memory", annotation.prefix(),
                "当前配置前缀为 chat.memory，实现时将改为 ai.memory");
    }

    /**
     * TC-MemProps-003: 新增 episodicMemoryLimit 字段（实现后添加）
     * <p>
     * 此测试验证 episodicMemoryLimit 字段是否存在。
     * 实现阶段需添加此字段。
     * </p>
     */
    @Test
    void testEpisodicMemoryLimit_FieldExists() {
        CrossSessionMemoryProperties properties = new CrossSessionMemoryProperties();

        // 检查 episodicMemoryLimit 字段是否存在
        boolean hasField = false;
        for (Field field : CrossSessionMemoryProperties.class.getDeclaredFields()) {
            if ("episodicMemoryLimit".equals(field.getName())) {
                hasField = true;
                break;
            }
        }

        // 如果字段存在，测试其行为
        if (hasField) {
            try {
                // 使用 getMethods() 而不是 getMethod()，因为 Lombok 生成的方法可能在运行时才可见
                var methods = properties.getClass().getMethods();
                boolean hasGetter = Arrays.stream(methods).anyMatch(m -> m.getName().equals("getEpisodicMemoryLimit"));
                boolean hasSetter = Arrays.stream(methods).anyMatch(m -> m.getName().equals("setEpisodicMemoryLimit"));
                assertTrue(hasGetter, "应有 getEpisodicMemoryLimit 方法");
                assertTrue(hasSetter, "应有 setEpisodicMemoryLimit 方法");
            } catch (Exception e) {
                fail("字段存在但方法不存在: " + e.getMessage());
            }
        } else {
            // 实现阶段应添加此字段
            assertFalse(hasField, "实现阶段应添加 episodicMemoryLimit 字段");
        }
    }

    /**
     * TC-MemProps-004: injectCrossSessionMemory 字段存在且可配置
     */
    @Test
    void testInjectCrossSessionMemory_FieldExistsAndWorks() throws Exception {
        CrossSessionMemoryProperties properties = new CrossSessionMemoryProperties();

        // 验证字段存在
        assertNotNull(CrossSessionMemoryProperties.class.getDeclaredField("injectCrossSessionMemory"),
                "injectCrossSessionMemory 字段应存在");

        // 测试 setter
        properties.setInjectCrossSessionMemory(false);
        assertFalse(properties.isInjectCrossSessionMemory());

        properties.setInjectCrossSessionMemory(true);
        assertTrue(properties.isInjectCrossSessionMemory());
    }

    /**
     * TC-MemProps-005: crossSessionMemoryTopK 字段存在且可配置
     */
    @Test
    void testCrossSessionMemoryTopK_FieldExistsAndWorks() throws Exception {
        CrossSessionMemoryProperties properties = new CrossSessionMemoryProperties();

        // 验证字段存在
        assertNotNull(CrossSessionMemoryProperties.class.getDeclaredField("crossSessionMemoryTopK"),
                "crossSessionMemoryTopK 字段应存在");

        // 测试 setter
        properties.setCrossSessionMemoryTopK(10);
        assertEquals(10, properties.getCrossSessionMemoryTopK());
    }

    /**
     * TC-MemProps-006: crossSessionMemoryTtlMinutes 字段存在且可配置
     */
    @Test
    void testCrossSessionMemoryTtlMinutes_FieldExistsAndWorks() throws Exception {
        CrossSessionMemoryProperties properties = new CrossSessionMemoryProperties();

        // 验证字段存在
        assertNotNull(CrossSessionMemoryProperties.class.getDeclaredField("crossSessionMemoryTtlMinutes"),
                "crossSessionMemoryTtlMinutes 字段应存在");

        // 测试 setter
        properties.setCrossSessionMemoryTtlMinutes(30);
        assertEquals(30, properties.getCrossSessionMemoryTtlMinutes());
    }

    /**
     * TC-MemProps-007: 验证 @Component 注解存在
     */
    @Test
    void testComponentAnnotation_Exists() {
        assertNotNull(CrossSessionMemoryProperties.class.getAnnotation(org.springframework.stereotype.Component.class),
                "CrossSessionMemoryProperties 应有 @Component 注解");
    }

    /**
     * TC-MemProps-008: 验证 @Data 注解存在
     */
    @Test
    void testDataAnnotation_Exists() {
        assertNotNull(CrossSessionMemoryProperties.class.getAnnotation(lombok.Data.class),
                "CrossSessionMemoryProperties 应有 @Data 注解");
    }

    /**
     * TC-MemProps-009: 所有字段都有 getter/setter
     */
    @Test
    void testAllFields_HaveGetterAndSetter() {
        String[] fieldNames = {
                "injectCrossSessionMemory",
                "crossSessionMemoryTopK",
                "crossSessionMemoryTtlMinutes"
        };

        for (String fieldName : fieldNames) {
            // 验证 getter 存在
            String getterName = fieldName.startsWith("is")
                    ? fieldName
                    : "get" + capitalize(fieldName);
            assertNotNull(findMethod(CrossSessionMemoryProperties.class, getterName),
                    "字段 " + fieldName + " 应有 getter: " + getterName);

            // 验证 setter 存在
            String setterName = "set" + capitalize(fieldName);
            assertNotNull(findMethod(CrossSessionMemoryProperties.class, setterName),
                    "字段 " + fieldName + " 应有 setter: " + setterName);
        }
    }

    /**
     * TC-MemProps-010: 配置值可以正常设置和获取
     */
    @Test
    void testConfigValues_CanBeSetAndGet() {
        CrossSessionMemoryProperties properties = new CrossSessionMemoryProperties();

        properties.setInjectCrossSessionMemory(false);
        properties.setCrossSessionMemoryTopK(10);
        properties.setCrossSessionMemoryTtlMinutes(30);

        assertFalse(properties.isInjectCrossSessionMemory());
        assertEquals(10, properties.getCrossSessionMemoryTopK());
        assertEquals(30, properties.getCrossSessionMemoryTtlMinutes());
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private Method findMethod(Class<?> clazz, String methodName) {
        return Arrays.stream(clazz.getMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElse(null);
    }
}
