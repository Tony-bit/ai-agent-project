package denny.ai.agent.trading.infra.provider.tushare.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Tushare cash_flow（现金流量表）接口响应。
 * <p>
 * Jackson 自动映射：im_net_incr_cash_equv → imNetIncrCashEquv, pay_for_fixed_assets → payForFixedAssets
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=135">Tushare cash_flow 文档</a>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TushareCashFlowDTO extends TushareBaseDTO {

    private String annDate;
    private String endDate;
    private BigDecimal imNetIncrCashEquv;  // 万元
    private BigDecimal payForFixedAssets;  // 万元
    private BigDecimal operNetCashFlow;    // 万元

    public String getAnnDateFormatted() { return fmtDate(annDate); }
    public String getEndDateFormatted() { return fmtDate(endDate); }

    /**
     * 自由现金流 = 经营活动现金流净额 - 资本支出（单位：元）
     */
    public BigDecimal getFreeCashFlowYuan() {
        if (operNetCashFlow == null) return null;
        BigDecimal result = operNetCashFlow.multiply(new BigDecimal("10000"));
        if (payForFixedAssets != null) {
            result = result.subtract(payForFixedAssets.abs().multiply(new BigDecimal("10000")));
        }
        return result;
    }
}
