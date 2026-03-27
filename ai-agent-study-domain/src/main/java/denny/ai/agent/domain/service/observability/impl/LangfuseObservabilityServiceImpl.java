package denny.ai.agent.domain.service.observability.impl;

import com.alibaba.fastjson2.JSON;
import denny.ai.agent.domain.service.observability.LangfuseProperties;
import denny.ai.agent.domain.service.observability.ObservabilityService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class LangfuseObservabilityServiceImpl implements ObservabilityService {

    private final LangfuseProperties properties;

    private final RestTemplate langfuseRestTemplate;

    public LangfuseObservabilityServiceImpl(LangfuseProperties properties) {
        this.properties = properties;

        Duration timeout = Duration.ofMillis(properties.getTimeoutMs());
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.langfuseRestTemplate = new RestTemplate(requestFactory);
    }

    @Override
    public String startTrace(String sessionId, String input, Map<String, Object> metadata) {
        String traceId = UUID.randomUUID().toString();
        if (!isEnabled()) return traceId;

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", traceId);
        payload.put("timestamp", Instant.now().toString());
        payload.put("sessionId", sessionId);
        payload.put("input", input);
        payload.put("metadata", metadata);

        sendEvent("trace-create", payload);
        return traceId;
    }

    @Override
    public String startSpan(String traceId, String spanName, Map<String, Object> metadata) {
        String spanId = UUID.randomUUID().toString();
        if (!isEnabled()) return spanId;

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", spanId);
        payload.put("traceId", traceId);
        payload.put("name", spanName);
        payload.put("startTime", Instant.now().toString());
        payload.put("metadata", metadata);

        sendEvent("span-create", payload);
        return spanId;
    }

    @Override
    public void logGeneration(String traceId, String spanId, String model, String prompt, String output, Map<String, Object> metadata, Map<String, Object> tokenUsage) {
        if (!isEnabled()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", UUID.randomUUID().toString());
        payload.put("traceId", traceId);
        payload.put("parentObservationId", spanId);
        payload.put("name", "llm-generation");
        payload.put("model", model);
        payload.put("startTime", Instant.now().toString());
        payload.put("endTime", Instant.now().toString());
        payload.put("input", prompt);
        payload.put("output", output);
        payload.put("metadata", metadata);
        if (tokenUsage != null && !tokenUsage.isEmpty()) {
            payload.put("usage", tokenUsage);
        }

        sendEvent("generation-create", payload);
    }

    @Override
    public void logScore(String traceId, String scoreName, Double value, String comment, Map<String, Object> metadata) {
        if (!isEnabled()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", UUID.randomUUID().toString());
        payload.put("traceId", traceId);
        payload.put("name", scoreName);
        payload.put("value", value);
        payload.put("comment", comment);
        payload.put("timestamp", Instant.now().toString());
        payload.put("metadata", metadata);

        sendEvent("score-create", payload);
    }

    @Override
    public void endSpan(String spanId, boolean success, String errorMessage) {
        if (!isEnabled()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", spanId);
        payload.put("endTime", Instant.now().toString());
        payload.put("level", success ? "DEFAULT" : "ERROR");
        if (!success && StringUtils.isNotBlank(errorMessage)) {
            payload.put("statusMessage", errorMessage);
        }

        sendEvent("span-update", payload);
    }

    @Override
    public void endTrace(String traceId, String output, Map<String, Object> metadata) {
        if (!isEnabled()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", traceId);
        payload.put("timestamp", Instant.now().toString());
        payload.put("output", output);
        payload.put("metadata", metadata);

        // Langfuse ingestion 对 trace 使用 trace-create(upsert) 更稳定，避免 trace-update 不生效。
        sendEvent("trace-create", payload);
    }

    private void sendEvent(String type, Map<String, Object> body) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("id", UUID.randomUUID().toString());
            event.put("type", type);
            event.put("timestamp", Instant.now().toString());
            event.put("body", body);

            Map<String, Object> envelope = new HashMap<>();
            envelope.put("batch", new Object[]{event});

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", buildBasicAuth());
            headers.set("Connection", "close");
            HttpEntity<String> request = new HttpEntity<>(JSON.toJSONString(envelope), headers);
            ResponseEntity<String> response = langfuseRestTemplate.postForEntity(properties.getHost() + "/api/public/ingestion", request, String.class);

            int statusCode = response.getStatusCodeValue();
            if (statusCode < 200 || statusCode >= 300) {
                log.warn("langfuse event send failed, type={}, status={}, body={}", type, statusCode, response.getBody());
            }
        } catch (Exception e) {
            log.warn("langfuse event send exception, type={}, err={}", type, e.getMessage());
        }
    }

    private String buildBasicAuth() {
        String raw = properties.getPublicKey() + ":" + properties.getSecretKey();
        String token = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private boolean isEnabled() {
        return properties.isEnabled()
                && StringUtils.isNotBlank(properties.getHost())
                && StringUtils.isNotBlank(properties.getPublicKey())
                && StringUtils.isNotBlank(properties.getSecretKey());
    }
}
