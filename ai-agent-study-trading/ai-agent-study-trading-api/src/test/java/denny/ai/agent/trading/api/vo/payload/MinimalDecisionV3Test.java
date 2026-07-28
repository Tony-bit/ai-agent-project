package denny.ai.agent.trading.api.vo.payload;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MinimalDecisionV3Test {

    @Test
    void threeV3DecisionDtosExposeExactlySixTopLevelFields() {
        assertEquals(Set.of("recommendation", "reasoning"), fields(ResearchManagerDecisionV3.class));
        assertEquals(Set.of("action", "rationale"), fields(RecommendationDecisionV3.class));
        assertEquals(Set.of("decision", "reasoning"), fields(PortfolioDecisionV3.class));
        assertEquals(6, fields(ResearchManagerDecisionV3.class).size()
                + fields(RecommendationDecisionV3.class).size()
                + fields(PortfolioDecisionV3.class).size());
        for (Class<?> type : Set.of(ResearchManagerDecisionV3.class,
                RecommendationDecisionV3.class, PortfolioDecisionV3.class)) {
            Set<String> fields = fields(type);
            assertFalse(fields.contains("targetEcho"));
            assertFalse(fields.contains("ticker"));
            assertFalse(fields.contains("positionRatio"));
            assertFalse(fields.contains("price"));
        }
    }

    @Test
    void normalizesCaseAndChineseSynonymsDeterministically() {
        assertEquals("BUY", new RecommendationDecisionV3("买入", " 理由 ").action());
        assertEquals("SELL", new PortfolioDecisionV3("sell", "理由").decision());
        assertEquals("INSUFFICIENT_DATA",
                new ResearchManagerDecisionV3("数据不足", "理由").recommendation());
    }

    private Set<String> fields(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName()).collect(Collectors.toSet());
    }
}
