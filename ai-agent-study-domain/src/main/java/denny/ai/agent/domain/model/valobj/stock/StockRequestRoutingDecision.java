package denny.ai.agent.domain.model.valobj.stock;

import denny.ai.agent.domain.model.valobj.StockSlot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 股票请求解析后的路由决策。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockRequestRoutingDecision {

    private StockRequestRouteDecisionType decisionType;

    private StockTargetStatus stockTargetStatus;

    private StockAnalysisMode analysisMode;

    private StockSlot stockSlot;

    private String clarificationPrompt;

    private String executionQuery;

    private String pendingVersion;

    private String claimId;

    private String message;
}
