package nl.chortzadarra.busdash.edge.web;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Wachtwoorden opslaan als PBKDF2-HMAC-SHA256-hash met een willekeurige salt (zit in de JDK).
 * Formaat: {@code pbkdf2-sha256$<iteraties>$<salt>$<hash>}, beide in Base64.
 *
 * <p>Het aantal iteraties staat in de hash zelf, zodat het later verhoogd kan worden
 * zonder bestaande wachtwoorden ongeldig te maken.
 */
final class PasswordHasher {

    static final int DEFAULT_ITERATIONS = 310_000;
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    static String hash(char[] password) {
        return hash(password, DEFAULT_ITERATIONS);
    }

    static String hash(char[] password, int iterations) {
        if (password.length == 0) {
            throw new IllegalArgumentException("Wachtwoord mag niet leeg zijn");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, salt, iterations, KEY_BITS);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(key);
    }

    static boolean verify(char[] password, String stored) {
        if (password.length == 0 || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals(PREFIX)) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password, salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual); // vergelijken in constante tijd
        } catch (IllegalArgumentException e) {
            return false; // kapotte hash in het bestand
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int bits) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 niet beschikbaar", e);
        } finally {
            spec.clearPassword();
        }
    }
}
