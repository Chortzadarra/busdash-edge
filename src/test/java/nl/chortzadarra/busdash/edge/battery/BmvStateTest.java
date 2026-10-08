package nl.chortzadarra.busdash.edge.battery;

import nl.chortzadarra.busdash.edge.vedirect.VeDirectBlock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BmvStateTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private final BmvState state = new BmvState();

    @Test
    void convertsUnitsFromProtocol() {
        BatteryReading r = state.update(new VeDirectBlock(Map.of(
                "V", "13280", "I", "-1500", "P", "-20", "SOC", "876",
                "CE", "-39840", "TTG", "1440", "T", "7")), NOW).orElseThrow();

        assertEquals(13.28, r.voltage(), 1e-9);
        assertEquals(-1.5, r.current(), 1e-9);
        assertEquals(-20, r.power());
        assertEquals(87.6, r.stateOfCharge(), 1e-9);
        assertEquals(-39.84, r.consumedAh(), 1e-9);
        assertEquals(1440, r.timeToGoMinutes());
        assertEquals(7.0, r.temperature(), 1e-9);
        assertEquals(NOW, r.timestamp());
    }

    @Test
    void noReadingWithoutVoltageAndCurrent() {
        assertTrue(state.update(new VeDirectBlock(Map.of("H1", "-50000")), NOW).isEmpty());
    }

    @Test
    void keepsValuesWhenHistoryBlockArrives() {
        state.update(new VeDirectBlock(Map.of("V", "13280", "I", "-1500", "SOC", "876")), NOW);

        BatteryReading r = state.update(new VeDirectBlock(Map.of("H1", "-50000", "H2", "-12000")), NOW)
                .orElseThrow();

        assertEquals(13.28, r.voltage(), 1e-9);
        assertEquals(87.6, r.stateOfCharge(), 1e-9);
    }

    @Test
    void unknownOrInfiniteValuesBecomeNull() {
        BatteryReading r = state.update(new VeDirectBlock(Map.of(
                "V", "13280", "I", "2000", "SOC", "---", "TTG", "-1")), NOW).orElseThrow();

        assertNull(r.stateOfCharge());
        assertNull(r.timeToGoMinutes());
        assertNull(r.temperature());
    }
}
