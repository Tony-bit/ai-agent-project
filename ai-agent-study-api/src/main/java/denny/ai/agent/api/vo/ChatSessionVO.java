package denny.ai.agent.api.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天会话 VO（用于接口返回）
 *
 * @author denny
 */
@Data
public class ChatSessionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sessionId;

    private String firstQuery;

    private String lastResponse;

    private Integer messageCount;

    private Integer status;

    private LocalDateTime createTime;
}
