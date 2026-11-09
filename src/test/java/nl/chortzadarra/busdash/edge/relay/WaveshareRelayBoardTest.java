package nl.chortzadarra.busdash.edge.relay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaveshareRelayBoardTest {

    private final List<FakePin> pins = new ArrayList<>();
    private final AtomicInteger shutdowns = new AtomicInteger();
    private final WaveshareRelayBoard board;

    WaveshareRelayBoardTest() {
        for (int i = 0; i < WaveshareRelayBoard.BCM_PINS.length; i++) {
            pins.add(new FakePin());
        }
        board = new WaveshareRelayBoard(List.copyOf(pins), shutdowns::incrementAndGet);
    }

    @Test
    void allRelaysOffAfterStart() {
        for (FakePin pin : pins) {
            assertTrue(pin.high, "hoog = uit bij een low-active bord");
        }
    }

    @Test
    void onPullsPinLow() {
        board.set(3, true);

        assertFalse(pins.get(2).high);
        assertTrue(board.isOn(3));
        assertFalse(board.isOn(2));
    }

    @Test
    void offPullsPinHigh() {
        board.set(3, true);
        board.set(3, false);

        assertTrue(pins.get(2).high);
        assertFalse(board.isOn(3));
    }

    @Test
    void rejectsUnknownChannel() {
        assertThrows(IllegalArgumentException.class, () -> board.set(0, true));
        assertThrows(IllegalArgumentException.class, () -> board.set(9, true));
    }

    @Test
    void closeTurnsEverythingOffOnce() {
        board.set(1, true);
        board.set(8, true);

        board.close();
        board.close();

        for (FakePin pin : pins) {
            assertTrue(pin.high);
        }
        assertEquals(1, shutdowns.get());
        assertThrows(IllegalStateException.class, () -> board.set(1, true));
    }

    @Test
    void hasEightChannelsWithDistinctPins() {
        assertEquals(8, board.channels());
        assertEquals(8, java.util.Arrays.stream(WaveshareRelayBoard.BCM_PINS).distinct().count());
    }

    private static final class FakePin implements WaveshareRelayBoard.OutputPin {
        // Begint laag: zo zie je dat de constructor de pinnen echt hoog zet.
        boolean high;

        @Override
        public void setHigh(boolean high) {
            this.high = high;
        }

        @Override
        public boolean isHigh() {
            return high;
        }
    }
}
