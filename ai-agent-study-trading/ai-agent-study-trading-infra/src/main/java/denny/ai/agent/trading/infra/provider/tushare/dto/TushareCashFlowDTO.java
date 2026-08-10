package denny.ai.agent.trading.infra.provider.tushare.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * Tushare cash_flow（现金流量表）接口响应。
 * <p>
 * 金额字段使用 Tushare 原始的元单位。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=135">Tushare cash_flow 文档</a>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TushareCashFlowDTO extends TushareBaseDTO {

    private String annDate;
    private String endDate;
    private String updateFlag;
    @JsonProperty("n_cashflow_act")
    private BigDecimal nCashflowAct;          // 元
    @JsonProperty("c_pay_acq_const_fiolta")
    private BigDecimal cPayAcqConstFiolta;    // 元

    public String getAnnDateFormatted() { return fmtDate(annDate); }
    public String getEndDateFormatted() { return fmtDate(endDate); }

    /** 自由现金流 = 经营活动现金流净额 - 资本支出。 */
    public BigDecimal calculateFreeCashFlow() {
        if (nCashflowAct == null) return null;
        return cPayAcqConstFiolta == null
                ? nCashflowAct
                : nCashflowAct.subtract(cPayAcqConstFiolta.abs());
    }
}
