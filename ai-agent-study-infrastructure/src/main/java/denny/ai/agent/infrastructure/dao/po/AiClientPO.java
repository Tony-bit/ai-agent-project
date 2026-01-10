package denny.ai.agent.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI客户端配置表
 */
@Data
public class AiClientPO {
    /**
     * 主键ID
     */
    private Long id;

    /**
     * 客户端ID
     */
    private String clientId;

    /**
     * 客户端名称
     */
    private String clientName;

    /**
     * 描述
     */
    private String description;

    /**
     * 状态(0:禁用,1:启用)
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

