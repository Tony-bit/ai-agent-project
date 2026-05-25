package denny.ai.agent.domain.model.valobj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryProperties 配置类单元测试
 */
class MemoryPropertiesTest {

    @Test
    void testDefaultValues_AreCorrect() {
        MemoryProperties properties = new MemoryProperties();

        assertTrue(properties.isInjectPersona(), "injectPersona 默认应为 true");
        assertEquals(5, properties.getPersonaTopK(), "personaTopK 默认应为 5");
        assertEquals(5, properties.getPersonaTtlMinutes(), "personaTtlMinutes 默认应为 5");
        assertEquals(5, properties.getEpisodicMemoryLimit(), "episodicMemoryLimit 默认应为 5");
    }

    @Test
    void testConfigurationPrefix_IsCorrect() throws Exception {
        var annotation = MemoryProperties.class.getAnnotation(
                org.springframework.boot.context.properties.ConfigurationProperties.class);
        assertNotNull(annotation, "类上应有 @ConfigurationProperties 注解");
        assertEquals("ai.memory", annotation.prefix(), "配置前缀应为 ai.memory");
    }

    @Test
    void testAllFields_WorkCorrectly() {
        MemoryProperties properties = new MemoryProperties();

        // 测试 setInjectPersona
        properties.setInjectPersona(false);
        assertFalse(properties.isInjectPersona());
        properties.setInjectPersona(true);
        assertTrue(properties.isInjectPersona());

        // 测试 setPersonaTopK
        properties.setPersonaTopK(10);
        assertEquals(10, properties.getPersonaTopK());

        // 测试 setPersonaTtlMinutes
        properties.setPersonaTtlMinutes(30);
        assertEquals(30, properties.getPersonaTtlMinutes());

        // 测试 setEpisodicMemoryLimit
        properties.setEpisodicMemoryLimit(8);
        assertEquals(8, properties.getEpisodicMemoryLimit());
    }

    @Test
    void testComponentAnnotation_Exists() {
        var component = MemoryProperties.class.getAnnotation(
                org.springframework.stereotype.Component.class);
        assertNotNull(component, "应有 @Component 注解");
    }
}
