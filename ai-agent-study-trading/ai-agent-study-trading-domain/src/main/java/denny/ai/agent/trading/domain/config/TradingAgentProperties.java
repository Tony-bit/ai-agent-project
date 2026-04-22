package denny.ai.agent.trading.domain.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * 交易 Agent 配置属性类。
 * <p>
 * 配置前缀: spring.ai.trading
 */
@Data
@ConfigurationProperties(prefix = "spring.ai.trading")
public class TradingAgentProperties {

    /**
     * 是否启用交易 Agent
     */
    private boolean enabled = true;

    /**
     * 默认启用的分析师类型
     */
    private List<String> defaultAnalysts = Arrays.asList("FUNDAMENTAL", "TECHNICAL", "SENTIMENT", "NEWS");

    /**
     * 最大辩论轮次
     */
    private int maxDebateRounds = 2;

    /**
     * 评分配置子对象
     */
    private RatingConfig rating = new RatingConfig();

    /**
     * Prompt 版本，用于 A/B 测试切换（如 "v1"、"v2"）
     */
    private String promptVersion = "default";

    /**
     * 评分阈值配置
     */
    @Data
    public static class RatingConfig {
        /**
         * 买入阈值，综合评分 >= 此值则推荐买入
         */
        private double buyThreshold = 3.5;

        /**
         * 卖出阈值，综合评分 <= 此值则推荐卖出
         */
        private double sellThreshold = 2.0;
    }
}
