package nl.chortzadarra.busdash.edge.solar;

import nl.chortzadarra.busdash.edge.vedirect.VeDirectBlock;
import nl.chortzadarra.busdash.edge.vedirect.VeDirectParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static nl.chortzadarra.busdash.edge.vedirect.VeDirectFrames.block;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpptStateTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private final MpptState state = new MpptState();

    @Test
    void convertsUnitsFromProtocol() {
        SolarReading r = state.update(new VeDirectBlock(Map.of(
                "V", "13650", "I", "30200", "VPV", "38510", "PPV", "412",
                "CS", "3", "ERR", "0", "H20", "125", "H21", "520")), NOW).orElseThrow();

        assertEquals(38.51, r.panelVoltage(), 1e-9);
        assertEquals(412, r.panelPower());
        assertEquals(13.65, r.batteryVoltage(), 1e-9);
        assertEquals(30.2, r.batteryCurrent(), 1e-9);
        assertEquals(ChargeState.BULK, r.chargeState());
        assertTrue(r.chargeState().charging());
        assertEquals(1.25, r.yieldTodayKWh(), 1e-9);
        assertEquals(520, r.maxPowerToday());
        assertEquals(0, r.errorCode());
    }

    @Test
    void noReadingWithoutPanelValues() {
        assertTrue(state.update(new VeDirectBlock(Map.of("V", "13650")), NOW).isEmpty());
    }

    @Test
    void unknownStateAndMissingFields() {
        SolarReading r = state.update(new VeDirectBlock(Map.of("VPV", "10", "PPV", "0", "CS", "99")), NOW).orElseThrow();
        assertEquals(ChargeState.UNKNOWN, r.chargeState());
        assertNull(r.batteryVoltage());
        assertNull(r.yieldTodayKWh());
    }

    @Test
    void worksWithRealParserFrames() {
        List<SolarReading> readings = new ArrayList<>();
        VeDirectParser parser = new VeDirectParser(b -> state.update(b, NOW).ifPresent(readings::add));
        parser.feed(block("PID", "0xA053", "V", "13650", "I", "1200", "VPV", "18300", "PPV", "17", "CS", "5",
                "MPPT", "2", "ERR", "0", "LOAD", "ON", "H20", "3", "H21", "40"));

        assertEquals(1, readings.size());
        assertEquals(ChargeState.FLOAT, readings.get(0).chargeState());
        assertEquals("Druppelladen", readings.get(0).chargeState().label());
    }
}
