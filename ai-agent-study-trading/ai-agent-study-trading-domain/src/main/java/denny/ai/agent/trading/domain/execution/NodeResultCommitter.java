package denny.ai.agent.trading.domain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.trading.domain.config.TradingStateContext;
import denny.ai.agent.trading.domain.config.TradingPhase;
import denny.ai.agent.trading.domain.validation.NodeResultValidator;
import denny.ai.agent.trading.domain.validation.NodeValidationContext;
import denny.ai.agent.trading.domain.validation.NodeValidationContextFactory;
import denny.ai.agent.trading.domain.validation.NodeValidationResult;
import jakarta.validation.Validation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import denny.ai.agent.trading.domain.prompt.PromptContractMode;

@Component
public class NodeResultCommitter {

    private final NodeResultValidator nodeResultValidator;
    private final NodeValidationContextFactory validationContextFactory;
    private final TradingNodeObservability observability;

    public NodeResultCommitter() {
        this(defaultObjectMapper());
    }

    private NodeResultCommitter(ObjectMapper objectMapper) {
        this(new NodeResultValidator(objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()),
                new NodeValidationContextFactory(objectMapper),
                new TradingNodeObservability(objectMapper));
    }

    @Autowired
    public NodeResultCommitter(NodeResultValidator nodeResultValidator,
                               NodeValidationContextFactory validationContextFactory,
                               TradingNodeObservability observability) {
        this.nodeResultValidator = Objects.requireNonNull(nodeResultValidator, "nodeResultValidator");
        this.validationContextFactory = Objects.requireNonNull(
                validationContextFactory, "validationContextFactory");
        this.observability = Objects.requireNonNull(observability, "observability");
    }

    public <T> NodeCommitResult commitValidated(NodeExecutionResult<T> result,
                                                TradingPhase expectedPhase,
                                                TradingStateContext stateContext,
                                                String nodeName,
                                                Consumer<T> contextWriter) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(stateContext, "stateContext");
        if (!result.isSuccess()) {
            stateContext.getValidationRegistry().markExecutionFailed(nodeName);
            observability.observe(stateContext, nodeName, "EXECUTION_FAILED", List.of(), result.latencyMs());
            return NodeCommitResult.notCommitted();
        }

        if (stateContext.getPromptSnapshot().mode() == PromptContractMode.STRICT_V2) {
            NodeValidationContext validationContext = validationContextFactory.create(
                    stateContext, nodeName, result.value());
            NodeResultEnvelope<T> envelope = NodeResultEnvelope.wrap(
                    stateContext.getTargetContext(), nodeName, result.value());
            NodeValidationResult validation = nodeResultValidator.validate(envelope, validationContext);
            if (!validation.isValid()) {
                result.scope().markFailed();
                stateContext.getValidationRegistry().markInvalid(nodeName, validation.errors());
                observability.observe(stateContext, nodeName, "INVALID",
                        validation.errors(), result.latencyMs());
                return NodeCommitResult.rejected(validation.errors());
            }
        }

        observability.observe(stateContext, nodeName, "VALID", List.of(), result.latencyMs());

        boolean committed = commit(result, expectedPhase,
                stateContext::getCurrentPhase, contextWriter);
        if (committed) {
            stateContext.getValidationRegistry().markValid(nodeName);
        } else {
            stateContext.getValidationRegistry().markExecutionFailed(nodeName);
        }
        return committed ? NodeCommitResult.committedResult() : NodeCommitResult.notCommitted();
    }

    public <T> boolean commit(NodeExecutionResult<T> result,
                              TradingPhase expectedPhase,
                              Supplier<TradingPhase> currentPhase,
                              Consumer<T> contextWriter) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(expectedPhase, "expectedPhase");
        Objects.requireNonNull(currentPhase, "currentPhase");
        Objects.requireNonNull(contextWriter, "contextWriter");
        if (!result.isSuccess()) {
            return false;
        }

        NodeExecutionScope scope = result.scope();
        if (currentPhase.get() != expectedPhase) {
            scope.markFailed();
            return false;
        }
        if (!scope.tryStartCommit()) {
            return false;
        }
        if (scope.isRequestCancelled() || scope.isDeadlineElapsed()
                || currentPhase.get() != expectedPhase) {
            scope.failCommit();
            return false;
        }

        try {
            contextWriter.accept(result.value());
            if (!scope.markCommitted()) {
                throw new IllegalStateException("Node commit state changed unexpectedly");
            }
            return true;
        } catch (RuntimeException error) {
            scope.failCommit();
            throw error;
        }
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
