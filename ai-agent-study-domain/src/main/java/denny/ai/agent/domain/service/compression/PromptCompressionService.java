package denny.ai.agent.domain.service.compression;

import denny.ai.agent.domain.model.valobj.runtime.RetryRuntimeContext;
import denny.ai.agent.domain.service.armory.factory.element.CompressionPolicy;
import org.springframework.ai.chat.prompt.Prompt;

public interface PromptCompressionService {

    Prompt compress(Prompt originalPrompt,
                    RetryRuntimeContext runtimeContext,
                    CompressionPolicy policy);
}
