package denny.ai.agent.trading.infra.tools;

/**
 * Tool input schema parameter definition.
 *
 * @param type        parameter type, e.g. "string" or "integer"
 * @param description parameter description for LLM
 */
public record ParamDef(String type, String description) {}
