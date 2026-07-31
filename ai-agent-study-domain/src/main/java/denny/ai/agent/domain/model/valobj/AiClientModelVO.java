package denny.ai.agent.domain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天模型配置，值对象
 * @author denny
 * 2025/6/27 17:43
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientModelVO {

    /**
     * 全局唯一模型ID
     */
    private String modelId;

    /**
     * 关联的API配置ID
     */
    private String apiId;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 模型类型：openai、deepseek、claude
     */
    private String modelType;

    /**
     * 重试配置
     */
    private RetryConfig retryConfig;

    /**
     * 压缩配置
     */
    private CompressionConfig compressionConfig;

    /**
     * Optional model-level streaming timeout overrides.
     */
    private StreamingTimeoutConfig streamingTimeoutConfig;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RetryConfig {

        /**
         * 是否启用
         */
        private boolean enabled;

        /**
         * 最大重试次数
         */
        @Builder.Default
        private int maxAttempts = 3;

        /**
         * 初始重试间隔（毫秒）
         */
        @Builder.Default
        private long initialIntervalMs = 1000;

        /**
         * 重试间隔倍数
         */
        @Builder.Default
        private double multiplier = 2.0;

        /**
         * 最大重试间隔（毫秒）
         */
        @Builder.Default
        private long maxIntervalMs = 10000;

        /**
         * 可重试错误 code 列表，命中则必定重试
         */
        private List<String> retryableErrorCodes;

        /**
         * 不可重试错误 code 黑名单，命中则直接抛出不重试
         */
        private List<String> nonRetryableErrorCodes;
    }

    /**
     * 压缩配置
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CompressionConfig {

        /**
         * 是否启用压缩
         */
        /**
         * 压缩模型ID
         */


        /**
         * 主动压缩阈值（token数），超过此值则触发压缩
         * 默认为 160,000（200,000 × 80%）
         */
        @Builder.Default
        private int proactiveThresholdTokens = 160000;

        /**
         * 最大压缩尝试次数
         */
        @Builder.Default
        private int maxCompressionAttempts = 3;

        /**
         * 摘要最大token数
         */
        @Builder.Default
        private int maxSummaryTokens = 2000;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StreamingTimeoutConfig {
        private Long firstChunkTimeoutMs;
        private Long stallThresholdMs;
        private Long chunkIdleTimeoutMs;
        private Long queryAttemptTimeoutMs;

        /** @deprecated use {@link #firstChunkTimeoutMs}. */
        @Deprecated
        private Long firstContentTimeoutMs;
        /** @deprecated use {@link #stallThresholdMs}. */
        @Deprecated
        private Long idleTimeoutMs;
        /** @deprecated use {@link #queryAttemptTimeoutMs}. */
        @Deprecated
        private Long totalTimeoutMs;
    }

}
