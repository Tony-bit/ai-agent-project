package denny.ai.agent.domain.service.armory.stream;

public final class MissingStreamTimeoutPolicyException extends IllegalStateException {

    public MissingStreamTimeoutPolicyException() {
        super("Layered streaming request is missing StreamChunkTimeoutPolicy");
    }
}
