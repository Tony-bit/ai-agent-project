package denny.ai.agent.trading.api.vo.signal;

import java.util.Objects;

public record DecisionSignal<T>(
        DecisionSignalStatus status,
        T value,
        DecisionSignalSource source,
        String algorithmVersion,
        String reason
) {

    public DecisionSignal {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(source, "source");
        algorithmVersion = normalize(algorithmVersion);
        reason = normalize(reason);
        if (status == DecisionSignalStatus.AVAILABLE && value == null) {
            throw new IllegalArgumentException("available signal must have a value");
        }
        if (status == DecisionSignalStatus.UNAVAILABLE && value != null) {
            throw new IllegalArgumentException("unavailable signal must not have a value");
        }
        if (status == DecisionSignalStatus.UNAVAILABLE && reason == null) {
            throw new IllegalArgumentException("unavailable signal must have a reason");
        }
        if ((source == DecisionSignalSource.DETERMINISTIC_V3
                || source == DecisionSignalSource.DERIVED_V3) && algorithmVersion == null) {
            throw new IllegalArgumentException("deterministic signal must have an algorithm version");
        }
    }

    public static <T> DecisionSignal<T> available(T value,
                                                   DecisionSignalSource source,
                                                   String algorithmVersion) {
        return new DecisionSignal<>(DecisionSignalStatus.AVAILABLE, value, source,
                algorithmVersion, null);
    }

    public static <T> DecisionSignal<T> unavailable(DecisionSignalSource source,
                                                     String algorithmVersion,
                                                     String reason) {
        return new DecisionSignal<>(DecisionSignalStatus.UNAVAILABLE, null, source,
                algorithmVersion, reason);
    }

    public boolean isAvailable() {
        return status == DecisionSignalStatus.AVAILABLE;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
