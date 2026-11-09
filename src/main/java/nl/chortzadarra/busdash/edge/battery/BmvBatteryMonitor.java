package nl.chortzadarra.busdash.edge.battery;

import nl.chortzadarra.busdash.edge.vedirect.BlockSource;
import nl.chortzadarra.busdash.edge.vedirect.VeDirectBlock;
import nl.chortzadarra.busdash.edge.vedirect.VeDirectSerialSource;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/** Echte BMV-712 via een VE.Direct-USB-kabel. */
public final class BmvBatteryMonitor implements BatteryMonitor {

    /**
     * De BMV stuurt elke seconde een blok. Is de laatste geldige meting ouder dan dit,
     * dan is de verbinding weg en geven we liever niets dan een verouderde waarde.
     */
    public static final Duration MAX_AGE = Duration.ofSeconds(10);

    private final BmvState state = new BmvState();
    private final AtomicReference<BatteryReading> latest = new AtomicReference<>();
    private final Clock clock;
    private final BlockSource source;

    public BmvBatteryMonitor(String portName) {
        this(listener -> new VeDirectSerialSource(portName, listener), Clock.systemUTC());
    }

    BmvBatteryMonitor(Function<Consumer<VeDirectBlock>, BlockSource> sourceFactory, Clock clock) {
        this.clock = clock;
        this.source = sourceFactory.apply(this::onBlock);
    }

    /** Wordt aangeroepen vanaf de leesthread van de seriële poort. */
    private void onBlock(VeDirectBlock block) {
        state.update(block, clock.instant()).ifPresent(latest::set);
    }

    @Override
    public void start() {
        source.start();
    }

    @Override
    public Optional<BatteryReading> latestReading() {
        BatteryReading reading = latest.get();
        if (reading == null) {
            return Optional.empty();
        }
        Duration age = Duration.between(reading.timestamp(), clock.instant());
        return age.compareTo(MAX_AGE) > 0 ? Optional.empty() : Optional.of(reading);
    }

    @Override
    public void close() {
        source.close();
    }
}
