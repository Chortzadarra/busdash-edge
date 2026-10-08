package nl.chortzadarra.busdash.edge.battery;

import java.util.Optional;

/**
 * Bron van accugegevens. Op de Pi is dit de echte BMV, op je laptop een simulatie.
 */
public interface BatteryMonitor extends AutoCloseable {

    void start();

    /** De laatst bekende meting, of leeg als er nog geen geldige data binnen is. */
    Optional<BatteryReading> latestReading();

    @Override
    void close();
}
