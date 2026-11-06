package nl.chortzadarra.busdash.edge.relay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayConsoleTest {

    private final FakeRelayBoard board = new FakeRelayBoard();
    private final RelayConsole console = new RelayConsole(board);

    @Test
    void switchesChannelOnAndOff() {
        console.handle("3 aan");
        assertTrue(board.isOn(3));

        console.handle("  3   UIT ");
        assertFalse(board.isOn(3));
    }

    @Test
    void allOff() {
        console.handle("1 aan");
        console.handle("5 aan");

        console.handle("alles uit");

        assertFalse(board.isOn(1));
        assertFalse(board.isOn(5));
    }

    @Test
    void showsStatus() {
        console.handle("2 aan");

        assertEquals("CH1=uit  CH2=AAN  CH3=uit  CH4=uit  CH5=uit  CH6=uit  CH7=uit  CH8=uit",
                console.handle("status"));
    }

    @Test
    void reportsInvalidInputWithoutSwitching() {
        assertEquals("Kanaal 9 bestaat niet, kies 1 t/m 8", console.handle("9 aan"));
        assertEquals("Onbekend kanaal: x", console.handle("x aan"));
        assertEquals(RelayConsole.HELP, console.handle("doe iets"));
        for (int channel = 1; channel <= 8; channel++) {
            assertFalse(board.isOn(channel));
        }
    }
}
