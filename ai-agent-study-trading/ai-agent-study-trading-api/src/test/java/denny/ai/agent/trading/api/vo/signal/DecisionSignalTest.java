package denny.ai.agent.trading.api.vo.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionSignalTest {

    @Test
    void enforcesAvailableAndUnavailableInvariants() {
        DecisionSignal<Integer> available = DecisionSignal.available(
                4, DecisionSignalSource.DETERMINISTIC_V3, "rating-v1");
        DecisionSignal<Integer> unavailable = DecisionSignal.unavailable(
                DecisionSignalSource.DETERMINISTIC_V3, "rating-v1", "missing input");

        assertTrue(available.isAvailable());
        assertEquals(4, available.value());
        assertFalse(unavailable.isAvailable());
        assertEquals("missing input", unavailable.reason());
        assertThrows(IllegalArgumentException.class, () -> new DecisionSignal<>(
                DecisionSignalStatus.AVAILABLE, null, DecisionSignalSource.LLM_V2, null, null));
        assertThrows(IllegalArgumentException.class, () -> new DecisionSignal<>(
                DecisionSignalStatus.UNAVAILABLE, 3, DecisionSignalSource.LLM_V2, null, "missing"));
        assertThrows(IllegalArgumentException.class, () -> DecisionSignal.available(
                3, DecisionSignalSource.DETERMINISTIC_V3, null));
    }
}
