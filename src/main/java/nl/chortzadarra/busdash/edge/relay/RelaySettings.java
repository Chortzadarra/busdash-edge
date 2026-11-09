package nl.chortzadarra.busdash.edge.relay;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Naam en zichtbaarheid per relais, bewaard in een properties-bestand zodat ze een herstart
 * overleven. Of een relais aan of uit staat wordt bewust <b>niet</b> bewaard: na een herstart
 * staat alles uit.
 *
 * <p>Standaard is alleen relais 1 zichtbaar; met "+" in het dashboard komen er meer bij.
 */
public final class RelaySettings {

    public static final int CHANNELS = 8;
    public static final int MAX_NAME_LENGTH = 40;

    private final Path file;
    private final String[] names = new String[CHANNELS];
    private final boolean[] visible = new boolean[CHANNELS];

    private RelaySettings(Path file) {
        this.file = file;
        for (int i = 0; i < CHANNELS; i++) {
            names[i] = defaultName(i + 1);
        }
        visible[0] = true;
    }

    /** Leest de instellingen, of begint met de standaard als het bestand nog niet bestaat. */
    public static RelaySettings load(Path file) throws IOException {
        RelaySettings settings = new RelaySettings(file);
        if (Files.exists(file)) {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            for (int channel = 1; channel <= CHANNELS; channel++) {
                int i = channel - 1;
                String name = props.getProperty("relay." + channel + ".name");
                if (name != null && !name.isBlank()) {
                    settings.names[i] = cleanName(name);
                }
                String vis = props.getProperty("relay." + channel + ".visible");
                if (vis != null) {
                    settings.visible[i] = Boolean.parseBoolean(vis);
                }
            }
        }
        return settings;
    }

    public static String defaultName(int channel) {
        return "Relais " + channel;
    }

    public synchronized String name(int channel) {
        return names[Channels.index(channel, CHANNELS)];
    }

    public synchronized boolean visible(int channel) {
        return visible[Channels.index(channel, CHANNELS)];
    }

    /**
     * Geeft een relais een nieuwe naam.
     *
     * @throws IllegalArgumentException bij een lege naam
     */
    public synchronized void rename(int channel, String name) throws IOException {
        String cleaned = cleanName(name);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Naam mag niet leeg zijn");
        }
        names[Channels.index(channel, CHANNELS)] = cleaned;
        save();
    }

    public synchronized void setVisible(int channel, boolean show) throws IOException {
        visible[Channels.index(channel, CHANNELS)] = show;
        save();
    }

    /** Spaties aan de randen weg, geen stuurtekens, maximaal {@link #MAX_NAME_LENGTH} tekens. */
    static String cleanName(String name) {
        String cleaned = name.replaceAll("\\p{Cntrl}", " ").strip();
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_NAME_LENGTH)).strip();
        }
        return cleaned;
    }

    /** Schrijft eerst naar een tijdelijk bestand, zodat een stroomstoring geen half bestand achterlaat. */
    private void save() throws IOException {
        Properties props = new Properties();
        for (int channel = 1; channel <= CHANNELS; channel++) {
            props.setProperty("relay." + channel + ".name", names[channel - 1]);
            props.setProperty("relay." + channel + ".visible", Boolean.toString(visible[channel - 1]));
        }
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "relays", ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            props.store(writer, "BusDash relais: namen en zichtbaarheid");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
