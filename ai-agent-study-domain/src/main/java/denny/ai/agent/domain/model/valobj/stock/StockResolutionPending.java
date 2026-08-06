package denny.ai.agent.domain.model.valobj.stock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 股票名称二次澄清 Pending。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockResolutionPending {

    private String version;

    private StockResolutionPendingStatus status;

    private String claimId;

    private Instant claimExpiresAt;

    private String originalQuery;

    private String stockNameQuery;

    private StockTargetStatus targetStatus;

    @Builder.Default
    private List<StockNameRecord> orderedCandidates = List.of();

    private String resolvedStockName;

    private String resolvedStockCode;

    private StockAnalysisMode analysisMode;

    private Instant createdAt;

    private Instant expiresAt;
}
