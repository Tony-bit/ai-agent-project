package denny.ai.agent.trading.infra.provider.tushare.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * Tushare fina_indicator（财务指标）接口响应。
 * <p>
 * Jackson 自动映射：grossprofit_margin → grossprofitMargin, debt_to_assets → debtToAssets
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=140">Tushare fina_indicator 文档</a>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TushareFinaIndicatorDTO extends TushareBaseDTO {

    private String annDate;
    private String endDate;
    private String updateFlag;
    private Double roe;
    private Double roa;
    private Double grossprofitMargin;
    private Double netprofitMargin;
    private Double debtToAssets;
    private Double currentRatio;
    private Double quickRatio;
    private Double cashRatio;
    private BigDecimal eps;
    private BigDecimal bps;
    private Double trYoy;
    private Double netprofitYoy;

    public String getAnnDateFormatted() { return fmtDate(annDate); }
    public String getEndDateFormatted() { return fmtDate(endDate); }
}
