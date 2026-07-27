package denny.ai.agent.trading.domain.validation;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** An authoritative numeric value supplied to a node. */
public record NumericInputFact(
        String field,
        BigDecimal value,
        Unit unit,
        Set<String> labels,
        BigDecimal tolerance
) {
    public NumericInputFact {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        field = field.trim();
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(unit, "unit");
        if (labels == null || labels.isEmpty()) {
            throw new IllegalArgumentException("labels must not be empty");
        }
        LinkedHashSet<String> normalizedLabels = new LinkedHashSet<>();
        for (String label : labels) {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("labels must not contain blanks");
            }
            normalizedLabels.add(label.trim());
        }
        labels = Set.copyOf(normalizedLabels);
        tolerance = tolerance == null ? BigDecimal.ZERO : tolerance;
        if (tolerance.signum() < 0) {
            throw new IllegalArgumentException("tolerance must not be negative");
        }
    }

    public static NumericInputFact exact(String field, BigDecimal value, Unit unit, String... labels) {
        return new NumericInputFact(field, value, unit, Set.of(labels), BigDecimal.ZERO);
    }

    public enum Unit {
        RAW,
        CNY,
        PERCENTAGE_POINT
    }
}
