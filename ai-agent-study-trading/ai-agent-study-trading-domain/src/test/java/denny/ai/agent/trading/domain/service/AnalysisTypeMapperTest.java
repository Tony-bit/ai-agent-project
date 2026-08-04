package denny.ai.agent.trading.domain.service;

import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnalysisTypeMapperTest {

    private final AnalysisTypeMapper mapper = new AnalysisTypeMapper();

    @Test
    void mapsSingleCombinedDefaultAndNormalizedAnalysisTypes() {
        assertEquals(List.of(AnalystTypeEnum.TECHNICAL), mapper.map(" technical "));
        assertEquals(List.of(AnalystTypeEnum.FUNDAMENTAL, AnalystTypeEnum.NEWS),
                mapper.map("FUNDAMENTAL,NEWS"));
        assertEquals(AnalysisTypeMapper.DEFAULT_ANALYSTS, mapper.map("ALL"));
        assertEquals(AnalysisTypeMapper.DEFAULT_ANALYSTS, mapper.map(null));
    }

    @Test
    void keepsValidSubsetAndFallsBackWhenAllItemsAreUnknown() {
        assertEquals(List.of(AnalystTypeEnum.TECHNICAL), mapper.map("TECHNICAL,UNKNOWN"));
        assertEquals(AnalysisTypeMapper.DEFAULT_ANALYSTS, mapper.map("UNKNOWN"));
    }
}
