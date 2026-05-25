package denny.ai.agent.infrastructure.tools;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * EpisodicMemoryToolCallbacks 单元测试
 */
public class EpisodicMemoryToolCallbacksTest {

    @Test
    public void testSearchEpisodicMemoryCallback_ReturnsNonNull() {
        EpisodicMemoryToolCallbacks callbacks = new EpisodicMemoryToolCallbacks();
        var callback = callbacks.searchEpisodicMemoryCallback();
        
        assertNotNull(callback);
    }

    @Test
    public void testSearchEpisodicMemoryCallback_ReturnsToolCallback() {
        EpisodicMemoryToolCallbacks callbacks = new EpisodicMemoryToolCallbacks();
        var callback = callbacks.searchEpisodicMemoryCallback();
        
        assertNotNull(callback);
        assertNotNull(callback.getToolDefinition());
    }
}
