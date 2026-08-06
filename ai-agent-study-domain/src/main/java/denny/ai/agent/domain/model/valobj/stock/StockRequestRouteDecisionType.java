package denny.ai.agent.domain.model.valobj.stock;

/**
 * 股票请求路由决策类型。
 */
public enum StockRequestRouteDecisionType {
    CLARIFY_TARGET,
    CLARIFY_ANALYSIS_MODE,
    ROUTE_GENERAL_CHAT,
    ROUTE_TRADING,
    NOT_FOUND,
    ERROR
}
