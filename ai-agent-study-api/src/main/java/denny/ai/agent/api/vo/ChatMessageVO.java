package denny.ai.agent.api.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天消息 VO（用于接口返回）
 *
 * @author denny
 */
@Data
public class ChatMessageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer messageIndex;

    private String role;

    private String content;

    private String model;

    private Long latencyMs;

    private LocalDateTime createTime;
}
