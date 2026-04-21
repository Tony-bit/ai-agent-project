package denny.ai.agent.infrastructure.service;

import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.service.chatsession.ISessionEndDetectionService;
import denny.ai.agent.domain.service.chatsession.ISessionMemoryPersistenceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 会话结束检测服务实现
 * <p>
 * 三层兜底策略：
 * 1. 关键词正则匹配（优先，快速）
 * 2. LLM 语义兜底（正则未命中时）
 * 3. 滑动窗口兜底（8 分钟无活动，内存追踪）
 * </p>
 *
 * @author denny
 */
@Slf4j
@Service
public class SessionEndDetectionServiceImpl implements ISessionEndDetectionService {

    /**
     * 结束关键词正则列表（不区分大小写，句首或句尾匹配）
     * 命中其中任一正则即认为是结束语
     */
    private static final List<Pattern> END_PATTERNS = List.of(
            // 标准结束语：好的、明白了、了解了、没问题、解决了、就这样、再见等
            // ()分组，[]是字符类，关键词用|分隔
            Pattern.compile("^(好的|好|知道了|明白了|(?:了)?解(?:了|啦)|没问题|没问题了|解决了|已解决|就这样|就这样吧|结束|再见|感谢|谢谢)([，,。.！!？?].*)?$"),
            // 感叹/确认类结束语
            Pattern.compile("^(好的好的|嗯嗯|嗯|好的哈|好的呢)[，,。.！!]*$"),
            // "好的，我明白了" 型：好的/好 + 逗号 + 内容（不含逗号）+ 句号结尾
            Pattern.compile("^(好的|好)[，,]+\\s*[^，,]+[。.！!？?]*$")
    );

    /**
     * 否定关键词正则列表（虽然含结束词但实际未结束）
     * 命中任一正则则无论是否匹配结束词，都认为未结束
     */
    private static final List<Pattern> NEGATIVE_PATTERNS = List.of(
            Pattern.compile("但是.*"),
            Pattern.compile("不过.*"),
            Pattern.compile("然而.*"),
            Pattern.compile("可是.*"),
            Pattern.compile("可是.*怎么办"),
            Pattern.compile("但.*怎么处理"),
            Pattern.compile("但.*怎么办"),
            Pattern.compile("还是.*问题"),
            Pattern.compile("另外.*"),
            Pattern.compile("还有.*"),
            Pattern.compile("补充.*"),
            Pattern.compile("再.*问.*"),
            Pattern.compile("继续.*")
    );

    /**
     * LLM 判断提示词
     * <p>
     * 仅基于 lastMessage 判断，不查对话历史。
     * 判断标准：显式结束语 → 结束；转折引出新问题 → 未结束；模糊场景 → 未结束（走滑动窗口兜底）
     * </p>
     */
    private static final String LLM_PROMPT_TEMPLATE =
            "## 任务\n" +
            "判断以下用户消息是否表示对话已结束。\n\n" +
            "## 判断标准（请严格遵循）\n" +
            "### 视为结束（ended = true）\n" +
            "- 用户明确表示接受/理解/满意：\"好的\"、\"明白了\"、\"知道了\"、\"谢谢\"、\"感谢\"、\"解决了\"、\"没问题\"、\"就这样\"、\"再见了\" 等\n" +
            "- 用户说\"好的，明白了\"、\"好的，谢谢\"、\"好的，我试试\" 等\n" +
            "- 用户只是随口道谢且无后续问题\n\n" +
            "### 视为未结束（ended = false）\n" +
            "- 用户说\"好的，但是xxx\"、\"好的，不过xxx\"、\"好的，另外xxx\"（转折引出新问题）\n" +
            "- 用户提出了新问题或新需求\n" +
            "- 用户对回答不满意或有疑问（\"不对\"、\"不是这样\"、\"但是怎么办\"）\n" +
            "- 用户要求补充或完善（\"再详细说一下\"、\"还有吗\"）\n" +
            "- 用户说\"继续问\"、\"再问一下\"、\"还有问题\"\n" +
            "- 用户说\"好的，我先试试/看看/测试一下\"（暗示可能还会回来）\n" +
            "- 用户仅回复表情、无实质内容\n" +
            "- 语义模糊，无法明确判断为结束\n\n" +
            "## 用户消息\n" +
            "%s\n\n" +
            "## 输出要求\n" +
            "请严格只回答 JSON 格式，不要输出任何其他内容：\n" +
            "{\"ended\": true 或 false, \"reason\": \"判断原因（中文，简洁）\"}";

    @Resource
    private ChatClient chatClient;

    @Resource
    private SessionActivityTracker sessionActivityTracker;

    @Resource
    private ISessionMemoryPersistenceService sessionMemoryPersistenceService;

    @Override
    public boolean isSessionEnded(String sessionId, String userId, String lastMessage) {
        // ========== 第一层：关键词正则匹配 ==========
        boolean matchedEnd = matchEndKeyword(lastMessage);
        if (matchedEnd) {
            log.info("会话 {} 关键词命中结束，判断为已结束, lastMessage={}", sessionId, lastMessage);
            return true;
        }

        // ========== 第二层：LLM 兜底 ==========
        try {
            boolean llmEnded = checkByLlm(lastMessage);
            if (llmEnded) {
                log.info("会话 {} LLM 判断为已结束, lastMessage={}", sessionId, lastMessage);
                return true;
            }
        } catch (Exception e) {
            log.warn("会话 {} LLM 调用异常，降级到时间窗口判断, error={}", sessionId, e.getMessage());
        }

        // ========== 第三层：滑动窗口兜底 ==========
        return checkBySlidingWindow(sessionId, userId, lastMessage);
    }

    // ========== 第三层：滑动窗口兜底 ==========

    /**
     * 通过滑动窗口判断会话是否结束
     * <p>
     * 使用内存中的 ConcurrentHashMap 追踪会话活动时间，
     * 超过 8 分钟无活动则判定为结束
     * </p>
     *
     * @param sessionId    会话ID
     * @param userId       用户ID
     * @param lastMessage  最后一条用户消息
     * @return true = 已超时，false = 仍在活动时间窗口内
     */
    boolean checkBySlidingWindow(String sessionId, String userId, String lastMessage) {
        if (userId == null || userId.isBlank()) {
            log.debug("会话 {} userId 为空，跳过滑动窗口判断", sessionId);
            return false;
        }

        // 先记录当前活动（异常不影响后续超时判断）
        try {
            sessionActivityTracker.recordActivity(userId, sessionId, lastMessage);
        } catch (Exception e) {
            log.warn("会话 {} 记录活动异常: {}", sessionId, e.getMessage());
        }

        // 判断是否超时
        boolean expired = sessionActivityTracker.isExpired(userId, sessionId);
        if (expired) {
            log.info("会话 {} 滑动窗口超时，判断为已结束, userId={}", sessionId, userId);
            return true;
        }

        log.debug("会话 {} 滑动窗口未超时，判断为未结束, userId={}", sessionId, userId);
        return false;
    }

    // ========== 第一层：关键词正则匹配 ==========

    /**
     * 关键词正则匹配
     * <p>
     * 判断逻辑：
     * 1. 先匹配否定模式（但是、不过、另外等），命中则直接返回 false
     * 2. 再匹配结束模式，命中则返回 true
     * </p>
     *
     * @param message 用户消息
     * @return true = 命中结束词，false = 未命中
     */
    @Override
    public boolean matchEndKeyword(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.trim();

        // 第一步：先匹配否定模式，命中则未结束
        for (Pattern negPattern : NEGATIVE_PATTERNS) {
            if (negPattern.matcher(trimmed).find()) {
                log.debug("消息命中否定模式，判定为未结束: pattern={}, message={}", negPattern.pattern(), message);
                return false;
            }
        }

        // 第二步：匹配结束模式，命中则结束
        for (Pattern endPattern : END_PATTERNS) {
            if (endPattern.matcher(trimmed).find()) {
                log.debug("消息命中结束模式，判定为已结束: pattern={}, message={}", endPattern.pattern(), message);
                return true;
            }
        }

        return false;
    }

    // ========== 第二层：LLM 兜底 ==========

    /**
     * 通过 LLM 判断会话是否结束
     *
     * @param lastMessage 用户最后一条消息
     * @return true = LLM 判断为已结束，false = LLM 判断为未结束或调用失败
     */
    boolean checkByLlm(String lastMessage) {
        if (lastMessage == null || lastMessage.isBlank()) {
            return false;
        }

        try {
            String response = chatClient.prompt()
                    .user(String.format(LLM_PROMPT_TEMPLATE, lastMessage))
                    .call()
                    .content();

            return parseLlmResponse(response);
        } catch (Exception e) {
            log.warn("LLM 调用失败: error={}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean parseLlmResponse(String response) {
        log.debug("LLM 判断结果: response={}", response);
        if (response == null) {
            return false;
        }
        if (response.contains("\"ended\":true") || response.contains("\"ended\": true")) {
            return true;
        }
        return false;
    }

    @Override
    public void recordActivity(String userId, String sessionId, String lastMessage) {
        sessionActivityTracker.recordActivity(userId, sessionId, lastMessage);
    }

    @Override
    public void syncSessionToMemory(String userId, String sessionId) {
        sessionMemoryPersistenceService.syncSessionToMemory(userId, sessionId);
    }

    @Override
    public void removeActivity(String userId, String sessionId) {
        sessionActivityTracker.removeActivity(userId, sessionId);
    }

}
