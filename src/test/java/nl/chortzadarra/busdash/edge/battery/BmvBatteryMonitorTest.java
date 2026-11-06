package nl.chortzadarra.busdash.edge.battery;

import nl.chortzadarra.busdash.edge.vedirect.BlockSource;
import nl.chortzadarra.busdash.edge.vedirect.VeDirectBlock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BmvBatteryMonitorTest {

    private static final VeDirectBlock LIVE_BLOCK = new VeDirectBlock(Map.of("V", "13280", "I", "-1500"));

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-08T12:00:00Z"));
    private Consumer<VeDirectBlock> listener;
    private final BmvBatteryMonitor monitor = new BmvBatteryMonitor(l -> {
        listener = l;
        return new NoopSource();
    }, clock);

    @Test
    void emptyBeforeFirstBlock() {
        assertTrue(monitor.latestReading().isEmpty());
    }

    @Test
    void returnsFreshReading() {
        listener.accept(LIVE_BLOCK);
        clock.advance(BmvBatteryMonitor.MAX_AGE);

        assertEquals(13.28, monitor.latestReading().orElseThrow().voltage(), 1e-9);
    }

    @Test
    void becomesEmptyWhenDataIsStale() {
        listener.accept(LIVE_BLOCK);
        clock.advance(BmvBatteryMonitor.MAX_AGE.plusSeconds(1));

        assertTrue(monitor.latestReading().isEmpty());
    }

    @Test
    void recoversWhenDataResumes() {
        listener.accept(LIVE_BLOCK);
        clock.advance(Duration.ofMinutes(5));
        listener.accept(new VeDirectBlock(Map.of("V", "12900", "I", "-1500")));

        assertEquals(12.9, monitor.latestReading().orElseThrow().voltage(), 1e-9);
    }

    private static final class NoopSource implements BlockSource {
        @Override
        public void start() {
        }

        @Override
        public void close() {
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
