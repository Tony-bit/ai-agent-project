package denny.ai.agent.trading.domain.validation;

import denny.ai.agent.trading.api.vo.TargetContext;

import java.util.List;
import java.util.Objects;

public record NodeValidationContext(
        TargetContext targetContext,
        String nodeName,
        AllowedEntitySet allowedEntities,
        List<NumericInputFact> numericFacts
) {
    public NodeValidationContext {
        Objects.requireNonNull(targetContext, "targetContext");
        if (nodeName == null || nodeName.isBlank()) {
            throw new IllegalArgumentException("nodeName must not be blank");
        }
        nodeName = nodeName.trim();
        Objects.requireNonNull(allowedEntities, "allowedEntities");
        numericFacts = numericFacts == null ? List.of() : List.copyOf(numericFacts);
    }
}
