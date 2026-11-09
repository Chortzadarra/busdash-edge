package nl.chortzadarra.busdash.edge.solar;

import java.time.Instant;

/**
 * Momentopname van de SmartSolar MPPT. Velden die de regelaar (nog) niet levert zijn {@code null}.
 *
 * @param panelVoltage   paneelspanning in V
 * @param panelPower     paneelvermogen in W
 * @param batteryVoltage accuspanning gemeten door de regelaar, in V
 * @param batteryCurrent laadstroom naar de accu in A
 * @param chargeState    laadstatus (bulk, absorptie, druppelladen, ...)
 * @param yieldTodayKWh  opbrengst vandaag in kWh
 * @param maxPowerToday  hoogste vermogen vandaag in W
 * @param errorCode      foutcode van de regelaar, 0 = geen fout
 */
public record SolarReading(
        Instant timestamp,
        double panelVoltage,
        int panelPower,
        Double batteryVoltage,
        Double batteryCurrent,
        ChargeState chargeState,
        Double yieldTodayKWh,
        Integer maxPowerToday,
        Integer errorCode) {
}
