package nl.chortzadarra.busdash.edge.relay;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelaySettingsTest {

    private final Path file;

    RelaySettingsTest() throws IOException {
        file = Files.createTempDirectory("busdash").resolve("sub").resolve("relays.properties");
    }

    @Test
    void defaultsShowOnlyChannelOne() throws IOException {
        RelaySettings settings = RelaySettings.load(file);

        assertTrue(settings.visible(1));
        for (int channel = 2; channel <= 8; channel++) {
            assertFalse(settings.visible(channel));
        }
        assertEquals("Relais 4", settings.name(4));
    }

    @Test
    void savesAndLoadsNamesAndVisibility() throws IOException {
        RelaySettings settings = RelaySettings.load(file);
        settings.rename(1, "Koelkast ❄ ü");
        settings.setVisible(2, true);
        settings.rename(2, "Pomp");

        RelaySettings reloaded = RelaySettings.load(file);

        assertEquals("Koelkast ❄ ü", reloaded.name(1));
        assertEquals("Pomp", reloaded.name(2));
        assertTrue(reloaded.visible(2));
        assertFalse(reloaded.visible(3));
    }

    @Test
    void cleansNames() throws IOException {
        RelaySettings settings = RelaySettings.load(file);
        settings.rename(1, "  Water\npomp  ");
        assertEquals("Water pomp", settings.name(1));

        settings.rename(1, "x".repeat(100));
        assertEquals(RelaySettings.MAX_NAME_LENGTH, settings.name(1).length());

        assertThrows(IllegalArgumentException.class, () -> settings.rename(1, "   "));
        assertThrows(IllegalArgumentException.class, () -> settings.rename(9, "Te ver"));
    }
}
