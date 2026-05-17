package denny.ai.agent.trading.domain.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import denny.ai.agent.trading.api.provider.IStockDataProvider;
import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import denny.ai.agent.trading.api.vo.ConfidenceEnum;
import denny.ai.agent.trading.api.vo.IntentEnumVO;
import denny.ai.agent.trading.api.vo.StockSearchResultVO;
import denny.ai.agent.trading.domain.prompt.IntentRoutingPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 交易模块意图识别服务。
 * <p>
 * 调用 LLM 对用户消息进行意图分类，支持股票分析意图识别和置信度判断。
 */
@Slf4j
@Service("tradingIntentRoutingService")
public class TradingIntentRoutingService {

    @Resource
    private ApplicationContext applicationContext;

    /**
     * 解析 LLM 响应为意图路由结果。
     */
    public IntentRoutingResult parseResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            log.warn("意图识别 LLM 响应为空，跳过解析");
            return IntentRoutingResult.builder()
                    .intent(IntentEnumVO.UNKNOWN)
                    .confidence(ConfidenceEnum.LOW)
                    .reasoning("LLM 响应为空，降级为未知意图")
                    .build();
        }

        try {
            String jsonStr = extractJson(response);
            if (jsonStr == null || jsonStr.trim().isEmpty()) {
                log.warn("无法从 LLM 响应中提取 JSON: {}", response);
                return IntentRoutingResult.builder()
                        .intent(IntentEnumVO.UNKNOWN)
                        .confidence(ConfidenceEnum.LOW)
                        .reasoning("无法解析 LLM 响应，降级为未知意图")
                        .build();
            }

            JSONObject json = JSON.parseObject(jsonStr);
            if (json == null) {
                log.warn("JSON 解析结果为空，原始响应: {}", response);
                return IntentRoutingResult.builder()
                        .intent(IntentEnumVO.UNKNOWN)
                        .confidence(ConfidenceEnum.LOW)
                        .reasoning("JSON 解析失败，降级为未知意图")
                        .build();
            }

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
     * 根据公司名称搜索股票代码。
     *
     * @param name 公司名称
     * @return 股票代码，如果搜索失败返回 null
     */
    public String searchTickerByName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }

        try {
            // 方式1：尝试通过 IStockDataProvider.searchByName
            Map<String, IStockDataProvider> providers = applicationContext.getBeansOfType(IStockDataProvider.class);
            for (IStockDataProvider provider : providers.values()) {
                List<StockSearchResultVO> results = provider.searchByName(name.trim());
                if (results != null && !results.isEmpty()) {
                    log.info("通过 IStockDataProvider 找到股票: {} -> {}", name, results.get(0).getTicker());
                    return results.get(0).getTicker();
                }
            }

            // 方式2：尝试通过 search_stock_by_name ToolCallback
            ToolCallback searchCallback = findSearchStockByNameCallback();
            if (searchCallback != null) {
                String input = String.format("{\"name\":\"%s\"}", name.trim());
                String result = searchCallback.call(input);
                log.debug("search_stock_by_name 返回: {}", result);
                return extractTickerFromSearchResult(result);
            }

            log.warn("未找到可用的股票搜索方式: {}", name);
            return null;
        } catch (Exception e) {
            log.error("搜索股票代码失败: name={}, error={}", name, e.getMessage());
            return null;
        }
    }

    /**
     * 查找 search_stock_by_name ToolCallback。
     */
    private ToolCallback findSearchStockByNameCallback() {
        try {
            Map<String, ToolCallback> callbacks = applicationContext.getBeansOfType(ToolCallback.class);
            for (ToolCallback callback : callbacks.values()) {
                if ("search_stock_by_name".equals(callback.getToolDefinition().name())) {
                    return callback;
                }
            }
        } catch (Exception e) {
            log.debug("查找 ToolCallback 失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从搜索结果中提取股票代码。
     */
    private String extractTickerFromSearchResult(String result) {
        if (result == null || result.isEmpty() || result.contains("未找到") || result.contains("工具执行失败")) {
            return null;
        }
        try {
            if (result.contains("[")) {
                List<StockSearchResultVO> results = JSON.parseArray(result, StockSearchResultVO.class);
                if (results != null && !results.isEmpty()) {
                    return results.get(0).getTicker();
                }
            }
            Pattern pattern = Pattern.compile("\\d{6}");
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                return matcher.group();
            }
        } catch (Exception e) {
            log.warn("解析搜索结果失败: {}", e.getMessage());
        }
        return null;
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
