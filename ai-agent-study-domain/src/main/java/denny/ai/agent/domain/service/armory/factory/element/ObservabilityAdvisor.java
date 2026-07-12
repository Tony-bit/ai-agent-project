package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.service.observability.ObservabilityService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用可观测 Advisor：统一记录输入输出、耗时、异常信息到 Langfuse。
 */
@Slf4j
public class ObservabilityAdvisor implements BaseAdvisor {

    private static final String TRACE_ID_KEY = "trace_id";
    private static final String SPAN_ID_KEY = "span_id";
    private static final String START_AT_KEY = "observe_start_at";
    private static final String SESSION_ID_KEY = "chat_memory_conversation_id";
    private static final String INPUT_KEY = "input";

    private final ObservabilityService observabilityService;

    public ObservabilityAdvisor(ObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        Map<String, Object> context = new HashMap<>(chatClientRequest.context());

        String input = extractUserText(chatClientRequest);
        String sessionId = doGetSessionId(context);

        String traceId = context.containsKey(TRACE_ID_KEY)
                ? String.valueOf(context.get(TRACE_ID_KEY))
                : "";

        if (StringUtils.isBlank(traceId)) {
            Map<String, Object> traceMetadata = new HashMap<>();
            traceMetadata.put("advisor", getName());
            traceMetadata.put("sessionId", sessionId);
            traceId = observabilityService.startTrace(sessionId, input, traceMetadata);
            context.put(TRACE_ID_KEY, traceId);
        }

        Map<String, Object> spanMetadata = new HashMap<>();
        spanMetadata.put("advisor", getName());
        spanMetadata.put("sessionId", sessionId);
        String spanId = observabilityService.startSpan(traceId, "chat_client_call", spanMetadata);

        context.put(INPUT_KEY, input);
        context.put(SPAN_ID_KEY, spanId);
        context.put(START_AT_KEY, System.currentTimeMillis());
        context.put("input", input);

        return ChatClientRequest.builder()
                .prompt(chatClientRequest.prompt())
                .context(context)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        Map<String, Object> context = new HashMap<>(chatClientResponse.context());

        String traceId = asString(context.get(TRACE_ID_KEY));
        String spanId = asString(context.get(SPAN_ID_KEY));
        long startAt = parseStartAt(context.get(START_AT_KEY));
        long latencyMs = startAt > 0 ? System.currentTimeMillis() - startAt : -1;

        String sessionId = doGetSessionId(context);
        String input = extractPromptText(chatClientResponse, context);
        String output = extractOutputText(chatClientResponse);
        String model = extractModelName(chatClientResponse);

        if (log.isDebugEnabled()) {
            log.debug("ObservabilityAdvisor after - traceId={}, spanId={}, inputLen={}, outputLen={}, latencyMs={}, model={}",
                    traceId, spanId,
                    input != null ? input.length() : -1,
                    output != null ? output.length() : -1,
                    latencyMs, model);
        }

        String userId = asString(context.get("user_id"));
        String agentId = asString(context.get("agent_id"));
        String clientId = asString(context.get("client_id"));

        Map<String, Object> generationMetadata = new HashMap<>();
        generationMetadata.put("advisor", getName());
        generationMetadata.put("latencyMs", latencyMs);

        String retrievedDocuments = asString(context.get("qa_retrieved_documents"));
        generationMetadata.put("ragRetrievedChars", retrievedDocuments.length());
        generationMetadata.put("ragRetrievedHitCount", countRetrievedHits(retrievedDocuments));

        if (StringUtils.isNotBlank(traceId) && StringUtils.isNotBlank(spanId)) {
            Map<String, Object> tokenUsage = extractTokenUsage(chatClientResponse);
            observabilityService.logGeneration(traceId, spanId, "chat-client", input, output, generationMetadata, tokenUsage);
            observabilityService.endSpan(spanId, true, null);
            observabilityService.endTrace(traceId, output, buildTraceMetadata(context, latencyMs, model));
        }

        ChatResponse.Builder chatResponseBuilder = ChatResponse.builder().from(chatClientResponse.chatResponse());
        chatResponseBuilder.metadata(TRACE_ID_KEY, traceId);
        chatResponseBuilder.metadata("latency_ms", latencyMs);

        return ChatClientResponse.builder()
                .chatResponse(chatResponseBuilder.build())
                .context(context)
                .build();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientRequest advisedRequest = this.before(chatClientRequest, callAdvisorChain);
        try {
            ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(advisedRequest);
            return this.after(chatClientResponse, callAdvisorChain);
        } catch (Exception e) {
            Map<String, Object> context = advisedRequest.context();
            String spanId = asString(context.get(SPAN_ID_KEY));
            String traceId = asString(context.get(TRACE_ID_KEY));
            if (StringUtils.isNotBlank(spanId)) {
                observabilityService.endSpan(spanId, false, e.getMessage());
            }
            if (StringUtils.isNotBlank(traceId)) {
                observabilityService.endTrace(traceId, "", buildErrorTraceMetadata(context, e));
            }
            throw e;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        ChatClientRequest advisedRequest = this.before(chatClientRequest, streamAdvisorChain);
        StringBuilder aggregatedOutput = new StringBuilder();
        AtomicReference<ChatClientResponse> lastResponseRef = new AtomicReference<>();

        return streamAdvisorChain.nextStream(advisedRequest)
                .doOnNext(chatClientResponse -> {
                    lastResponseRef.set(chatClientResponse);
                    String chunk = extractOutputText(chatClientResponse, false);
                    if (StringUtils.isNotBlank(chunk)) {
                        aggregatedOutput.append(chunk);
                    }
                })
                .doOnError(error -> endObservationOnError(advisedRequest.context(), error))
                .doOnComplete(() -> completeStreamObservation(advisedRequest, lastResponseRef.get(), aggregatedOutput.toString()));
    }

    @Override
    public int getOrder() {
        return 1000;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    private String doGetSessionId(Map<String, Object> context) {
        return context.containsKey(SESSION_ID_KEY) ? String.valueOf(context.get(SESSION_ID_KEY)) : "";
    }

    private String extractUserText(ChatClientRequest request) {
        if (request == null || request.prompt() == null || request.prompt().getUserMessage() == null) {
            return "";
        }
        String text = request.prompt().getUserMessage().getText();
        return text == null ? "" : text;
    }

    private String extractPromptText(ChatClientResponse response, Map<String, Object> context) {
        if (context != null && context.containsKey(INPUT_KEY) && context.get(INPUT_KEY) != null) {
            return String.valueOf(context.get(INPUT_KEY));
        }
        if (response != null && response.chatResponse() != null && response.chatResponse().getMetadata() != null) {
            Object prompt = response.chatResponse().getMetadata().get("prompt");
            if (prompt != null) {
                return String.valueOf(prompt);
            }
        }
        return "";
    }

    private String extractOutputText(ChatClientResponse response) {
        return extractOutputText(response, true);
    }

    private String extractOutputText(ChatClientResponse response, boolean logFailure) {
        if (response == null || response.chatResponse() == null) {
            if (logFailure) {
                log.debug("extractOutputText: response or chatResponse is null");
            }
            return "";
        }

        ChatResponse chatResponse = response.chatResponse();
        String outputText = tryExtractFromResult(chatResponse);

        if (StringUtils.isBlank(outputText)) {
            outputText = tryExtractFromGenerations(chatResponse);
        }

        if (StringUtils.isBlank(outputText)) {
            outputText = tryExtractFromMetadata(chatResponse);
        }

        if (StringUtils.isBlank(outputText)) {
            outputText = tryExtractFromMessageContent(chatResponse);
        }

        if (StringUtils.isBlank(outputText) && logFailure) {
            logOutputExtractionFailure(chatResponse);
        }

        return outputText;
    }

    private String tryExtractFromResult(ChatResponse chatResponse) {
        try {
            if (chatResponse.getResult() != null
                    && chatResponse.getResult().getOutput() != null) {
                String text = chatResponse.getResult().getOutput().getText();
                if (StringUtils.isNotBlank(text)) {
                    return text;
                }
            }
        } catch (Exception e) {
            log.debug("tryExtractFromResult failed: {}", e.getMessage());
        }
        return "";
    }

    private String tryExtractFromMetadata(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null) {
                Object content = chatResponse.getMetadata().get("content");
                if (content != null) {
                    String text = String.valueOf(content).trim();
                    if (StringUtils.isNotBlank(text)) {
                        return text;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("tryExtractFromMetadata failed: {}", e.getMessage());
        }
        return "";
    }

    private String tryExtractFromGenerations(ChatResponse chatResponse) {
        try {
            if (chatResponse.getResults() == null || chatResponse.getResults().isEmpty()) {
                return "";
            }
            for (var generation : chatResponse.getResults()) {
                if (generation != null && generation.getOutput() != null) {
                    String text = generation.getOutput().getText();
                    if (StringUtils.isNotBlank(text)) {
                        return text;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("tryExtractFromGenerations failed: {}", e.getMessage());
        }
        return "";
    }

    private String tryExtractFromMessageContent(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null) {
                Object resultObj = chatResponse.getMetadata().get("result");
                if (resultObj instanceof ChatResponse generated) {
                    if (generated.getResult() != null
                            && generated.getResult().getOutput() != null) {
                        String text = generated.getResult().getOutput().getText();
                        if (StringUtils.isNotBlank(text)) {
                            return text;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("tryExtractFromMessageContent failed: {}", e.getMessage());
        }
        return "";
    }

    private void logOutputExtractionFailure(ChatResponse chatResponse) {
        StringBuilder sb = new StringBuilder();
        sb.append("ObservabilityAdvisor: output text extraction all paths returned empty. ");
        try {
            sb.append("result=").append(chatResponse.getResult()).append(", ");
            List<Generation> gens = chatResponse.getResults();
            sb.append("generations.size=").append(gens != null ? gens.size() : "null").append(", ");
            if (chatResponse.getMetadata() != null) {
                sb.append("metadata.keys=").append(chatResponse.getMetadata().keySet());
            } else {
                sb.append("metadata=null");
            }
        } catch (Exception diagEx) {
            sb.append("diagnostics-error=").append(diagEx.getMessage());
        }
        log.warn(sb.toString());
    }

    private String extractModelName(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null || response.chatResponse().getMetadata() == null) {
            return "";
        }
        String model = response.chatResponse().getMetadata().getModel();
        if (StringUtils.isNotBlank(model)) {
            return model;
        }
        Object fallbackModel = response.chatResponse().getMetadata().get("model");
        return fallbackModel == null ? "" : String.valueOf(fallbackModel);
    }

    private long parseStartAt(Object value) {
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignore) {
            return -1L;
        }
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int countRetrievedHits(String retrievedDocuments) {
        if (!StringUtils.isNotBlank(retrievedDocuments)) {
            return 0;
        }
        return (int) java.util.Arrays.stream(retrievedDocuments.split("\\R+"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .count();
    }

    private Map<String, Object> extractTokenUsage(ChatClientResponse response) {
        Map<String, Object> usage = new HashMap<>();
        if (response == null || response.chatResponse() == null || response.chatResponse().getMetadata() == null) {
            return usage;
        }

        Usage usageMetadata = response.chatResponse().getMetadata().getUsage();
        if (usageMetadata != null) {
            putIfNumberOrNumericString(usageMetadata.getPromptTokens(), usage, "promptTokens");
            putIfNumberOrNumericString(usageMetadata.getCompletionTokens(), usage, "completionTokens");
            putIfNumberOrNumericString(usageMetadata.getTotalTokens(), usage, "totalTokens");
            if (usageMetadata.getNativeUsage() instanceof Map<?, ?> nativeUsageMap) {
                putIfNumberOrNumericString(nativeUsageMap, usage, "input_tokens", "promptTokens");
                putIfNumberOrNumericString(nativeUsageMap, usage, "output_tokens", "completionTokens");
                putIfNumberOrNumericString(nativeUsageMap, usage, "total_tokens", "totalTokens");
            }
        }

        Object promptTokens = response.chatResponse().getMetadata().get("promptTokens");
        Object completionTokens = response.chatResponse().getMetadata().get("completionTokens");
        Object totalTokens = response.chatResponse().getMetadata().get("totalTokens");
        putIfNumberOrNumericString(promptTokens, usage, "promptTokens");
        putIfNumberOrNumericString(completionTokens, usage, "completionTokens");
        putIfNumberOrNumericString(totalTokens, usage, "totalTokens");

        Object usageObj = response.chatResponse().getMetadata().get("usage");
        if (usageObj instanceof Map<?, ?> usageMap) {
            putIfNumberOrNumericString(usageMap, usage, "input_tokens", "promptTokens");
            putIfNumberOrNumericString(usageMap, usage, "output_tokens", "completionTokens");
            putIfNumberOrNumericString(usageMap, usage, "total_tokens", "totalTokens");
        }
        return usage;
    }

    private void putIfNumberOrNumericString(Object value, Map<String, Object> target, String targetKey) {
        if (value == null) {
            return;
        }
        if (value instanceof Number) {
            target.put(targetKey, value);
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.matches("^-?\\d+$")) {
            target.put(targetKey, Long.parseLong(text));
        }
    }

    private void putIfNumberOrNumericString(Map<?, ?> source, Map<String, Object> target, String sourceKey, String targetKey) {
        if (source == null) {
            return;
        }
        putIfNumberOrNumericString(source.get(sourceKey), target, targetKey);
    }

    private void completeStreamObservation(ChatClientRequest advisedRequest,
                                           ChatClientResponse lastResponse,
                                           String aggregatedOutput) {
        Map<String, Object> context = advisedRequest.context();
        String traceId = asString(context.get(TRACE_ID_KEY));
        String spanId = asString(context.get(SPAN_ID_KEY));
        if (StringUtils.isBlank(traceId) || StringUtils.isBlank(spanId)) {
            return;
        }

        long startAt = parseStartAt(context.get(START_AT_KEY));
        long latencyMs = startAt > 0 ? System.currentTimeMillis() - startAt : -1;
        String input = extractPromptText(lastResponse, context);
        String output = StringUtils.isNotBlank(aggregatedOutput)
                ? aggregatedOutput
                : extractOutputText(lastResponse);
        String model = extractModelName(lastResponse);

        if (log.isDebugEnabled()) {
            log.debug("ObservabilityAdvisor stream complete - traceId={}, spanId={}, inputLen={}, outputLen={}, latencyMs={}, model={}",
                    traceId, spanId,
                    input != null ? input.length() : -1,
                    output != null ? output.length() : -1,
                    latencyMs, model);
        }

        observabilityService.logGeneration(
                traceId,
                spanId,
                "chat-client",
                input,
                output,
                buildGenerationMetadata(context, latencyMs),
                extractTokenUsage(lastResponse)
        );
        observabilityService.endSpan(spanId, true, null);
        observabilityService.endTrace(traceId, output, buildTraceMetadata(context, latencyMs, model));
    }

    private void endObservationOnError(Map<String, Object> context, Throwable error) {
        String traceId = asString(context.get(TRACE_ID_KEY));
        String spanId = asString(context.get(SPAN_ID_KEY));
        if (StringUtils.isNotBlank(spanId)) {
            observabilityService.endSpan(spanId, false, error != null ? error.getMessage() : null);
        }
        if (StringUtils.isNotBlank(traceId)) {
            observabilityService.endTrace(traceId, "", buildErrorTraceMetadata(context, error));
        }
    }

    private Map<String, Object> buildGenerationMetadata(Map<String, Object> context, long latencyMs) {
        Map<String, Object> generationMetadata = new HashMap<>();
        generationMetadata.put("advisor", getName());
        generationMetadata.put("latencyMs", latencyMs);

        String retrievedDocuments = asString(context.get("qa_retrieved_documents"));
        generationMetadata.put("ragRetrievedChars", retrievedDocuments.length());
        generationMetadata.put("ragRetrievedHitCount", countRetrievedHits(retrievedDocuments));
        return generationMetadata;
    }

    private Map<String, Object> buildTraceMetadata(Map<String, Object> context, long latencyMs, String model) {
        Map<String, Object> traceMetadata = new HashMap<>();
        traceMetadata.put("advisor", getName());
        traceMetadata.put("sessionId", doGetSessionId(context));
        traceMetadata.put("latencyMs", latencyMs);
        if (StringUtils.isNotBlank(model)) {
            traceMetadata.put("model", model);
        }
        return traceMetadata;
    }

    private Map<String, Object> buildErrorTraceMetadata(Map<String, Object> context, Throwable error) {
        Map<String, Object> traceMetadata = new HashMap<>();
        traceMetadata.put("advisor", getName());
        traceMetadata.put("sessionId", doGetSessionId(context));
        if (error != null && StringUtils.isNotBlank(error.getMessage())) {
            traceMetadata.put("error", error.getMessage());
        }
        return traceMetadata;
    }
}
