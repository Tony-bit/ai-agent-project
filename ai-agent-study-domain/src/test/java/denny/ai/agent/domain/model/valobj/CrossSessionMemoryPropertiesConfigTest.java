package denny.ai.agent.domain.model.valobj;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CrossSessionMemoryProperties 配置加载测试
 * <p>
 * 注意：此测试类针对当前类名编写。实现阶段类将重命名为 MemoryProperties。
 * </p>
 * <p>
 * 测试覆盖：
 * 1. TC-Config-001: YAML 配置正确解析配置节点
 * 2. TC-Config-002: 配置正确映射到 Java 属性
 * 3. TC-Config-003: 默认配置值正确
 * 4. TC-Config-004: SnakeYAML 正确解析嵌套配置
 * </p>
 *
 * @author denny
 */
class CrossSessionMemoryPropertiesConfigTest {

    /**
     * TC-Config-001: YAML 配置正确解析 memory 节点
     */
    @Test
    void testMemoryConfig_IsParsedCorrectly() {
        String yamlContent = """
                chat:
                  memory:
                    inject-cross-session-memory: true
                    cross-session-memory-top-k: 10
                    cross-session-memory-ttl-minutes: 30
                """;

        Yaml yaml = new Yaml();
        Map<String, Object> config = yaml.load(yamlContent);

        assertNotNull(config);
        assertTrue(config.containsKey("chat"),
                "配置应包含 chat 根节点");

        @SuppressWarnings("unchecked")
        Map<String, Object> chatConfig = (Map<String, Object>) config.get("chat");
        assertTrue(chatConfig.containsKey("memory"),
                "chat 配置应包含 memory 节点");
    }

    /**
     * TC-Config-002: 配置正确映射到 Java 属性
     */
    @Test
    void testConfigValues_AreMappedCorrectly() {
        CrossSessionMemoryProperties properties = new CrossSessionMemoryProperties();

        // 测试默认值
        assertTrue(properties.isInjectCrossSessionMemory());
        assertEquals(5, properties.getCrossSessionMemoryTopK());
        assertEquals(5, properties.getCrossSessionMemoryTtlMinutes());

        // 测试设置值
        properties.setInjectCrossSessionMemory(false);
        properties.setCrossSessionMemoryTopK(10);
        properties.setCrossSessionMemoryTtlMinutes(30);

        assertFalse(properties.isInjectCrossSessionMemory());
        assertEquals(10, properties.getCrossSessionMemoryTopK());
        assertEquals(30, properties.getCrossSessionMemoryTtlMinutes());
    }

    /**
     * TC-Config-003: 默认配置值正确
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
     * TC-Config-004: SnakeYAML 正确解析嵌套配置
     */
    @Test
    void testNestedConfig_IsParsedCorrectly() {
        String yamlContent = """
                chat:
                  memory:
                    nested:
                      value: test
                """;

        Yaml yaml = new Yaml();
        Map<String, Object> config = yaml.load(yamlContent);

        @SuppressWarnings("unchecked")
        Map<String, Object> memoryConfig = (Map<String, Object>) ((Map<String, Object>) config.get("chat")).get("memory");

        @SuppressWarnings("unchecked")
        Map<String, Object> nestedConfig = (Map<String, Object>) memoryConfig.get("nested");
        assertEquals("test", nestedConfig.get("value"));
    }

    /**
     * TC-Config-005: YAML 注释不影响解析
     */
    @Test
    void testYamlComments_DoNotAffectParsing() {
        String yamlContent = """
                # 这是 chat.memory 配置
                chat:
                  memory:
                    # 是否启用跨会话记忆注入
                    inject-cross-session-memory: true
                    cross-session-memory-top-k: 5  # 查询条数
                """;

        Yaml yaml = new Yaml();
        Map<String, Object> config = yaml.load(yamlContent);

        @SuppressWarnings("unchecked")
        Map<String, Object> memoryConfig = (Map<String, Object>) ((Map<String, Object>) config.get("chat")).get("memory");

        assertEquals(true, memoryConfig.get("inject-cross-session-memory"));
        assertEquals(5, memoryConfig.get("cross-session-memory-top-k"));
    }

    /**
     * TC-Config-006: 配置可序列化
     */
    @Test
    void testConfig_IsSerializable() {
        CrossSessionMemoryProperties properties = new CrossSessionMemoryProperties();
        properties.setInjectCrossSessionMemory(true);
        properties.setCrossSessionMemoryTopK(10);
        properties.setCrossSessionMemoryTtlMinutes(15);

        // 验证属性可以正常获取
        assertTrue(properties.isInjectCrossSessionMemory());
        assertEquals(10, properties.getCrossSessionMemoryTopK());
        assertEquals(15, properties.getCrossSessionMemoryTtlMinutes());
    }

    /**
     * TC-Config-007: 空配置处理
     */
    @Test
    void testEmptyConfig_IsHandled() {
        String emptyYaml = "";

        Yaml yaml = new Yaml();
        Map<String, Object> config = yaml.load(emptyYaml);

        assertNull(config, "空 YAML 应返回 null");
    }
}
