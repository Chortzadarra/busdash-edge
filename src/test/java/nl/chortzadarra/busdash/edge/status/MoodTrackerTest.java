package nl.chortzadarra.busdash.edge.status;

import nl.chortzadarra.busdash.edge.solar.ChargeState;
import nl.chortzadarra.busdash.edge.solar.FakeSolarCharger;
import nl.chortzadarra.busdash.edge.solar.SolarReading;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoodTrackerTest {

    private final MoodTracker tracker = new MoodTracker(100);

    private static Optional<SolarReading> solar(int watts, double panelVoltage) {
        return Optional.of(new SolarReading(Instant.EPOCH, panelVoltage, watts, 13.2, watts / 13.2,
                watts > 0 ? ChargeState.BULK : ChargeState.OFF, 0.0, watts, 0));
    }

    @Test
    void awakeWithoutData() {
        assertEquals(Mood.AWAKE, tracker.update(Optional.empty()));
    }

    @Test
    void sunnyWhenChargingHard() {
        assertEquals(Mood.SUNNY, tracker.update(solar(350, 38)));
    }

    @Test
    void awakeWithALittleSun() {
        assertEquals(Mood.AWAKE, tracker.update(solar(40, 30)));
    }

    @Test
    void sleepingAtNight() {
        assertEquals(Mood.SLEEPING, tracker.update(solar(0, 0.6)));
    }

    @Test
    void cloudyEveningWithPanelVoltageIsStillAwake() {
        assertEquals(Mood.AWAKE, tracker.update(solar(0, 16)));
    }

    @Test
    void hysteresisAroundSunnyThreshold() {
        tracker.update(solar(120, 38));
        assertEquals(Mood.SUNNY, tracker.update(solar(85, 37)), "kleine dip: zonnebril blijft op");
        assertEquals(Mood.AWAKE, tracker.update(solar(60, 36)), "echt minder zon: bril af");
        assertEquals(Mood.AWAKE, tracker.update(solar(95, 37)), "nog net niet genoeg om hem weer op te zetten");
        assertEquals(Mood.SUNNY, tracker.update(solar(100, 38)));
    }

    @Test
    void wakesUpAtDawn() {
        tracker.update(solar(0, 0.4));
        assertEquals(Mood.SLEEPING, tracker.update(solar(0, 6)), "beetje spanning: nog even doorslapen");
        assertEquals(Mood.AWAKE, tracker.update(solar(0, 9)));
    }

    @Test
    void fakeChargerShowsAllMoodsDuringOneDay() {
        var clock = new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);
        java.time.Clock c = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return clock.get(); }
        };
        FakeSolarCharger fake = new FakeSolarCharger(c, java.time.Duration.ofMinutes(3));
        fake.start();
        java.util.Set<Mood> seen = java.util.EnumSet.noneOf(Mood.class);
        for (int s = 0; s < 180; s++) {
            clock.set(Instant.EPOCH.plusSeconds(s));
            seen.add(tracker.update(fake.latestReading()));
        }
        assertEquals(java.util.EnumSet.allOf(Mood.class), seen);
    }
}
