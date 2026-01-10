package denny.ai.agent.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * OpenAI API配置表
 */
@Data
public class AiClientApiPO {
    /**
     * 自增主键ID
     */
    private Long id;

    /**
     * 全局唯一配置ID
     */
    private String apiId;

    /**
     * API基础URL
     */
    private String baseUrl;

    /**
     * API密钥
     */
    private String apiKey;

    /**
     * 补全API路径
     */
    private String completionsPath;

    /**
     * 嵌入API路径
     */
    private String embeddingsPath;

    /**
     * 状态：0-禁用，1-启用
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}

