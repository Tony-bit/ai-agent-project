package denny.ai.agent.trading.domain.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import denny.ai.agent.trading.api.vo.FundamentalDataVO;
import denny.ai.agent.trading.api.vo.StockInfoVO;
import denny.ai.agent.trading.domain.execution.StructuredPayloadCodec;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FundamentalAnalystValuationPolicyTest {

    private FundamentalAnalystNode node;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        StructuredPayloadCodec codec = new StructuredPayloadCodec(new ObjectMapper(), validator);
        node = new FundamentalAnalystNode();
        ReflectionTestUtils.setField(node, "structuredPayloadCodec", codec);
    }

    @Test
    void stockDataMakesPeTtmAuthoritativeAndForbidsQuarterlyEpsDerivation() {
        String input = node.buildStockData(
                StockInfoVO.builder()
                        .ticker("600285.SH")
                        .currentPrice(new BigDecimal("22.24"))
                        .build(),
                FundamentalDataVO.builder()
                        .eps(new BigDecimal("0.435"))
                        .peTtm(16.6)
                        .build());

        assertTrue(input.contains("\"peTtm\":16.6"));
        assertTrue(input.contains("默认且权威的 PE 口径"));
        assertTrue(input.contains("禁止使用 currentPrice / eps"));
        assertFalse(input.contains("51.1"));
    }

    @Test
    void missingPeTtmRequiresUnavailableMessage() {
        String input = node.buildStockData(
                StockInfoVO.builder()
                        .currentPrice(new BigDecimal("22.24"))
                        .build(),
                FundamentalDataVO.builder()
                        .eps(new BigDecimal("0.435"))
                        .build());

        assertTrue(input.contains("PE_TTM 不可用"));
        assertTrue(input.contains("不得自行补算"));
        assertTrue(input.contains("禁止使用 currentPrice / eps"));
        assertFalse(input.contains("51.1"));
    }

    @Test
    void stockDataIncludesShareholderReturnFields() {
        String input = node.buildStockData(
                StockInfoVO.builder().ticker("600285.SH").build(),
                FundamentalDataVO.builder()
                        .dps(new BigDecimal("1.10"))
                        .dividendYield(4.0468)
                        .build());

        assertTrue(input.contains("\"dps\":1.10"));
        assertTrue(input.contains("\"dividendYield\":4.0468"));
    }
}
