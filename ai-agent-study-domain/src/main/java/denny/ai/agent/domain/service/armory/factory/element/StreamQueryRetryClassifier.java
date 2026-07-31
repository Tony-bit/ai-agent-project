package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.service.auto.step.ClientDisconnectedException;
import denny.ai.agent.domain.service.armory.stream.FirstStreamChunkTimeoutException;
import denny.ai.agent.domain.service.armory.stream.LlmTimeoutException;
import denny.ai.agent.domain.service.armory.stream.StreamChunkIdleTimeoutException;
import denny.ai.agent.domain.service.armory.stream.StreamTimeoutType;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the Story 1 safety precedence to errors from a complete stream query attempt.
 */
public final class StreamQueryRetryClassifier {

    private static final int MAX_CAUSE_DEPTH = 8;
    private static final Set<String> RETRYABLE_HTTP_STATUSES = Set.of(
            AiErrorCodes.HTTP_429,
            AiErrorCodes.HTTP_500,
            AiErrorCodes.HTTP_502,
            AiErrorCodes.HTTP_503,
            AiErrorCodes.HTTP_504);
    private static final Pattern PROVIDER_CODE_PATTERN = Pattern.compile(
            "\\\"code\\\"\\s*:\\s*\\\"?([^\\\",}\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> PROVIDER_CODE_NORMALIZATION = providerCodeNormalization();
    private static final Set<String> TRANSPORT_MESSAGE_MARKERS = Set.of(
            "connection reset", "connection refused", "connection aborted",
            "connection closed", "closed prematurely", "premature close",
            "broken pipe", "econnreset", "epipe", "unexpected end of file");

    private final Set<String> nonRetryableCodes;

    public StreamQueryRetryClassifier(RetryConfig retryConfig) {
        this.nonRetryableCodes = normalizedSet(retryConfig == null
                ? null : retryConfig.getNonRetryableErrorCodes());
    }

    public boolean isRetryable(Throwable error) {
        return isOrdinaryRetryable(error);
    }

    Optional<StreamTimeoutType> streamTimeoutType(Throwable error) {
        for (Throwable cause : causes(error)) {
            if (cause instanceof FirstStreamChunkTimeoutException) {
                return Optional.of(StreamTimeoutType.FIRST_CHUNK);
            }
            if (cause instanceof StreamChunkIdleTimeoutException) {
                return Optional.of(StreamTimeoutType.CHUNK_IDLE);
            }
        }
        return Optional.empty();
    }

    boolean isSafetyExcluded(Throwable error) {
        List<Throwable> causes = causes(error);
        if (causes.isEmpty()) {
            return true;
        }
        if (causes.stream().anyMatch(this::isSafetyExcludedCause)) {
            return true;
        }
        Set<String> statuses = structuredHttpStatuses(causes);
        return statuses.contains(AiErrorCodes.HTTP_401)
                || statuses.contains(AiErrorCodes.HTTP_403);
    }

    boolean matchesNonRetryableCode(Throwable error) {
        List<Throwable> causes = causes(error);
        if (causes.isEmpty() || nonRetryableCodes.isEmpty()) {
            return false;
        }
        Set<String> observedCodes = observedCodes(causes);
        return observedCodes.stream().anyMatch(nonRetryableCodes::contains);
    }

    boolean isDefiniteNonRetryable4xx(Throwable error) {
        return structuredHttpStatuses(causes(error)).stream()
                .anyMatch(status -> is4xx(status)
                        && !AiErrorCodes.HTTP_429.equals(status));
    }

    boolean isOrdinaryRetryable(Throwable error) {
        List<Throwable> causes = causes(error);
        if (causes.isEmpty()
                || causes.stream().anyMatch(LlmTimeoutException.class::isInstance)
                || isSafetyExcluded(error)
                || matchesNonRetryableCode(error)) {
            return false;
        }

        Set<String> providerCodes = providerCodes(causes);
        Set<String> normalizedProviderCodes = new HashSet<>();
        for (String providerCode : providerCodes) {
            normalizedProviderCodes.add(normalizeProviderCode(providerCode));
        }
        Set<String> httpStatuses = structuredHttpStatuses(causes);

        if (!httpStatuses.isEmpty()) {
            if (httpStatuses.stream().allMatch(this::isSuccessfulStatus)) {
                return causes.stream().anyMatch(this::isTransportFailure);
            }
            return httpStatuses.stream().allMatch(RETRYABLE_HTTP_STATUSES::contains);
        }
        if (normalizedProviderCodes.stream().anyMatch(RETRYABLE_HTTP_STATUSES::contains)) {
            return true;
        }
        return causes.stream().anyMatch(this::isTransportFailure);
    }

    private Set<String> observedCodes(List<Throwable> causes) {
        Set<String> providerCodes = providerCodes(causes);
        Set<String> observedCodes = new HashSet<>(providerCodes);
        for (String providerCode : providerCodes) {
            observedCodes.add(normalizeProviderCode(providerCode));
        }
        observedCodes.addAll(structuredHttpStatuses(causes));
        return observedCodes;
    }

    private boolean is4xx(String status) {
        try {
            int value = Integer.parseInt(status);
            return value >= 400 && value < 500;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean isSuccessfulStatus(String status) {
        try {
            int value = Integer.parseInt(status);
            return value >= 200 && value < 300;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean isSafetyExcludedCause(Throwable error) {
        if ((error instanceof LlmTimeoutException
                && !(error instanceof FirstStreamChunkTimeoutException)
                && !(error instanceof StreamChunkIdleTimeoutException))
                || error instanceof TimeoutException
                || error instanceof SocketTimeoutException
                || error instanceof HttpTimeoutException
                || error instanceof CancellationException
                || error instanceof ClientDisconnectedException
                || error instanceof ToolExecutionException
                || error instanceof ResponseValidationException) {
            return true;
        }
        String className = error.getClass().getName().toLowerCase(Locale.ROOT);
        return className.contains("decodingexception")
                || className.contains("decodeexception")
                || className.contains("jsonprocessingexception")
                || className.contains("codecexception")
                || className.contains("cancelexception");
    }

    private boolean isTransportFailure(Throwable error) {
        if (error instanceof ConnectException
                || error instanceof NoRouteToHostException
                || error instanceof UnknownHostException
                || error instanceof EOFException
                || error instanceof SocketException
                || error instanceof IOException) {
            return true;
        }
        String className = error.getClass().getName().toLowerCase(Locale.ROOT);
        if (className.contains("prematurecloseexception")
                || className.contains("abortedexception")) {
            return true;
        }
        String message = lower(error.getMessage());
        return TRANSPORT_MESSAGE_MARKERS.stream().anyMatch(message::contains)
                || (message.contains("response header") && message.contains("failed"))
                || (message.contains("response body")
                        && (message.contains("failed") || message.contains("closed")));
    }

    private Set<String> structuredHttpStatuses(List<Throwable> causes) {
        Set<String> statuses = new HashSet<>();
        for (Throwable cause : causes) {
            if (cause instanceof WebClientResponseException responseException) {
                statuses.add(Integer.toString(responseException.getStatusCode().value()));
            }
        }
        return statuses;
    }

    private Set<String> providerCodes(List<Throwable> causes) {
        Set<String> codes = new HashSet<>();
        for (Throwable cause : causes) {
            collectProviderCodes(cause.getMessage(), codes);
            if (cause instanceof WebClientResponseException responseException) {
                collectProviderCodes(responseException.getResponseBodyAsString(), codes);
            }
        }
        return codes;
    }

    private void collectProviderCodes(String value, Set<String> codes) {
        Matcher matcher = PROVIDER_CODE_PATTERN.matcher(value == null ? "" : value);
        while (matcher.find()) {
            codes.add(lower(matcher.group(1)));
        }
    }

    private List<Throwable> causes(Throwable error) {
        java.util.ArrayList<Throwable> causes = new java.util.ArrayList<>();
        Throwable current = error;
        while (current != null && causes.size() < MAX_CAUSE_DEPTH && !causes.contains(current)) {
            causes.add(current);
            current = current.getCause();
        }
        return List.copyOf(causes);
    }

    private static Set<String> normalizedSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(lower(value));
            }
        }
        return Set.copyOf(normalized);
    }

    private static String normalizeProviderCode(String code) {
        return PROVIDER_CODE_NORMALIZATION.getOrDefault(lower(code), lower(code));
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> providerCodeNormalization() {
        Map<String, String> codes = new HashMap<>();
        codes.put("rate_limit", AiErrorCodes.HTTP_429);
        codes.put("rate_limit_error", AiErrorCodes.HTTP_429);
        codes.put("rate_limit_exceeded", AiErrorCodes.HTTP_429);
        codes.put("server_error", AiErrorCodes.HTTP_500);
        codes.put("internal_error", AiErrorCodes.HTTP_500);
        codes.put("bad_gateway", AiErrorCodes.HTTP_502);
        codes.put("service_unavailable", AiErrorCodes.HTTP_503);
        codes.put("gateway_timeout", AiErrorCodes.HTTP_504);
        return Map.copyOf(codes);
    }
}
