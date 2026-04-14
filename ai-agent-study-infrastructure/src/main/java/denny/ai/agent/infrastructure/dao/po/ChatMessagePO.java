package denny.ai.agent.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息 PO
 */
@Data
public class ChatMessagePO {

    private Long id;

    private String sessionId;

    private Integer messageIndex;

    private String role;

    private String content;

    private String model;

    private Long latencyMs;

    private String traceId;

    private LocalDateTime createTime;
}
