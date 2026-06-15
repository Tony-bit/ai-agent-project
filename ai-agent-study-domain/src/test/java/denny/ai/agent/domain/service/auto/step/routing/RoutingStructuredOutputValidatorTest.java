package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.service.armory.factory.element.ResponseValidationException;
import denny.ai.agent.domain.service.armory.factory.element.ResponseValidationFailureType;
import org.junit.Before;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class RoutingStructuredOutputValidatorTest {

    private RoutingStructuredOutputValidator validator;

    @Before
    public void setUp() {
        validator = new RoutingStructuredOutputValidator(new TaskGraphValidator());
    }

    @Test
    public void shouldAcceptValidUnifiedOutputWithoutRuntimeFields() {
        validator.unified().validate(response("""
                {"multiTask":false,"needsClarification":false,"missingInfo":[],"clarificationPrompt":"",
                 "reasoning":"single","taskList":[
                   {"taskId":"sub-1","taskIndex":1,"totalTasks":1,"content":"hello",
                    "intent":"GENERAL_CHAT","confidence":"HIGH","dependsOn":[],"slots":{}}
                 ]}
                """));
    }

    @Test
    public void shouldAcceptUnifiedOutputWithoutMissingInfoWhenClarificationIsFalse() {
        validator.unified().validate(response("""
                {"multiTask":false,"needsClarification":false,
                 "reasoning":"single","taskList":[
                   {"taskId":"sub-1","taskIndex":1,"totalTasks":1,"content":"hello",
                    "intent":"GENERAL_CHAT","confidence":"HIGH","dependsOn":[],"slots":{}}
                 ]}
                """));
    }

    @Test
    public void shouldThrowEmptyResponseFailureWhenChatResponseIsNull() {
        ResponseValidationException thrown = assertThrows(ResponseValidationException.class, () ->
                validator.unified().validate(null));

        assertEquals(ResponseValidationFailureType.EMPTY_RESPONSE, thrown.getFailureType());
    }

    @Test
    public void shouldAcceptValidUnifiedClarificationOutput() {
        validator.unified().validate(response("""
                {"multiTask":false,"needsClarification":true,"missingInfo":["stockCode"],
                 "clarificationPrompt":"请提供股票代码","reasoning":"missing stock","taskList":[]}
                """));
    }

    @Test
    public void shouldRejectRuntimeFieldsInUnifiedOutput() {
        ResponseValidationException thrown = assertThrows(ResponseValidationException.class, () ->
                validator.unified().validate(response("""
                        {"multiTask":false,"needsClarification":false,"missingInfo":[],"reasoning":"single",
                         "taskList":[{"taskId":"sub-1","taskIndex":1,"totalTasks":1,"content":"hello",
                         "intent":"GENERAL_CHAT","executorNode":"generalChatNode","confidence":"HIGH","slots":{}}]}
                        """)));

        assertEquals(ResponseValidationFailureType.SCHEMA_VALIDATION_ERROR, thrown.getFailureType());
    }

    @Test
    public void shouldRejectMissingRequiredFields() {
        ResponseValidationException thrown = assertThrows(ResponseValidationException.class, () ->
                validator.unified().validate(response("""
                        {"multiTask":false,"missingInfo":[],"reasoning":"missing required","taskList":[]}
                        """)));

        assertEquals(ResponseValidationFailureType.SCHEMA_VALIDATION_ERROR, thrown.getFailureType());
    }

    @Test
    public void shouldRejectWrongFieldTypes() {
        ResponseValidationException thrown = assertThrows(ResponseValidationException.class, () ->
                validator.queryDecomposition().validate(response("""
                        {"multiTask":"yes","reasoning":"wrong type","taskList":[]}
                        """)));

        assertEquals(ResponseValidationFailureType.SCHEMA_VALIDATION_ERROR, thrown.getFailureType());
    }

    @Test
    public void shouldRejectInvalidIntentInTaskRoutingOutput() {
        ResponseValidationException thrown = assertThrows(ResponseValidationException.class, () ->
                validator.taskIntentRouting().validate(response("""
                        {"intent":"TECHNICAL_CONSULTING","confidence":"HIGH","reasoning":"bad",
                         "baseSlot":{"topic":"x","sentiment":"neutral"},"intentSpecificSlots":{}}
                        """)));

        assertEquals(ResponseValidationFailureType.SCHEMA_VALIDATION_ERROR, thrown.getFailureType());
    }

    @Test
    public void shouldRejectUnknownIntentFromModelOutput() {
        ResponseValidationException thrown = assertThrows(ResponseValidationException.class, () ->
                validator.taskIntentRouting().validate(response("""
                        {"intent":"UNKNOWN","confidence":"LOW","reasoning":"unknown",
                         "baseSlot":null,"intentSpecificSlots":{}}
                        """)));

        assertEquals(ResponseValidationFailureType.SCHEMA_VALIDATION_ERROR, thrown.getFailureType());
    }

    @Test
    public void shouldRejectInvalidConfidenceEnum() {
        ResponseValidationException thrown = assertThrows(ResponseValidationException.class, () ->
                validator.taskIntentRouting().validate(response("""
                        {"intent":"GENERAL_CHAT","confidence":"VERY_HIGH","reasoning":"bad confidence",
                         "baseSlot":{},"intentSpecificSlots":{}}
                        """)));

        assertEquals(ResponseValidationFailureType.SCHEMA_VALIDATION_ERROR, thrown.getFailureType());
    }

    @Test
    public void shouldRejectBusinessRuleViolationInDecompositionOutput() {
        ResponseValidationException thrown = assertThrows(ResponseValidationException.class, () ->
                validator.queryDecomposition().validate(response("""
                        {"multiTask":false,"reasoning":"empty","taskList":[]}
                        """)));

        assertEquals(ResponseValidationFailureType.BUSINESS_VALIDATION_ERROR, thrown.getFailureType());
    }

    @Test
    public void shouldRejectClarificationWithoutMissingInfo() {
        ResponseValidationException thrown = assertThrows(ResponseValidationException.class, () ->
                validator.unified().validate(response("""
                        {"multiTask":false,"needsClarification":true,"missingInfo":[],
                         "clarificationPrompt":"请补充信息","reasoning":"missing info","taskList":[]}
                        """)));

        assertEquals(ResponseValidationFailureType.BUSINESS_VALIDATION_ERROR, thrown.getFailureType());
    }

    @Test
    public void shouldRejectClarificationWhenMissingInfoFieldIsAbsent() {
        ResponseValidationException thrown = assertThrows(ResponseValidationException.class, () ->
                validator.unified().validate(response("""
                        {"multiTask":false,"needsClarification":true,
                         "clarificationPrompt":"请补充信息","reasoning":"missing info","taskList":[]}
                        """)));

        assertEquals(ResponseValidationFailureType.SCHEMA_VALIDATION_ERROR, thrown.getFailureType());
    }

    @Test
    public void shouldClassifyInvalidJson() {
        ResponseValidationException thrown = assertThrows(ResponseValidationException.class, () ->
                validator.taskIntentRouting().validate(response("invalid json")));

        assertEquals(ResponseValidationFailureType.JSON_PARSE_ERROR, thrown.getFailureType());
    }

    @Test
    public void shouldRejectNonObjectJsonRoot() {
        ResponseValidationException thrown = assertThrows(ResponseValidationException.class, () ->
                validator.taskIntentRouting().validate(response("[]")));

        assertEquals(ResponseValidationFailureType.JSON_PARSE_ERROR, thrown.getFailureType());
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
