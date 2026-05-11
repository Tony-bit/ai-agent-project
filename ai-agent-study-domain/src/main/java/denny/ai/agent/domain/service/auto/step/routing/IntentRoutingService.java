package denny.ai.agent.domain.service.auto.step.routing;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import denny.ai.agent.domain.model.valobj.enums.ConfidenceEnum;
import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

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

    /**
     * 调用 LLM 进行意图识别
     *
     * @param userMessage 当前用户消息
     * @param prompt      已构建好的 Prompt
     * @return 意图识别结果
     */
    public IntentRoutingResult route(String userMessage, String prompt) {
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

    /**
     * 解析 LLM 返回的 JSON 响应
     * 解析失败时降级为 UNKNOWN + LOW
     */
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

            return IntentRoutingResult.builder()
                    .intent(intent)
                    .confidence(confidence)
                    .reasoning(reasoning)
                    .build();
        } catch (Exception e) {
            log.warn("意图识别 JSON 解析失败，降级为 UNKNOWN + LOW: response={}, error={}",
                    response, e.getMessage());
            return fallbackResult("JSON解析失败: " + e.getMessage());
        }
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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntentRoutingResult {
        private IntentTypeEnum intent;
        private ConfidenceEnum confidence;
        private String reasoning;
    }
}
