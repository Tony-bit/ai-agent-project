package denny.ai.agent.domain.service.chatsession;

/**
 * 会话结束检测服务接口
 *
 * @author denny
 */
public interface ISessionEndDetectionService {

    /**
     * 判断会话是否已结束（完整三层兜底）
     *
     * @param sessionId    会话ID
     * @param userId       用户ID
     * @param lastMessage  最后一条用户消息
     * @return true = 会话已结束，false = 会话未结束
     */
    boolean isSessionEnded(String sessionId, String userId, String lastMessage);

    /**
     * 正则关键词快速匹配（第一层判断）
     *
     * @param message 用户消息
     * @return true = 命中结束词，false = 未命中
     */
    boolean matchEndKeyword(String message);

    /**
     * 解析 LLM 返回的 JSON 响应，提取 ended 字段
     *
     * @param response LLM 返回的原始文本
     * @return true = ended，false = 未结束
     */
    boolean parseLlmResponse(String response);

    /**
     * 记录会话活动（更新滑动窗口活动时间）
     *
     * @param userId      用户ID
     * @param sessionId   会话ID
     * @param lastMessage 最后一条用户消息
     */
    void recordActivity(String userId, String sessionId, String lastMessage);

    /**
     * 将指定会话同步到 Mem0 长期记忆
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     */
    void syncSessionToMemory(String userId, String sessionId);

    /**
     * 将会话从滑动窗口中移除
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     */
    void removeActivity(String userId, String sessionId);
}
