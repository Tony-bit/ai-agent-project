package denny.ai.agent.api.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 会话列表查询结果
 *
 * @author denny
 */
@Data
public class SessionListResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话列表
     */
    private List<ChatSessionVO> sessions;

    /**
     * 是否有更多数据
     */
    private boolean hasMore;

    /**
     * 下一页游标时间
     */
    private String nextCursorTime;

    /**
     * 下一页游标ID
     */
    private Long nextCursorId;

    /**
     * 本次返回数量
     */
    private int size;

    public static SessionListResult empty() {
        SessionListResult result = new SessionListResult();
        result.setSessions(List.of());
        result.setHasMore(false);
        result.setSize(0);
        return result;
    }
}
