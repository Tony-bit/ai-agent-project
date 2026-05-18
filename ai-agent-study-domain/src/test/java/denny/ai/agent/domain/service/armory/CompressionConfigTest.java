package denny.ai.agent.domain.service.armory;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.CompressionConfig;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * CompressionConfig 配置单元测试
 * <p>
 * 测试覆盖：
 * 1. TC-Cfg-001: 默认阈值
 * 2. TC-Cfg-002: 自定义阈值
 * 3. TC-Cfg-003: 阈值为 0
 * 4. TC-Cfg-004: 阈值为负数
 * 5. TC-Cfg-005: 阈值为 200000
 * 6. TC-Cfg-006: 默认 maxSummaryTokens
 * 7. TC-Cfg-007: 自定义 maxSummaryTokens
 * 8. TC-Cfg-008: 压缩未启用
 * </p>
 */
public class CompressionConfigTest {

    /**
     * TC-Cfg-001: 默认阈值
     */
    @Test
    public void testDefaultProactiveThreshold() {
        CompressionConfig config = CompressionConfig.builder().build();

        assertFalse(config.isEnabled());
        assertEquals(160000, config.getProactiveThresholdTokens());
    }

    /**
     * TC-Cfg-002: 自定义阈值
     */
    @Test
    public void testCustomProactiveThreshold() {
        CompressionConfig config = CompressionConfig.builder()
                .proactiveThresholdTokens(100000)
                .build();

        assertEquals(100000, config.getProactiveThresholdTokens());
    }

    /**
     * TC-Cfg-003: 阈值为 0
     */
    @Test
    public void testZeroThreshold() {
        CompressionConfig config = CompressionConfig.builder()
                .proactiveThresholdTokens(0)
                .build();

        assertEquals(0, config.getProactiveThresholdTokens());
    }

    /**
     * TC-Cfg-004: 阈值为负数
     */
    @Test
    public void testNegativeThreshold() {
        CompressionConfig config = CompressionConfig.builder()
                .proactiveThresholdTokens(-1)
                .build();

        assertEquals(-1, config.getProactiveThresholdTokens());
    }

    /**
     * TC-Cfg-005: 阈值为 200000
     */
    @Test
    public void test200000Threshold() {
        CompressionConfig config = CompressionConfig.builder()
                .proactiveThresholdTokens(200000)
                .build();

        assertEquals(200000, config.getProactiveThresholdTokens());
    }

    /**
     * TC-Cfg-006: 默认 maxSummaryTokens
     */
    @Test
    public void testDefaultMaxSummaryTokens() {
        CompressionConfig config = CompressionConfig.builder().build();

        assertEquals(2000, config.getMaxSummaryTokens());
    }

    /**
     * TC-Cfg-007: 自定义 maxSummaryTokens
     */
    @Test
    public void testCustomMaxSummaryTokens() {
        CompressionConfig config = CompressionConfig.builder()
                .maxSummaryTokens(5000)
                .build();

        assertEquals(5000, config.getMaxSummaryTokens());
    }

    /**
     * TC-Cfg-008: 压缩未启用
     */
    @Test
    public void testCompressionDisabled() {
        CompressionConfig config = CompressionConfig.builder()
                .enabled(false)
                .build();

        assertFalse(config.isEnabled());
    }

    /**
     * TC-Cfg-009: 压缩启用
     */
    @Test
    public void testCompressionEnabled() {
        CompressionConfig config = CompressionConfig.builder()
                .enabled(true)
                .build();

        assertTrue(config.isEnabled());
    }

    /**
     * TC-Cfg-010: 默认 maxCompressionAttempts
     */
    @Test
    public void testDefaultMaxCompressionAttempts() {
        CompressionConfig config = CompressionConfig.builder().build();

        assertEquals(3, config.getMaxCompressionAttempts());
    }

    /**
     * TC-Cfg-011: 自定义 maxCompressionAttempts
     */
    @Test
    public void testCustomMaxCompressionAttempts() {
        CompressionConfig config = CompressionConfig.builder()
                .maxCompressionAttempts(5)
                .build();

        assertEquals(5, config.getMaxCompressionAttempts());
    }
}
