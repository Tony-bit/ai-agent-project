package denny.ai.agent.domain.service.armory;

import org.springframework.ai.chat.prompt.Prompt;

/**
 * 压缩触发异常
 * 当需要触发上下文压缩时抛出此异常，路由到压缩节点进行处理
 */
public class CompressionRequiredException extends RuntimeException {

    private final Prompt originalPrompt;
    private final String returnNode;

    public CompressionRequiredException(Prompt originalPrompt, String returnNode) {
        super("Compression required, will route to compression node");
        this.originalPrompt = originalPrompt;
        this.returnNode = returnNode;
    }

    public Prompt getOriginalPrompt() {
        return originalPrompt;
    }

    public String getReturnNode() {
        return returnNode;
    }
}
