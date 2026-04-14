package denny.ai.agent.domain.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 聊天消息实体
 *
 * @author denny
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessageEntity {

    private Long id;

    private String sessionId;

    private Integer messageIndex;

    private String role;

    private String content;

    private String model;

    private Long latencyMs;

    private String traceId;

    private LocalDateTime createTime;

    public static final String ROLE_USER = "user";

    public static final String ROLE_ASSISTANT = "assistant";
}
