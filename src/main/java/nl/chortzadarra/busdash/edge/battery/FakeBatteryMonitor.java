package nl.chortzadarra.busdash.edge.battery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import nl.chortzadarra.busdash.edge.solar.FakeSolarCharger;

/**
 * Gesimuleerde accu voor ontwikkelen op je laptop: langzaam ontladen met een
 * schommelende stroom, zodat je dashboard iets te laten zien heeft. De temperatuur loopt mee
 * met de versnelde dag van {@link nl.chortzadarra.busdash.edge.solar.FakeSolarCharger}: rond
 * het middaguur 11 °C, 's nachts tot -1 °C (zodat je de bus het koud ziet krijgen).
 */
public final class FakeBatteryMonitor implements BatteryMonitor {

    private static final double CAPACITY_AH = 320.0;

    private final Clock clock;
    private Instant startedAt;

    public FakeBatteryMonitor() {
        this(Clock.systemUTC());
    }

    FakeBatteryMonitor(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void start() {
        startedAt = clock.instant();
    }

    @Override
    public Optional<BatteryReading> latestReading() {
        if (startedAt == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        double seconds = Duration.between(startedAt, now).toMillis() / 1000.0;

        double current = -3.0 + 1.5 * Math.sin(seconds / 10.0);   // A, ontladen
        double consumedAh = -seconds / 3600.0 * 3.0 * 60;           // versneld: 1 s = 1 min
        double soc = Math.max(0, 80.0 + consumedAh / CAPACITY_AH * 100);
        double voltage = 12.9 + (soc - 50) * 0.008;
        double dayFraction = seconds / FakeSolarCharger.DAY_LENGTH.toSeconds() % 1.0;
        double temperature = Math.round(5.0 + 6.0 * Math.cos(2 * Math.PI * (dayFraction - 0.6)));

        return Optional.of(new BatteryReading(
                now, voltage, current, (int) Math.round(voltage * current),
                soc, consumedAh, (int) (soc / 100 * CAPACITY_AH / Math.abs(current) * 60),
                temperature, 12.6));
    }

    @Override
    public void close() {
        // niets op te ruimen
    }
}
