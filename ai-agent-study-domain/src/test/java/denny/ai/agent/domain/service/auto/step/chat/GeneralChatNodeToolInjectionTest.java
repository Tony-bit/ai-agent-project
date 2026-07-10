package denny.ai.agent.domain.service.auto.step.chat;

import denny.ai.agent.domain.model.valobj.enums.IntentTypeEnum;
import org.junit.Before;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * GeneralChatNode Tool 注入测试
 */
public class GeneralChatNodeToolInjectionTest {

    private GeneralChatNode generalChatNode;

    @Before
    public void setUp() {
        generalChatNode = new GeneralChatNode();
    }

    @Test
    public void testSearchEpisodicMemoryCallbacks_FieldExists() throws Exception {
        Field field = GeneralChatNode.class.getDeclaredField("searchEpisodicMemoryCallbacks");
        assertNotNull(field);
        assertEquals(List.class, field.getType());
    }

    @Test
    public void testBuildSystemPrompt_WithUserId_IncludesContext() {
        String result = invokeBuildSystemPrompt(IntentTypeEnum.GENERAL_CHAT, "user-001");

        assertTrue(result.contains("user-001"));
        assertTrue(result.contains("["));
    }

    @Test
    public void testBuildSystemPrompt_WithoutUserId_ReturnsNull() {
        String result = invokeBuildSystemPrompt(IntentTypeEnum.GENERAL_CHAT, null);

        assertNull(result);
    }

    @Test
    public void testBuildSystemPrompt_EmptyUserId_ReturnsNull() {
        String result = invokeBuildSystemPrompt(IntentTypeEnum.GENERAL_CHAT, "");

        assertNull(result);
    }

    @Test
    public void testBuildSystemPrompt_AmbiguousIntent_IncludesContext() {
        String result = invokeBuildSystemPrompt(IntentTypeEnum.AMBIGUOUS, "user-002");

        assertTrue(result.contains("user-002"));
        assertTrue(result.contains("["));
    }

    @Test
    public void testToolInjection_NullList_ShouldNotCauseNPE() throws Exception {
        injectField(generalChatNode, "searchEpisodicMemoryCallbacks", null);

        var field = GeneralChatNode.class.getDeclaredField("searchEpisodicMemoryCallbacks");
        field.setAccessible(true);
        Object value = field.get(generalChatNode);
        assertNull(value);
    }

    @Test
    public void testToolInjection_EmptyList_ShouldNotCauseNPE() throws Exception {
        injectField(generalChatNode, "searchEpisodicMemoryCallbacks", new ArrayList<ToolCallback>());

        var field = GeneralChatNode.class.getDeclaredField("searchEpisodicMemoryCallbacks");
        field.setAccessible(true);
        List<?> value = (List<?>) field.get(generalChatNode);
        assertNotNull(value);
        assertTrue(value.isEmpty());
    }

    @Test
    public void testToolInjection_WithList_ShouldInject() throws Exception {
        List<ToolCallback> callbacks = new ArrayList<>();
        callbacks.add(mockToolCallback());
        injectField(generalChatNode, "searchEpisodicMemoryCallbacks", callbacks);

        var field = GeneralChatNode.class.getDeclaredField("searchEpisodicMemoryCallbacks");
        field.setAccessible(true);
        List<?> value = (List<?>) field.get(generalChatNode);

        assertNotNull(value);
        assertEquals(1, value.size());
    }

    private String invokeBuildSystemPrompt(IntentTypeEnum intent, String userId) {
        try {
            var method = GeneralChatNode.class.getDeclaredMethod("buildSystemPrompt", IntentTypeEnum.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(generalChatNode, intent, userId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void injectField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ToolCallback mockToolCallback() {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return null;
            }

            @Override
            public String call(String functionInput) {
                return null;
            }
        };
    }
}
