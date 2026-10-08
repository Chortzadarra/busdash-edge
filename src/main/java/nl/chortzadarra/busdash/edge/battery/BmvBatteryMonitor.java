package nl.chortzadarra.busdash.edge.battery;

import nl.chortzadarra.busdash.edge.vedirect.VeDirectBlock;
import nl.chortzadarra.busdash.edge.vedirect.VeDirectSerialSource;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Echte BMV-712 via een VE.Direct-USB-kabel. */
public final class BmvBatteryMonitor implements BatteryMonitor {

    private final BmvState state = new BmvState();
    private final AtomicReference<BatteryReading> latest = new AtomicReference<>();
    private final Clock clock;
    private final VeDirectSerialSource source;

    public BmvBatteryMonitor(String portName) {
        this(portName, Clock.systemUTC());
    }

    BmvBatteryMonitor(String portName, Clock clock) {
        this.clock = clock;
        this.source = new VeDirectSerialSource(portName, this::onBlock);
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
        return Optional.ofNullable(latest.get());
    }

    @Override
    public void close() {
        source.close();
    }
}
