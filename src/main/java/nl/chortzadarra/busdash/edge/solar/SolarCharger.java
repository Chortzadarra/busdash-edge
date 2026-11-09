package nl.chortzadarra.busdash.edge.solar;

import java.util.Optional;

/** Bron van zonne-laadgegevens. Op de Pi de echte SmartSolar, op je laptop een simulatie. */
public interface SolarCharger extends AutoCloseable {

    void start();

    /** De laatste meting, of leeg als er nog geen of geen recente geldige data is. */
    Optional<SolarReading> latestReading();

    @Override
    void close();
}
