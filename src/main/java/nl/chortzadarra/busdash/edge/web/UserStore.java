package nl.chortzadarra.busdash.edge.web;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Gebruikers en hun wachtwoordhash, bewaard in {@code users.properties}.
 * Wachtwoorden zelf worden nooit opgeslagen, alleen de hash.
 * Aanmaken of wijzigen gaat via {@code --set-password <naam>}.
 */
public final class UserStore {

    public static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final String KEY_PREFIX = "user.";

    /** Gebruikt voor onbekende gebruikers, zodat een login dan even lang duurt (geen hint via de tijd). */
    private static volatile String dummyHash;

    private final Path file;
    private final Map<String, String> hashes = new TreeMap<>();

    private UserStore(Path file) {
        this.file = file;
    }

    public static UserStore load(Path file) throws IOException {
        UserStore store = new UserStore(file);
        if (Files.exists(file)) {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            for (String key : props.stringPropertyNames()) {
                String user = key.startsWith(KEY_PREFIX) ? key.substring(KEY_PREFIX.length()) : null;
                if (user != null && NAME.matcher(user).matches()) {
                    store.hashes.put(user, props.getProperty(key));
                }
            }
        }
        return store;
    }

    public synchronized boolean isEmpty() {
        return hashes.isEmpty();
    }

    public synchronized boolean exists(String user) {
        return hashes.containsKey(user);
    }

    /**
     * Maakt een gebruiker aan of wijzigt zijn wachtwoord.
     *
     * @throws IllegalArgumentException bij een ongeldige naam of een te kort wachtwoord
     */
    public void setPassword(String user, char[] password) throws IOException {
        if (user == null || !NAME.matcher(user).matches()) {
            throw new IllegalArgumentException("Gebruikersnaam: 1 tot 32 tekens, alleen letters, cijfers, . _ of -");
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Wachtwoord moet minstens " + MIN_PASSWORD_LENGTH + " tekens zijn");
        }
        String hash = PasswordHasher.hash(password); // buiten de lock: dit duurt even
        synchronized (this) {
            hashes.put(user, hash);
            save();
        }
    }

    /** Controleert gebruikersnaam en wachtwoord. Duurt altijd ongeveer even lang. */
    public boolean verify(String user, char[] password) {
        String stored;
        synchronized (this) {
            stored = user == null ? null : hashes.get(user);
        }
        if (stored == null) {
            PasswordHasher.verify(password, dummyHash());
            return false;
        }
        return PasswordHasher.verify(password, stored);
    }

    private static String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = PasswordHasher.hash("geen-echte-gebruiker".toCharArray());
            dummyHash = hash;
        }
        return hash;
    }

    private void save() throws IOException {
        Properties props = new Properties();
        hashes.forEach((user, hash) -> props.setProperty(KEY_PREFIX + user, hash));
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "users", ".tmp");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        }
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            props.store(writer, "BusDash gebruikers (alleen wachtwoordhashes)");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
