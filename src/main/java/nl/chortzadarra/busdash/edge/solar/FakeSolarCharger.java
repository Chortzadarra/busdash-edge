package nl.chortzadarra.busdash.edge.solar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Gesimuleerde zonnelader voor ontwikkelen op je laptop. Een hele dag duurt hier
 * {@link #DAY_LENGTH} (standaard 3 minuten), zodat je in het dashboard snel nacht,
 * ochtend en volle zon voorbij ziet komen.
 */
public final class FakeSolarCharger implements SolarCharger {

    public static final Duration DAY_LENGTH = Duration.ofMinutes(3);
    private static final int PEAK_WATTS = 420;

    private final Clock clock;
    private final Duration dayLength;
    private Instant startedAt;

    public FakeSolarCharger() {
        this(Clock.systemUTC(), DAY_LENGTH);
    }

    public FakeSolarCharger(Clock clock, Duration dayLength) {
        this.clock = clock;
        this.dayLength = dayLength;
    }

    @Override
    public void start() {
        startedAt = clock.instant();
    }

    @Override
    public Optional<SolarReading> latestReading() {
        if (startedAt == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        long elapsedMs = Duration.between(startedAt, now).toMillis();
        // Begin halverwege de ochtend, dan zie je meteen iets gebeuren.
        double dayFraction = ((elapsedMs / (double) dayLength.toMillis()) + 0.35) % 1.0;

        // Zon op van 0,25 tot 0,80 van de "dag", met een sinusvormige piek.
        double sun = dayFraction < 0.25 || dayFraction > 0.80
                ? 0
                : Math.sin((dayFraction - 0.25) / 0.55 * Math.PI);
        int watts = (int) Math.round(PEAK_WATTS * sun * sun);
        double panelVoltage = sun > 0 ? 34 + 6 * sun : 0.4;
        double batteryVoltage = 13.1 + 0.6 * sun;
        double current = watts / batteryVoltage;
        ChargeState state = watts == 0 ? ChargeState.OFF : sun > 0.9 ? ChargeState.ABSORPTION : ChargeState.BULK;

        // Opbrengst tot nu toe vandaag: integraal van sin² over het verstreken deel van de zon.
        double sunPart = Math.min(Math.max((dayFraction - 0.25) / 0.55, 0), 1);
        double yieldKWh = PEAK_WATTS * 0.55 * 24 / 1000.0
                * (sunPart / 2 - Math.sin(2 * Math.PI * sunPart) / (4 * Math.PI));

        return Optional.of(new SolarReading(now, panelVoltage, watts, batteryVoltage, current, state,
                Math.round(yieldKWh * 100) / 100.0, sunPart > 0.5 ? PEAK_WATTS : watts, 0));
    }

    @Override
    public void close() {
        // niets op te ruimen
    }
}
