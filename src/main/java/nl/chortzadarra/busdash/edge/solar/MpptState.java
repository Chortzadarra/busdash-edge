package nl.chortzadarra.busdash.edge.solar;

import nl.chortzadarra.busdash.edge.vedirect.VeDirectBlock;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Houdt de laatst bekende MPPT-velden bij en vertaalt die naar een {@link SolarReading}.
 *
 * <p>Eenheden in het protocol: V en VPV in mV, I in mA, PPV in W, H20 (opbrengst vandaag)
 * in 0,01 kWh, H21 (max vermogen vandaag) in W, CS en ERR als code.
 */
public final class MpptState {

    private final Map<String, String> fields = new HashMap<>();

    public Optional<SolarReading> update(VeDirectBlock block, Instant timestamp) {
        fields.putAll(block.fields());
        return currentReading(timestamp);
    }

    Optional<SolarReading> currentReading(Instant timestamp) {
        Integer panelMillivolts = parseInt("VPV");
        Integer panelWatts = parseInt("PPV");
        if (panelMillivolts == null || panelWatts == null) {
            return Optional.empty();
        }
        Integer cs = parseInt("CS");
        return Optional.of(new SolarReading(
                timestamp,
                panelMillivolts / 1000.0,
                panelWatts,
                scaled("V", 1000.0),
                scaled("I", 1000.0),
                cs == null ? null : ChargeState.of(cs),
                scaled("H20", 100.0),
                parseInt("H21"),
                parseInt("ERR")));
    }

    private Integer parseInt(String label) {
        String raw = fields.get(label);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double scaled(String label, double divisor) {
        Integer raw = parseInt(label);
        return raw == null ? null : raw / divisor;
    }
}
