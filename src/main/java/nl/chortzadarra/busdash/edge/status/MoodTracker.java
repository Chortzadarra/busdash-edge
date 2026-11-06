package nl.chortzadarra.busdash.edge.status;

import nl.chortzadarra.busdash.edge.solar.SolarReading;

import java.util.Optional;

/**
 * Bepaalt de stemming van de bus uit de zonnedata, met hysterese: rond een grens
 * (bijvoorbeeld een wolkje bij 100 W) springt hij niet steeds heen en weer.
 *
 * <ul>
 *   <li><b>Zon</b> vanaf {@code sunnyWatts} paneelvermogen; pas terug onder 70% daarvan.</li>
 *   <li><b>Slapen</b> als het paneel 0 W levert én de paneelspanning onder
 *       {@value #NIGHT_PANEL_VOLTAGE} V zakt (nacht); wakker zodra er weer vermogen is of de
 *       spanning boven {@value #WAKE_PANEL_VOLTAGE} V komt (ochtendschemering).</li>
 *   <li><b>Wakker</b> in alle andere gevallen, ook als er geen zonnedata is.</li>
 * </ul>
 */
public final class MoodTracker {

    public static final int DEFAULT_SUNNY_WATTS = 100;
    static final double NIGHT_PANEL_VOLTAGE = 5.0;
    static final double WAKE_PANEL_VOLTAGE = 8.0;
    private static final double SUNNY_EXIT_FACTOR = 0.7;

    private final int sunnyWatts;
    private Mood current = Mood.AWAKE;

    public MoodTracker() {
        this(DEFAULT_SUNNY_WATTS);
    }

    public MoodTracker(int sunnyWatts) {
        if (sunnyWatts <= 0) {
            throw new IllegalArgumentException("sunnyWatts moet groter dan 0 zijn");
        }
        this.sunnyWatts = sunnyWatts;
    }

    public synchronized Mood update(Optional<SolarReading> solar) {
        if (solar.isEmpty()) {
            current = Mood.AWAKE;
            return current;
        }
        int watts = solar.get().panelPower();
        double panelVoltage = solar.get().panelVoltage();
        boolean night = watts == 0 && panelVoltage < NIGHT_PANEL_VOLTAGE;

        current = switch (current) {
            case SUNNY -> watts >= sunnyWatts * SUNNY_EXIT_FACTOR ? Mood.SUNNY : night ? Mood.SLEEPING : Mood.AWAKE;
            case SLEEPING -> {
                if (watts >= sunnyWatts) {
                    yield Mood.SUNNY;
                }
                yield watts > 0 || panelVoltage >= WAKE_PANEL_VOLTAGE ? Mood.AWAKE : Mood.SLEEPING;
            }
            case AWAKE -> watts >= sunnyWatts ? Mood.SUNNY : night ? Mood.SLEEPING : Mood.AWAKE;
        };
        return current;
    }

    public int sunnyWatts() {
        return sunnyWatts;
    }
}
