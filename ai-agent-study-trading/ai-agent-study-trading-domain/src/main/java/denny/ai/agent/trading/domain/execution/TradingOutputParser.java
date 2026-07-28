package denny.ai.agent.trading.domain.execution;

import denny.ai.agent.trading.api.vo.NarrativeNodeResult;
import denny.ai.agent.trading.domain.prompt.PromptContractMode;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Function;

@Component
public class TradingOutputParser {

    private final StructuredPayloadCodec codec;

    public TradingOutputParser(StructuredPayloadCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public <T> NarrativeNodeResult parseNarrative(PromptContractMode mode,
                                                   String response,
                                                   String javaRole,
                                                   Class<T> strictPayloadType,
                                                   Function<T, NarrativeNodeResult> strictAdapter) {
        Objects.requireNonNull(mode, "mode");
        if (mode == PromptContractMode.RELAXED_V3) {
            return new NarrativeNodeResult(javaRole, response);
        }
        T payload = codec.parse(response, strictPayloadType);
        return strictAdapter.apply(payload);
    }

    public <T> T parseStructured(PromptContractMode mode,
                                 String response,
                                 Class<T> payloadType) {
        Objects.requireNonNull(mode, "mode");
        return codec.parse(mode == PromptContractMode.STRICT_V2
                ? response : extractJsonObject(response), payloadType);
    }

    private String extractJsonObject(String response) {
        if (response == null || response.isBlank()) {
            throw new StructuredPayloadException("structured response is blank");
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new StructuredPayloadException("relaxed response does not contain a JSON object");
        }
        return response.substring(start, end + 1);
    }
}
