package nl.chortzadarra.busdash.edge.battery;

import nl.chortzadarra.busdash.edge.vedirect.VeDirectBlock;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Houdt de laatst bekende BMV-velden bij en vertaalt die naar een {@link BatteryReading}.
 *
 * <p>Nodig omdat de BMV-712 twee blokken afwisselt: het ene met V/I/SOC enz., het andere
 * met historiedata (H1..H18). Door samen te voegen gaat er per blok geen informatie verloren.
 *
 * <p>Eenheden in het protocol: V en VS in mV, I in mA, P in W, CE in mAh,
 * SOC in promille, TTG in minuten (-1 = oneindig), T in °C.
 */
public final class BmvState {

    private final Map<String, String> fields = new HashMap<>();

    public Optional<BatteryReading> update(VeDirectBlock block, Instant timestamp) {
        fields.putAll(block.fields());
        return currentReading(timestamp);
    }

    Optional<BatteryReading> currentReading(Instant timestamp) {
        Integer millivolts = parseInt("V");
        Integer milliamps = parseInt("I");
        if (millivolts == null || milliamps == null) {
            return Optional.empty();
        }
        Integer ttg = parseInt("TTG");
        return Optional.of(new BatteryReading(
                timestamp,
                millivolts / 1000.0,
                milliamps / 1000.0,
                parseInt("P"),
                scaled("SOC", 10.0),
                scaled("CE", 1000.0),
                ttg == null || ttg < 0 ? null : ttg,
                scaled("T", 1.0),
                scaled("VS", 1000.0)));
    }

    private Integer parseInt(String label) {
        String raw = fields.get(label);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null; // bijv. "---" als de BMV nog niet gesynchroniseerd is
        }
    }

    private Double scaled(String label, double divisor) {
        Integer raw = parseInt(label);
        return raw == null ? null : raw / divisor;
    }
}
