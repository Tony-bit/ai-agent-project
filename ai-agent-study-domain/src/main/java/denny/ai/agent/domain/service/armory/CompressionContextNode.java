package denny.ai.agent.domain.service.armory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import denny.ai.agent.domain.model.entity.ArmoryCommandEntity;
import denny.ai.agent.domain.model.entity.ChatMessageEntity;
import denny.ai.agent.domain.model.valobj.AiAgentClientFlowConfigVO;
import denny.ai.agent.domain.model.valobj.AiClientSystemPromptVO;
import denny.ai.agent.domain.model.valobj.enums.AiAgentEnumVO;
import denny.ai.agent.domain.model.valobj.enums.AiClientTypeEnumVO;
import denny.ai.agent.domain.service.armory.factory.DynamicContext;
import denny.ai.agent.domain.service.chatmemory.ChatMemoryPersistenceService;
import denny.ai.agent.domain.util.TokenCountUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 上下文压缩节点
 * <p>
 * 负责在 Prompt 超长时进行上下文压缩，通过调用压缩模型生成摘要，
 * 并采用滚动截断策略保留关键对话。
 * </p>
 *
 * @author denny
 */
@Slf4j
@Service
public class CompressionContextNode extends AbstractArmorySupport {

    private static final int DEFAULT_KEEP_ROUNDS = 2;
    private static final int DEFAULT_COMPRESSION_THRESHOLD = 160000;
    private static final int DEFAULT_MAX_SUMMARY_TOKENS = 2000;

    @Resource
    private ChatMemoryPersistenceService chatMemoryService;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        log.info("CompressionContextNode 开始处理压缩请求, sessionId={}", dynamicContext.getSessionId());

        // 1. 获取压缩助手配置
        Map<String, AiAgentClientFlowConfigVO> flowConfigMap = dynamicContext.getAiAgentClientFlowConfigVOMap();
        AiAgentClientFlowConfigVO compressionConfig = flowConfigMap != null
                ? flowConfigMap.get(AiClientTypeEnumVO.COMPRESSION_ASSISTANT.getCode())
                : null;

        // 2. 获取原始 Prompt
        Prompt originalPrompt = dynamicContext.getOriginalPrompt();
        String promptText = extractPromptText(originalPrompt);
        int originalTokenCount = TokenCountUtils.estimate(promptText);
        log.info("原始 Prompt token 数量: {}", originalTokenCount);

        // 3. 获取消息历史用于压缩
        String sessionId = dynamicContext.getSessionId();
        List<ChatMessageEntity> messages = chatMemoryService.getConversationHistory(sessionId);
        log.info("获取到消息历史数量: {}", messages != null ? messages.size() : 0);

        // 4. 调用压缩模型生成摘要
        String compressionPromptTemplate = getCompressionPromptTemplate(dynamicContext);
        int maxSummaryTokens = getMaxSummaryTokens(compressionConfig);
        String compressionRequest = buildCompressionRequest(compressionPromptTemplate, messages, maxSummaryTokens);

        ChatClient chatClient = getCompressionChatClient();
        String summary = chatClient.prompt(compressionRequest).call().content();
        log.info("压缩模型返回摘要长度: {} chars", summary != null ? summary.length() : 0);

        // 5. 格式化摘要
        String formattedSummary = formatSummary(summary);
        int summaryTokens = TokenCountUtils.estimate(formattedSummary);
        log.info("格式化后摘要 token 数量: {}", summaryTokens);

        // 6. 获取压缩阈值
        int threshold = getCompressionThreshold(compressionConfig);

        // 7. 构建压缩 Prompt（滚动截断策略）
        String compressedText = buildCompressedPromptWithTruncation(
                formattedSummary, messages, originalTokenCount, summaryTokens, threshold);
        int compressedTokens = TokenCountUtils.estimate(compressedText);
        log.info("压缩后 Prompt token 数量: {}, 阈值: {}", compressedTokens, threshold);

        // 8. 存入 DynamicContext 并路由回原节点
        dynamicContext.setCompressedPrompt(new Prompt(compressedText));
        dynamicContext.setCompressionRequired(false);

        log.info("CompressionContextNode 压缩完成，准备路由回节点: {}", dynamicContext.getReturnNode());
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DynamicContext, String> get(ArmoryCommandEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        return this;
    }

    /**
     * 滚动截断：优先保留最新2轮，必要时递减
     */
    private String buildCompressedPromptWithTruncation(String summary, List<ChatMessageEntity> messages,
                                                       int originalTokens, int summaryTokens, int threshold) {
        // 尝试保留 2 轮对话
        String compressedText = buildCompressedPrompt(summary, messages, originalTokens, DEFAULT_KEEP_ROUNDS);
        int compressedTokens = TokenCountUtils.estimate(compressedText);

        if (compressedTokens <= threshold) {
            return compressedText;
        }

        // 压缩后仍超限，递减保留轮数
        log.info("压缩后仍超限，尝试减少保留轮数: currentTokens={}, threshold={}", compressedTokens, threshold);

        // 尝试保留 1 轮
        compressedText = buildCompressedPrompt(summary, messages, originalTokens, 1);
        compressedTokens = TokenCountUtils.estimate(compressedText);
        if (compressedTokens <= threshold) {
            return compressedText;
        }

        // 尝试保留 0 轮（仅摘要）
        log.info("保留1轮仍超限，仅保留摘要");
        return buildCompressedPrompt(summary, null, originalTokens, 0);
    }

    private String buildCompressedPrompt(String summary, List<ChatMessageEntity> messages,
                                         int originalTokens, int keepRounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("[压缩边界] 以下是之前对话的摘要（原始约 ").append(originalTokens).append(" tokens）：\n\n");
        sb.append(summary);

        // 添加最近 N 轮对话
        if (messages != null && !messages.isEmpty() && keepRounds > 0) {
            sb.append("\n\n[最近对话]\n");
            List<ChatMessageEntity> recentMessages = getRecentRounds(messages, keepRounds);
            for (ChatMessageEntity msg : recentMessages) {
                sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n\n");
            }
        }

        sb.append("[压缩边界结束]");
        return sb.toString();
    }

    /**
     * 获取最近 N 轮对话（1轮=user+assistant=2条消息）
     * 防御性处理：消息不足时返回全部消息，不抛异常
     */
    protected List<ChatMessageEntity> getRecentRounds(List<ChatMessageEntity> messages, int keepRounds) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int totalSize = messages.size();
        int targetSize = keepRounds * 2;
        // 消息不足时，返回全部消息
        if (totalSize <= targetSize) {
            return messages;
        }
        int startIndex = totalSize - targetSize;
        return messages.subList(startIndex, totalSize);
    }

    private String getCompressionPromptTemplate(DynamicContext dynamicContext) {
        Map<String, AiClientSystemPromptVO> systemPromptMap = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName());
        AiClientSystemPromptVO compressionPrompt = systemPromptMap != null ? systemPromptMap.get("7001") : null;
        return compressionPrompt != null ? compressionPrompt.getPromptContent()
                : getDefaultCompressionPromptTemplate();
    }

    private String getDefaultCompressionPromptTemplate() {
        return "你是上下文压缩专家。你的任务是将对话历史压缩成简洁的摘要。\n\n" +
                "压缩规则：\n" +
                "- 保留关键决策和结论\n" +
                "- 保留所有代码修改（带文件名）\n" +
                "- 保留所有错误和解决方案\n" +
                "- 压缩重复操作和调试过程\n" +
                "- 保留待处理任务和下一步计划\n\n" +
                "请严格按以下格式输出（仅返回文本，不要调用任何工具）：\n" +
                "<分析>[简要分析哪些内容是重要的]</分析>\n" +
                "<摘要>\n" +
                "1. 主要请求和意图：[一句话概括用户目标]\n" +
                "2. 关键技术概念：[涉及的技术栈、框架、工具]\n" +
                "3. 文件和代码片段：[完整保留重要代码，格式：文件名:行号 代码内容]\n" +
                "4. 错误和修复：[遇到的问题及解决方案]\n" +
                "5. 问题解决过程：[关键步骤和思路转变]\n" +
                "6. 所有用户消息：[压缩后的用户请求列表]\n" +
                "7. 待处理任务：[还未完成的工作]\n" +
                "8. 当前工作：[当前正在进行的任务]\n" +
                "9. 下一步计划：[推荐的后续行动]\n" +
                "</摘要>";
    }

    private String buildCompressionRequest(String template, List<ChatMessageEntity> messages, int maxTokens) {
        StringBuilder sb = new StringBuilder();
        sb.append(template).append("\n\n");
        sb.append("[待压缩对话内容]\n");
        if (messages != null) {
            for (ChatMessageEntity msg : messages) {
                if (msg != null && msg.getContent() != null) {
                    sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n\n");
                }
            }
        }
        sb.append("[/待压缩对话内容]\n\n");
        sb.append("请生成不超过 ").append(maxTokens).append(" tokens 的摘要。");
        return sb.toString();
    }

    /**
     * 格式化摘要，提取 <摘要> 标签内容
     */
    protected String formatSummary(String rawSummary) {
        if (rawSummary == null || rawSummary.isEmpty()) {
            return "";
        }
        String formatted = rawSummary.replaceAll("(?i)<分析>[\\s\\S]*?</分析>", "");
        Pattern pattern = Pattern.compile("(?i)<摘要>([\\s\\S]*?)</摘要>");
        java.util.regex.Matcher matcher = pattern.matcher(formatted);
        if (matcher.find()) {
            formatted = matcher.group(1).trim();
        }
        // 清理多余空行
        return formatted.replaceAll("\n{3,}", "\n\n").trim();
    }

    private int getCompressionThreshold(AiAgentClientFlowConfigVO config) {
        if (config != null && config.getMaxSummaryTokens() > 0) {
            return config.getMaxSummaryTokens();
        }
        return DEFAULT_COMPRESSION_THRESHOLD;
    }

    private int getMaxSummaryTokens(AiAgentClientFlowConfigVO config) {
        if (config != null && config.getMaxSummaryTokens() > 0) {
            return config.getMaxSummaryTokens();
        }
        return DEFAULT_MAX_SUMMARY_TOKENS;
    }

    private String extractPromptText(Prompt prompt) {
        if (prompt == null) {
            return "";
        }
        return prompt.toString();
    }

    private ChatClient getCompressionChatClient() {
        // 使用压缩助手配置的 clientId 获取 ChatClient
        String compressionClientId = AiClientTypeEnumVO.COMPRESSION_ASSISTANT.getCode();
        String beanName = "aiClient" + compressionClientId + "taskType" + 1;
        return (ChatClient) getBean(beanName);
    }
}
