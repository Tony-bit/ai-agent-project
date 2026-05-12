package denny.ai.agent.domain.service.auto.step.routing;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import denny.ai.agent.domain.model.entity.IntentFewshotSample;
import denny.ai.agent.domain.model.valobj.BaseSlot;
import denny.ai.agent.domain.model.valobj.IntentRoutingResult;
import denny.ai.agent.domain.model.valobj.StockSlot;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import denny.ai.agent.domain.service.intent.IntentFewshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 意图识别服务
 *
 * @author denny
 * 2026/5/10
 */
@Slf4j
@Service
public class IntentRoutingService {

    @Resource
    private ChatClient chatClient;

    @javax.annotation.Resource
    private IntentFewshotService intentFewshotService;

    /**
     * 调用 LLM 进行意图识别（含动态 Few-Shot + 切槽）
     *
     * @param userMessage     当前用户消息
     * @param historyMessages 历史消息列表
     * @return 意图识别结果（含 slots）
     */
    public IntentRoutingResult route(String userMessage, List<String> historyMessages) {
        List<IntentFewshotSample> fewshotSamples = retrieveFewshotSamples(userMessage);
        String prompt = IntentRoutingPrompt.buildPrompt(userMessage, historyMessages, fewshotSamples);
        return doRoute(userMessage, prompt);
    }

    /**
     * 调用 LLM 进行意图识别（旧签名，保持向后兼容）
     *
     * @param userMessage 当前用户消息
     * @param prompt      已构建好的 Prompt
     * @return 意图识别结果
     */
    public IntentRoutingResult route(String userMessage, String prompt) {
        return doRoute(userMessage, prompt);
    }

    private IntentRoutingResult doRoute(String userMessage, String prompt) {
        try {
            String response = chatClient.prompt(prompt).call().content();
            log.debug("意图识别 LLM 原始响应: userMessage={}, response={}", userMessage, response);
            return parseResponse(response);
        } catch (Exception e) {
            log.error("意图识别调用失败，降级为 UNKNOWN: userMessage={}, error={}",
                    userMessage, e.getMessage());
            return fallbackResult("LLM调用异常: " + e.getMessage());
        }
    }

    private List<IntentFewshotSample> retrieveFewshotSamples(String userMessage) {
        try {
            return intentFewshotService.retrieveTopK(userMessage, 5);
        } catch (Exception e) {
            log.warn("Few-Shot 检索失败，降级为空样本: userMessage={}, error={}",
                    userMessage, e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析 LLM 返回的 JSON 响应（支持切槽字段）
     * 解析失败时降级为 UNKNOWN + LOW
     */
    @SuppressWarnings("unchecked")
    public IntentRoutingResult parseResponse(String response) {
        if (response == null || response.isBlank()) {
            log.warn("意图识别 LLM 返回为空，降级为 UNKNOWN + LOW");
            return fallbackResult("LLM返回为空");
        }

        try {
            String jsonStr = extractJson(response);
            JSONObject json = JSON.parseObject(jsonStr);

            String intentCode = json.getString("intent");
            String confidenceCode = json.getString("confidence");
            String reasoning = json.getString("reasoning");
            if (reasoning == null) {
                reasoning = "无推理过程";
            }

            IntentTypeEnum intent = IntentTypeEnum.fromCode(intentCode);
            ConfidenceEnum confidence = ConfidenceEnum.fromCode(confidenceCode);

            if (intent == null || intent == IntentTypeEnum.UNKNOWN) {
                log.warn("意图识别结果无效，降级为 UNKNOWN: response={}", response);
                return fallbackResult("intent字段无效");
            }

            BaseSlot baseSlot = null;
            if (json.containsKey("baseSlot") && json.getJSONObject("baseSlot") != null) {
                baseSlot = json.getObject("baseSlot", BaseSlot.class);
            }

            Map<String, Object> intentSpecificSlots = null;
            if (json.containsKey("intentSpecificSlots") && json.getJSONObject("intentSpecificSlots") != null) {
                intentSpecificSlots = json.getJSONObject("intentSpecificSlots").getInnerMap();
            }

            if (intent == IntentTypeEnum.STOCK_ANALYSIS && intentSpecificSlots != null) {
                intentSpecificSlots = buildStockSlot(intentSpecificSlots);
            }

            return IntentRoutingResult.builder()
                    .intent(intent)
                    .confidence(confidence)
                    .reasoning(reasoning)
                    .baseSlot(baseSlot)
                    .intentSpecificSlots(intentSpecificSlots)
                    .build();
        } catch (Exception e) {
            log.warn("意图识别 JSON 解析失败，降级为 UNKNOWN + LOW: response={}, error={}",
                    response, e.getMessage());
            return fallbackResult("JSON解析失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildStockSlot(Map<String, Object> rawSlots) {
        StockSlot stockSlot = StockSlot.builder()
                .stockCode((String) rawSlots.get("stockCode"))
                .stockQueryType((String) rawSlots.get("stockQueryType"))
                .timeRange((String) rawSlots.get("timeRange"))
                .exchange((String) rawSlots.get("exchange"))
                .build();
        return Map.of("stockSlot", stockSlot);
    }

    private String extractJson(String response) {
        int start = response.indexOf("{");
        int end = response.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    private IntentRoutingResult fallbackResult(String reason) {
        return IntentRoutingResult.builder()
                .intent(IntentTypeEnum.UNKNOWN)
                .confidence(ConfidenceEnum.LOW)
                .reasoning(reason)
                .build();
    }
}
