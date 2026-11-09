package nl.chortzadarra.busdash.edge.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-06T12:00:00Z"));
    private final UserStore users;
    private final Auth auth;

    AuthTest() throws IOException {
        users = UserStore.load(Files.createTempDirectory("busdash").resolve("users.properties"));
        users.setPassword("jeroen", "goed-wachtwoord".toCharArray());
        auth = new Auth(users, clock);
    }

    private static List<String> cookie(String token) {
        return List.of("iets=anders; " + Auth.COOKIE + "=" + token);
    }

    @Test
    void passwordIsStoredAsHashOnly() throws IOException {
        UserStore reloaded = UserStore.load(Files.createTempDirectory("busdash").resolve("u.properties"));
        reloaded.setPassword("a", "wachtwoord1".toCharArray());
        assertTrue(reloaded.verify("a", "wachtwoord1".toCharArray()));
        assertFalse(reloaded.verify("a", "wachtwoord2".toCharArray()));
        assertFalse(reloaded.verify("b", "wachtwoord1".toCharArray()));
    }

    @Test
    void hashesDifferEachTimeButBothVerify() {
        String a = PasswordHasher.hash("zelfde".toCharArray(), 1_000);
        String b = PasswordHasher.hash("zelfde".toCharArray(), 1_000);
        assertNotEquals(a, b, "eigen salt per hash");
        assertTrue(PasswordHasher.verify("zelfde".toCharArray(), a));
        assertTrue(PasswordHasher.verify("zelfde".toCharArray(), b));
        assertFalse(PasswordHasher.verify("anders".toCharArray(), a));
        assertFalse(PasswordHasher.verify("zelfde".toCharArray(), "rommel"));
        assertFalse(PasswordHasher.verify(new char[0], a));
    }

    @Test
    void rejectsBadUserNamesAndShortPasswords() {
        assertThrows(IllegalArgumentException.class, () -> users.setPassword("met spatie", "lang-genoeg".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> users.setPassword("ok", "kort".toCharArray()));
    }

    @Test
    void loginCreatesSessionUntilLogout() {
        Auth.Login login = auth.login("jeroen", "goed-wachtwoord".toCharArray(), "1.2.3.4");
        assertEquals(Auth.Result.OK, login.result());
        assertEquals(Optional.of("jeroen"), auth.user(cookie(login.token())));

        auth.logout(cookie(login.token()));
        assertTrue(auth.user(cookie(login.token())).isEmpty());
    }

    @Test
    void unknownTokenOrNoCookieIsNotLoggedIn() {
        assertTrue(auth.user(cookie("verzonnen")).isEmpty());
        assertTrue(auth.user(null).isEmpty());
        assertTrue(auth.user(List.of("iets=anders")).isEmpty());
    }

    @Test
    void sessionExpires() {
        String token = auth.login("jeroen", "goed-wachtwoord".toCharArray(), "ip").token();
        clock.advance(Auth.SESSION_MAX_AGE.minusMinutes(1));
        assertTrue(auth.user(cookie(token)).isPresent());
        clock.advance(Duration.ofMinutes(2));
        assertTrue(auth.user(cookie(token)).isEmpty());
    }

    @Test
    void blocksAfterTooManyFailuresThenRecovers() {
        for (int i = 0; i < Auth.MAX_FAILURES; i++) {
            assertEquals(Auth.Result.WRONG, auth.login("jeroen", "fout".toCharArray(), "ip").result());
        }
        // Zelfs het goede wachtwoord werkt nu even niet...
        assertEquals(Auth.Result.BLOCKED, auth.login("jeroen", "goed-wachtwoord".toCharArray(), "ip").result());
        // ...maar een ander adres wel.
        assertEquals(Auth.Result.OK, auth.login("jeroen", "goed-wachtwoord".toCharArray(), "ander-ip").result());

        clock.advance(Auth.BLOCK_DURATION.plusSeconds(1));
        assertEquals(Auth.Result.OK, auth.login("jeroen", "goed-wachtwoord".toCharArray(), "ip").result());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
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
