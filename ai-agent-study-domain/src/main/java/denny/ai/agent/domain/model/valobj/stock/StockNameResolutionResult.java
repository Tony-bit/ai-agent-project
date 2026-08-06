package denny.ai.agent.domain.model.valobj.stock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 股票名称解析结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockNameResolutionResult {

    private StockNameResolutionStatus status;

    private String stockNameQuery;

    private StockNameRecord resolvedRecord;

    @Builder.Default
    private List<StockNameRecord> candidates = List.of();

    private Integer totalMatches;

    private String message;
}
