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
     * 模型对应的mcp tool ID列表
     */
    private List<String> toolMcpIds;

    /**
     * 重试配置
     */
    private RetryConfig retryConfig;

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

}
