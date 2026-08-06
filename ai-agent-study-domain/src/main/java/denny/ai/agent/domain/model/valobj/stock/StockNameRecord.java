package denny.ai.agent.domain.model.valobj.stock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 股票名称目录记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockNameRecord {

    private String stockName;

    private String stockCode;
}
