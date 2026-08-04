package denny.ai.agent.trading.domain.service;

import denny.ai.agent.trading.api.vo.AnalystTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
public class AnalysisTypeMapper {

    public static final List<AnalystTypeEnum> DEFAULT_ANALYSTS = List.of(
            AnalystTypeEnum.FUNDAMENTAL,
            AnalystTypeEnum.TECHNICAL,
            AnalystTypeEnum.SENTIMENT,
            AnalystTypeEnum.NEWS);

    public List<AnalystTypeEnum> map(String stockQueryType) {
        if (stockQueryType == null || stockQueryType.isBlank()
                || "ALL".equalsIgnoreCase(stockQueryType.trim())) {
            return DEFAULT_ANALYSTS;
        }

        List<AnalystTypeEnum> analysts = new ArrayList<>();
        for (String item : stockQueryType.split(",")) {
            try {
                AnalystTypeEnum analyst = AnalystTypeEnum.valueOf(item.trim().toUpperCase(Locale.ROOT));
                if (!analysts.contains(analyst)) {
                    analysts.add(analyst);
                }
            } catch (IllegalArgumentException ignored) {
                log.warn("Ignoring unsupported stockQueryType item: {}", item);
            }
        }
        if (analysts.isEmpty()) {
            log.warn("No supported stockQueryType item found, using default analysts: {}", stockQueryType);
            return DEFAULT_ANALYSTS;
        }
        return List.copyOf(analysts);
    }
}
