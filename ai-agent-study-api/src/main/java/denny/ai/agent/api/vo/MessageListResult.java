package denny.ai.agent.api.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 消息列表查询结果
 *
 * @author denny
 */
@Data
public class MessageListResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息列表
     */
    private List<ChatMessageVO> messages;

    /**
     * 是否有更多数据
     */
    private boolean hasMore;

    /**
     * 下一页游标索引
     */
    private Integer nextCursorIndex;

    /**
     * 本次返回数量
     */
    private int size;

    public static MessageListResult empty() {
        MessageListResult result = new MessageListResult();
        result.setMessages(List.of());
        result.setHasMore(false);
        result.setSize(0);
        return result;
    }
}
