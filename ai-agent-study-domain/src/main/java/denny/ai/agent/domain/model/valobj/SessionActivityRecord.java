package denny.ai.agent.domain.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话活动记录
 * 用于滑动窗口超时检测
 *
 * @author denny
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionActivityRecord {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 最后一条用户消息
     */
    private String lastMessage;

    /**
     * 最后活动时间（毫秒时间戳）
     */
    private long lastTimestamp;
}
