package denny.ai.agent.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天会话 PO
 */
@Data
public class ChatSessionPO {

    private Long id;

    private String sessionId;

    private String userId;

    private String agentId;

    private String clientId;

    private Integer messageCount;

    private String firstQuery;

    private String lastResponse;

    private Integer status;

    private Integer addMemory;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
