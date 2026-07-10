package denny.ai.agent.domain.service.armory.factory.element;

public enum ResponseValidationFailureType {
    INFRA_ERROR,
    EMPTY_RESPONSE,
    JSON_PARSE_ERROR,
    SCHEMA_VALIDATION_ERROR,
    DTO_CONVERSION_ERROR,
    BUSINESS_VALIDATION_ERROR
}
