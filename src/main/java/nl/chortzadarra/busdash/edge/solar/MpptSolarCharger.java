package nl.chortzadarra.busdash.edge.solar;

import nl.chortzadarra.busdash.edge.vedirect.BlockSource;
import nl.chortzadarra.busdash.edge.vedirect.VeDirectBlock;
import nl.chortzadarra.busdash.edge.vedirect.VeDirectSerialSource;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/** Echte Victron SmartSolar MPPT via een (tweede) VE.Direct-USB-kabel. */
public final class MpptSolarCharger implements SolarCharger {

    /** Net als bij de BMV: oudere data dan dit betekent dat de verbinding weg is. */
    public static final Duration MAX_AGE = Duration.ofSeconds(10);

    private final MpptState state = new MpptState();
    private final AtomicReference<SolarReading> latest = new AtomicReference<>();
    private final Clock clock;
    private final BlockSource source;

    public MpptSolarCharger(String portName) {
        this(listener -> new VeDirectSerialSource(portName, listener), Clock.systemUTC());
    }

    MpptSolarCharger(Function<Consumer<VeDirectBlock>, BlockSource> sourceFactory, Clock clock) {
        this.clock = clock;
        this.source = sourceFactory.apply(this::onBlock);
    }

    private void onBlock(VeDirectBlock block) {
        state.update(block, clock.instant()).ifPresent(latest::set);
    }

    @Override
    public void start() {
        source.start();
    }

    @Override
    public Optional<SolarReading> latestReading() {
        SolarReading reading = latest.get();
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
