package denny.ai.agent.trading.domain.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.trading.api.vo.TargetContext;
import denny.ai.agent.trading.domain.execution.NodeResultEnvelope;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class NodeResultValidator {

    private static final String NUMBER = "([-+]?[0-9]+(?:\\.[0-9]+)?)";

    private final ObjectMapper objectMapper;
    private final Validator beanValidator;

    public NodeResultValidator(ObjectMapper objectMapper, Validator beanValidator) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.beanValidator = Objects.requireNonNull(beanValidator, "beanValidator");
    }

    public NodeValidationResult validate(NodeResultEnvelope<?> envelope, NodeValidationContext context) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(context, "context");
        List<TradingValidationError> errors = new ArrayList<>();
        validateEnvelope(envelope, context, errors);
        validateSchema(envelope.payload(), errors);

        String output;
        JsonNode payloadTree;
        try {
            output = objectMapper.writeValueAsString(envelope.payload());
            payloadTree = objectMapper.valueToTree(envelope.payload());
        } catch (IllegalArgumentException | JsonProcessingException error) {
            errors.add(error(ValidationErrorCode.INVALID_SCHEMA,
                    "Payload cannot be represented as JSON", "payload"));
            return new NodeValidationResult(errors);
        }

        validateTargetEcho(payloadTree.get("targetEcho"), context.targetContext(), errors);
        validateEntities(output, context.allowedEntities(), errors);
        validateNumericFacts(textualClaims(payloadTree), context.numericFacts(), errors);
        return new NodeValidationResult(errors);
    }

    private void validateEnvelope(NodeResultEnvelope<?> envelope,
                                  NodeValidationContext context,
                                  List<TradingValidationError> errors) {
        TargetContext target = context.targetContext();
        if (!target.runId().equals(envelope.runId())) {
            errors.add(error(ValidationErrorCode.ENVELOPE_MISMATCH,
                    "Envelope runId does not match the active run", "runId"));
        }
        if (!target.targetId().equalsIgnoreCase(envelope.targetId())) {
            errors.add(error(ValidationErrorCode.ENVELOPE_MISMATCH,
                    "Envelope targetId does not match the active target", "targetId"));
        }
        if (!context.nodeName().equals(envelope.nodeName())) {
            errors.add(error(ValidationErrorCode.ENVELOPE_MISMATCH,
                    "Envelope nodeName does not match the active node", "nodeName"));
        }
    }

    private void validateSchema(Object payload, List<TradingValidationError> errors) {
        beanValidator.validate(payload).stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .map(this::schemaError)
                .forEach(errors::add);
    }

    private TradingValidationError schemaError(ConstraintViolation<Object> violation) {
        return error(ValidationErrorCode.INVALID_SCHEMA,
                "Payload constraint failed: " + violation.getMessage(),
                violation.getPropertyPath().toString());
    }

    private void validateTargetEcho(JsonNode targetEcho,
                                    TargetContext target,
                                    List<TradingValidationError> errors) {
        if (targetEcho == null || targetEcho.isNull()) {
            return;
        }
        String ticker = text(targetEcho.get("ticker"));
        if (ticker != null && !ticker.equalsIgnoreCase(target.stockCode())
                && !ticker.equalsIgnoreCase(target.targetId())) {
            errors.add(error(ValidationErrorCode.TARGET_MISMATCH,
                    "Payload target ticker does not match the active target", "targetEcho.ticker"));
        }
        String stockName = text(targetEcho.get("stockName"));
        if (stockName != null && !stockName.equals(target.stockName())) {
            errors.add(error(ValidationErrorCode.TARGET_MISMATCH,
                    "Payload target name does not match the active target", "targetEcho.stockName"));
        }
    }

    private void validateEntities(String output,
                                  AllowedEntitySet allowedEntities,
                                  List<TradingValidationError> errors) {
        for (String entity : allowedEntities.findForeignEntities(output)) {
            errors.add(error(ValidationErrorCode.FOREIGN_ENTITY,
                    "Payload contains an entity not authorized by node input: " + entity,
                    "payload"));
        }
    }

    private void validateNumericFacts(String output,
                                      List<NumericInputFact> facts,
                                      List<TradingValidationError> errors) {
        for (NumericInputFact fact : facts) {
            for (String label : fact.labels()) {
                Pattern pattern = Pattern.compile(Pattern.quote(label)
                        + "[^0-9+\\-]{0,12}" + NUMBER + "\\s*(%|％|元|CNY|人民币)?",
                        Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(output);
                while (matcher.find()) {
                    BigDecimal actual = new BigDecimal(matcher.group(1));
                    String suffix = matcher.group(2);
                    if (fact.unit() == NumericInputFact.Unit.PERCENTAGE_POINT
                            && (suffix == null || !(suffix.equals("%") || suffix.equals("％")))) {
                        errors.add(error(ValidationErrorCode.DATA_QUALITY,
                                "Percentage-point fact must use an explicit percent unit", fact.field()));
                    }
                    if (actual.subtract(fact.value()).abs().compareTo(fact.tolerance()) > 0) {
                        errors.add(error(ValidationErrorCode.INPUT_DATA_CONFLICT,
                                "Payload numeric claim conflicts with authoritative node input",
                                fact.field()));
                    }
                }
            }
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static String textualClaims(JsonNode node) {
        StringBuilder text = new StringBuilder();
        appendText(node, text);
        return text.toString();
    }

    private static void appendText(JsonNode node, StringBuilder text) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            text.append(node.asText()).append('\n');
            return;
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> appendText(child, text));
        }
    }

    private static TradingValidationError error(ValidationErrorCode code,
                                                String message,
                                                String field) {
        return new TradingValidationError(code, message, field);
    }
}
