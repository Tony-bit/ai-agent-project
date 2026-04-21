package denny.ai.agent.infrastructure.service;

import denny.ai.agent.domain.service.chatsession.ISessionEndDetectionService;
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
     */
    private static final String LLM_PROMPT_TEMPLATE =
            "判断以下用户消息是否表示当前问题已解决、对话已结束。\n\n" +
            "判断规则：\n" +
            "- 用户明确表示\"明白了\"、\"好的\"、\"解决了\"等，说明问题已解决，对话可以结束\n" +
            "- 如果用户说\"好的，但是xxx\"或\"好的，另外xxx\"，说明还有后续问题，对话未结束\n" +
            "- 如果用户提出了新问题或新需求，对话未结束\n\n" +
            "用户消息：%s\n\n" +
            "请只回答 JSON 格式：{\"ended\": true/false, \"reason\": \"原因\"}";

    @Resource
    private ChatClient chatClient;

    @Resource
    private SessionActivityTracker sessionActivityTracker;

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
    boolean matchEndKeyword(String message) {
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

    /**
     * 解析 LLM 返回的 JSON 响应
     *
     * @param response LLM 返回的原始文本
     * @return true = ended，false = 未结束
     */
    boolean parseLlmResponse(String response) {
        log.debug("LLM 判断结果: response={}", response);
        if (response == null) {
            return false;
        }
        // 兼容 "ended":true 和 "ended": true（有空格）两种格式
        if (response.contains("\"ended\":true") || response.contains("\"ended\": true")) {
            return true;
        }
        return false;
    }
}
