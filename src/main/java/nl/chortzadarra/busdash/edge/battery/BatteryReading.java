package nl.chortzadarra.busdash.edge.battery;

import java.time.Instant;

/**
 * Momentopname van de huishoudaccu. Velden die de BMV (nog) niet levert zijn {@code null},
 * bijvoorbeeld de laadstatus voordat de BMV gesynchroniseerd is.
 *
 * @param voltage            accuspanning in V
 * @param current            stroom in A (negatief = ontladen)
 * @param power              vermogen in W
 * @param stateOfCharge      laadstatus in %
 * @param consumedAh         verbruikte Ah sinds vol (negatief)
 * @param timeToGoMinutes    geschatte resterende tijd; {@code null} bij laden/oneindig
 * @param temperature        accutemperatuur in °C (aux-ingang als temperatuursensor)
 * @param starterVoltage     startaccuspanning in V (aux-ingang als startaccu)
 */
public record BatteryReading(
        Instant timestamp,
        double voltage,
        double current,
        Integer power,
        Double stateOfCharge,
        Double consumedAh,
        Integer timeToGoMinutes,
        Double temperature,
        Double starterVoltage) {
}
