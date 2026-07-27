package denny.ai.agent.trading.api.vo;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** 单次 Trading run 的不可变权威标的身份。 */
public record TargetContext(
        String runId,
        String targetId,
        String stockName,
        String industry,
        LocalDate asOfDate
) {

    private static final Pattern TARGET_ID_PATTERN =
            Pattern.compile("^[0-9]{6}\\.(SH|SZ|BJ)$");

    public TargetContext {
        requireUuid(runId);
        if (targetId == null || !TARGET_ID_PATTERN.matcher(targetId).matches()) {
            throw new IllegalArgumentException("targetId must be a canonical A-share ts_code");
        }
        if (stockName == null || stockName.isBlank()) {
            throw new IllegalArgumentException("stockName must not be blank");
        }
        stockName = stockName.trim();
        industry = industry == null || industry.isBlank() ? null : industry.trim();
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
    }

    public String stockCode() {
        return targetId.substring(0, 6);
    }

    private static void requireUuid(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        try {
            UUID parsed = UUID.fromString(runId);
            if (!parsed.toString().equalsIgnoreCase(runId)) {
                throw new IllegalArgumentException("runId must be a canonical UUID");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("runId must be a canonical UUID", error);
        }
    }
}
