package denny.ai.agent.trading.domain.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.ConfidenceEnum;
import denny.ai.agent.trading.api.vo.IntentEnumVO;
import denny.ai.agent.trading.api.vo.StockAnalysisRequestVO;
import denny.ai.agent.trading.domain.prompt.IntentRoutingPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 意图识别服务。
 * <p>
 * 调用 LLM 对用户消息进行意图分类，支持股票分析意图识别和置信度判断。
 */
@Slf4j
@Service
public class IntentRoutingService {

    private final ChatClient chatClient;

    public IntentRoutingService(ChatClient defaultChatClient) {
        this.chatClient = defaultChatClient;
    }

    /**
     * 执行意图路由，识别用户消息的意图类型。
     *
     * @param userMessage 用户消息
     * @return 意图路由结果
     */
    public IntentRoutingResult route(String userMessage) {
        log.info("开始意图识别，用户消息: {}", userMessage);

        String response = chatClient.prompt()
                .system(IntentRoutingPrompt.SYSTEM_PROMPT)
                .user(userMessage)
                .call()
                .content();

        log.debug("意图识别 LLM 原始响应: {}", response);

        return parseResponse(response);
    }

    /**
     * 解析 LLM 响应为意图路由结果。
     */
    private IntentRoutingResult parseResponse(String response) {
        try {
            String jsonStr = extractJson(response);
            JSONObject json = JSON.parseObject(jsonStr);

            IntentEnumVO intent = IntentEnumVO.valueOf(json.getString("intent"));
            ConfidenceEnum confidence = ConfidenceEnum.valueOf(json.getString("confidence"));
            String ticker = json.getString("ticker");
            String analysisTypeStr = json.getString("analysisType");
            String reasoning = json.getString("reasoning");

            List<AnalystTypeEnum> selectedAnalysts = parseAnalysisType(analysisTypeStr);

            return IntentRoutingResult.builder()
                    .intent(intent)
                    .confidence(confidence)
                    .ticker(ticker)
                    .analysisType(analysisTypeStr)
                    .selectedAnalysts(selectedAnalysts)
                    .reasoning(reasoning)
                    .build();
        } catch (Exception e) {
            log.error("解析意图识别响应失败: {}, 降级为 UNKNOWN", response, e);
            return IntentRoutingResult.builder()
                    .intent(IntentEnumVO.UNKNOWN)
                    .confidence(ConfidenceEnum.LOW)
                    .reasoning("解析失败，降级为未知意图")
                    .build();
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 字符串。
     */
    private String extractJson(String response) {
        String trimmed = response.trim();
        int jsonStart = trimmed.indexOf("{");
        int jsonEnd = trimmed.lastIndexOf("}");
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return trimmed.substring(jsonStart, jsonEnd + 1);
        }
        return trimmed;
    }

    /**
     * 解析分析类型字符串为分析师枚举列表。
     */
    private List<AnalystTypeEnum> parseAnalysisType(String analysisType) {
        if (analysisType == null || "null".equalsIgnoreCase(analysisType)) {
            return null;
        }
        return switch (analysisType.toUpperCase()) {
            case "ALL" -> null;
            case "FUNDAMENTAL" -> List.of(AnalystTypeEnum.FUNDAMENTAL);
            case "TECHNICAL" -> List.of(AnalystTypeEnum.TECHNICAL);
            case "SENTIMENT" -> List.of(AnalystTypeEnum.SENTIMENT);
            case "NEWS" -> List.of(AnalystTypeEnum.NEWS);
            default -> {
                List<AnalystTypeEnum> analysts = Arrays.stream(analysisType.split(","))
                        .map(String::trim)
                        .map(s -> {
                            try {
                                return AnalystTypeEnum.valueOf(s.toUpperCase());
                            } catch (IllegalArgumentException e) {
                                return null;
                            }
                        })
                        .filter(a -> a != null)
                        .collect(Collectors.toList());
                yield analysts.isEmpty() ? null : analysts;
            }
        };
    }

    /**
     * 解析股票分析请求参数。
     *
     * @param userMessage 用户消息
     * @param intent     识别的意图
     * @return 股票分析请求VO，若无法解析则返回 null
     */
    public StockAnalysisRequestVO parseStockRequest(String userMessage, IntentEnumVO intent) {
        if (intent != IntentEnumVO.STOCK_ANALYSIS) {
            return null;
        }

        IntentRoutingResult result = route(userMessage);

        if (result.getTicker() == null || result.getTicker().equalsIgnoreCase("null")) {
            log.warn("未识别到股票代码，用户消息: {}", userMessage);
            return null;
        }

        return StockAnalysisRequestVO.builder()
                .ticker(result.getTicker().toUpperCase())
                .selectedAnalysts(result.getSelectedAnalysts())
                .maxDebateRounds(2)
                .build();
    }

    /**
     * 生成确认问题（用于中置信度场景）。
     *
     * @param ticker       识别的股票代码
     * @param analysisType 分析类型
     * @return 确认问题文本
     */
    public String generateConfirmationQuestion(String ticker, String analysisType) {
        String prompt = IntentRoutingPrompt.CONFIRMATION_PROMPT
                .replace("{ticker}", ticker)
                .replace("{analysisType}", analysisType);

        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    /**
     * 意图路由结果内部类。
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class IntentRoutingResult {
        /**
         * 识别的意图类型
         */
        private IntentEnumVO intent;

        /**
         * 置信度
         */
        private ConfidenceEnum confidence;

        /**
         * 股票代码
         */
        private String ticker;

        /**
         * 分析类型原始字符串
         */
        private String analysisType;

        /**
         * 选定的分析师类型列表
         */
        private List<AnalystTypeEnum> selectedAnalysts;

        /**
         * 推理说明
         */
        private String reasoning;
    }
}
