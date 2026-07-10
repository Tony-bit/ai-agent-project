package denny.ai.agent.domain.service.armory.factory.element;

import org.springframework.ai.chat.model.ChatResponse;

@FunctionalInterface
public interface ChatResponseValidator {

    void validate(ChatResponse response);
}
