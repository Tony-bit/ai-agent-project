package denny.ai.agent.trading.infra.provider.tushare.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TushareDailyBasicDTOTest {

    @Test
    void mapsDailyBasicValuationFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        TushareDailyBasicDTO dto = mapper.readValue("""
                {"ts_code":"600285.SH","trade_date":"20260807","close":22.24,
                 "pe":16.8,"pe_ttm":16.6,"pb":3.2,"total_mv":1257000,"circ_mv":1249000}
                """, TushareDailyBasicDTO.class);

        assertEquals("600285.SH", dto.getTsCode());
        assertEquals(16.6, dto.getPeTtm());
        assertEquals(0, new BigDecimal("1257000").compareTo(dto.getTotalMv()));
        assertEquals("2026-08-07", dto.getTradeDateFormatted());
    }
}
