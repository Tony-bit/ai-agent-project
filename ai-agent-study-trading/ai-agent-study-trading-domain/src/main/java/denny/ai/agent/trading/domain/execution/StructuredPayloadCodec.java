package denny.ai.agent.trading.domain.execution;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class StructuredPayloadCodec {
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public StructuredPayloadCodec(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.validator = validator;
    }

    public <T> T parse(String response, Class<T> payloadType) {
        if (response == null || response.isBlank()) {
            throw new StructuredPayloadException("structured response is blank");
        }
        try {
            T payload = objectMapper.readValue(response, payloadType);
            Set<ConstraintViolation<T>> violations = validator.validate(payload);
            if (!violations.isEmpty()) {
                String errors = violations.stream()
                        .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                        .sorted()
                        .collect(Collectors.joining("; "));
                throw new StructuredPayloadException("structured response validation failed: " + errors);
            }
            return payload;
        } catch (StructuredPayloadException error) {
            throw error;
        } catch (Exception error) {
            throw new StructuredPayloadException("structured response is not valid JSON for "
                    + payloadType.getSimpleName(), error);
        }
    }

    public <T> String outputContract(Class<T> payloadType) {
        return new BeanOutputConverter<>(payloadType, objectMapper).getFormat();
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("failed to serialize prompt input", error);
        }
    }
}
