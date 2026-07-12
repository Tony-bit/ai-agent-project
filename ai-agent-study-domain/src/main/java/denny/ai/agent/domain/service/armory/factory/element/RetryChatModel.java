package denny.ai.agent.domain.service.armory.factory.element;

import denny.ai.agent.domain.model.valobj.AiClientModelVO.RetryConfig;
import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.service.compression.PromptCompressionService;
import denny.ai.agent.domain.service.runtime.RetryRuntimeContextHolder;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Objects;

public class RetryChatModel implements ChatModel {

    private final ChatModel delegate;
    private final RetryConfig retryConfig;
    private final CompressionPolicy compressionPolicy;
    private final PromptCompressionService compressionService;
    private final AiErrorCodeExtractor errorCodeExtractor;

    public RetryChatModel(ChatModel delegate, RetryConfig retryConfig) {
        this(delegate, retryConfig, null, null, null);
    }

    public RetryChatModel(ChatModel delegate,
                          RetryConfig retryConfig,
                          CompressionPolicy compressionPolicy,
                          PromptCompressionService compressionService,
                          AiErrorCodeExtractor errorCodeExtractor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.retryConfig = Objects.requireNonNull(retryConfig, "retryConfig must not be null");
        this.compressionPolicy = compressionPolicy;
        this.compressionService = compressionService;
        this.errorCodeExtractor = errorCodeExtractor != null ? errorCodeExtractor : new AiErrorCodeExtractor();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        RetryRuntimeContext context = RetryRuntimeContextHolder.current();
        return new CallRetryStrategy(context).execute(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // Reactive retry/compression is implemented separately in Task 7.
        return delegate.stream(prompt);
    }

    private class CallRetryStrategy extends RetryStrategy<ChatResponse> {

        CallRetryStrategy(RetryRuntimeContext runtimeContext) {
            super(RetryChatModel.this.delegate, RetryChatModel.this.retryConfig,
                    RetryChatModel.this.compressionPolicy, RetryChatModel.this.compressionService,
                    runtimeContext, RetryChatModel.this.errorCodeExtractor);
        }

        @Override
        protected ChatResponse doExecute(Prompt prompt) {
            ChatResponse response = delegate.call(prompt);
            ChatResponseValidator validator = ResponseValidationContext.currentValidator();
            if (validator != null) {
                validator.validate(response);
            }
            return response;
        }

        @Override
        protected ChatResponse onExhausted(RuntimeException error) {
            if (error == null) {
                throw new IllegalStateException("exhausted all retry attempts without exception");
            }
            throw error;
        }
    }
}
