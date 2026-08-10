package denny.ai.agent.trading.infra.provider.tushare.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** Tushare income（利润表）接口响应。 */
@Data
@EqualsAndHashCode(callSuper = false)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TushareIncomeDTO extends TushareBaseDTO {

    private String annDate;
    private String endDate;
    private String updateFlag;
    private BigDecimal revenue;
    @JsonProperty("n_income_attr_p")
    private BigDecimal nIncomeAttrP;
}
