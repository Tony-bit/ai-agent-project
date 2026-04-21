package denny.ai.agent.domain.service.chatsession;

/**
 * 会话结束检测服务接口
 *
 * @author denny
 */
public interface ISessionEndDetectionService {

    /**
     * 判断会话是否已结束
     *
     * @param sessionId    会话ID
     * @param userId       用户ID
     * @param lastMessage  最后一条用户消息
     * @return true = 会话已结束，false = 会话未结束
     */
    boolean isSessionEnded(String sessionId, String userId, String lastMessage);
}
