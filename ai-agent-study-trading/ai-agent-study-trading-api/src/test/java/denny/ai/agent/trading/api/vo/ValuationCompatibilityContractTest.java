package denny.ai.agent.trading.api.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValuationCompatibilityContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void newFieldsAreVisibleThroughLegacyGetters() {
        StockInfoVO stock = StockInfoVO.builder().peTtm(16.6).pb(3.2).build();

        assertEquals(16.6, stock.getPeRatio());
        assertEquals(3.2, stock.getPbRatio());
    }

    @Test
    void legacyFieldsAreVisibleThroughNewGetters() {
        FundamentalDataVO data = FundamentalDataVO.builder()
                .peRatio(18.4)
                .pbRatio(2.8)
                .build();

        assertEquals(18.4, data.getPeTtm());
        assertEquals(2.8, data.getPb());
    }

    @Test
    void newFieldWinsWhenBothContractsArePresent() {
        FundamentalDataVO data = FundamentalDataVO.builder()
                .peTtm(16.6)
                .peRatio(51.1)
                .build();

        assertEquals(16.6, data.getPeTtm());
        assertEquals(16.6, data.getPeRatio());
    }

    @Test
    void jsonKeepsLegacyFieldsWithoutChangingMarketCapUnit() {
        JsonNode json = objectMapper.valueToTree(StockInfoVO.builder()
                .peTtm(16.6)
                .totalMv(new BigDecimal("1257000"))
                .build());

        assertEquals(16.6, json.path("peTtm").doubleValue());
        assertEquals(16.6, json.path("peRatio").doubleValue());
        assertTrue(json.path("marketCap").isNull());
        assertEquals(0, new BigDecimal("1257000").compareTo(json.path("totalMv").decimalValue()));
    }
}
