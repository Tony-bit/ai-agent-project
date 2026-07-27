package denny.ai.agent.trading.domain.validation;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NodeValidationRegistry {

    private final Map<String, NodeValidationAudit> audits = new ConcurrentHashMap<>();
    private final Clock clock;

    public NodeValidationRegistry() {
        this(Clock.systemUTC());
    }

    NodeValidationRegistry(Clock clock) {
        this.clock = clock;
    }

    public void markValid(String nodeName) {
        audits.put(nodeName, new NodeValidationAudit(
                nodeName, NodeValidationAudit.Status.VALID, List.of(), clock.instant()));
    }

    public void markInvalid(String nodeName, List<TradingValidationError> errors) {
        List<ValidationErrorCode> codes = errors.stream()
                .map(TradingValidationError::code).distinct().toList();
        audits.put(nodeName, new NodeValidationAudit(
                nodeName, NodeValidationAudit.Status.INVALID, codes, clock.instant()));
    }

    public void markExecutionFailed(String nodeName) {
        audits.put(nodeName, new NodeValidationAudit(
                nodeName, NodeValidationAudit.Status.EXECUTION_FAILED, List.of(), clock.instant()));
    }

    public boolean isValid(String nodeName) {
        NodeValidationAudit audit = audits.get(nodeName);
        return audit != null && audit.status() == NodeValidationAudit.Status.VALID;
    }

    public NodeValidationAudit statusOrMissing(String nodeName) {
        NodeValidationAudit audit = audits.get(nodeName);
        return audit != null ? audit : new NodeValidationAudit(
                nodeName, NodeValidationAudit.Status.MISSING, List.of(), clock.instant());
    }

    public Map<String, NodeValidationAudit> snapshot() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(audits));
    }
}
