package denny.ai.agent.domain.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 聊天会话实体
 *
 * @author denny
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatSessionEntity {

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

    public static final int STATUS_ACTIVE = 1;

    public static final int STATUS_CLOSED = 0;

    public static final int ADD_MEMORY_NO = 0;

    public static final int ADD_MEMORY_YES = 1;
}
