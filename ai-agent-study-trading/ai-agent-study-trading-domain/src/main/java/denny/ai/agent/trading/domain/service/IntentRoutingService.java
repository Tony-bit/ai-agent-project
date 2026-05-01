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

    /**
     * 解析 LLM 响应为意图路由结果。
     */
    public IntentRoutingResult parseResponse(String response) {
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
            String json = trimmed.substring(jsonStart, jsonEnd + 1);
            json = json.replace('\u201C', '\u201D');
            return json;
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
