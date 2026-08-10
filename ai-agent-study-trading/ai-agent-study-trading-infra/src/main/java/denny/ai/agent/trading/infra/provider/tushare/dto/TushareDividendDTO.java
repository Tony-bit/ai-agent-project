package denny.ai.agent.trading.infra.provider.tushare.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** Tushare dividend 接口响应。 */
@Data
@EqualsAndHashCode(callSuper = false)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TushareDividendDTO extends TushareBaseDTO {

    private String tsCode;
    private String endDate;
    private String annDate;
    private String divProc;
    private BigDecimal cashDivTax;
    private String recordDate;
    private String exDate;
    private String payDate;
}
