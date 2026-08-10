package denny.ai.agent.trading.infra.provider.tushare.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** Tushare daily_basic 接口响应。 */
@Data
@EqualsAndHashCode(callSuper = false)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TushareDailyBasicDTO extends TushareBaseDTO {

    private String tsCode;
    private String tradeDate;
    private BigDecimal close;
    private Double pe;
    private Double peTtm;
    private Double pb;
    private Double ps;
    private Double psTtm;
    private Double dvRatio;
    private BigDecimal totalMv;
    private BigDecimal circMv;

    public String getTradeDateFormatted() {
        return fmtDate(tradeDate);
    }
}
