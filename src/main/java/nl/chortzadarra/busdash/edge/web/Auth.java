package nl.chortzadarra.busdash.edge.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inloggen en sessies voor het dashboard.
 *
 * <ul>
 *   <li>Na inloggen krijgt de browser een willekeurige sessiecode in een cookie
 *       (HttpOnly, SameSite=Strict). Sessies leven {@link #SESSION_MAX_AGE} en staan alleen in het
 *       geheugen: na een herstart van de app opnieuw inloggen.</li>
 *   <li>Na {@link #MAX_FAILURES} foute pogingen binnen {@link #FAILURE_WINDOW} wordt dat adres
 *       {@link #BLOCK_DURATION} geblokkeerd, tegen het raden van wachtwoorden.</li>
 * </ul>
 */
public final class Auth {

    public static final String COOKIE = "busdash_session";
    public static final Duration SESSION_MAX_AGE = Duration.ofDays(30);
    static final int MAX_FAILURES = 10;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final Duration BLOCK_DURATION = Duration.ofMinutes(5);

    public enum Result { OK, WRONG, BLOCKED }

    /** Uitkomst van een inlogpoging; {@code token} is alleen gevuld bij {@link Result#OK}. */
    public record Login(Result result, String token) {
    }

    private record Session(String user, Instant expires) {
    }

    private static final class Failures {
        int count;
        Instant first;
        Instant blockedUntil;
    }

    private final UserStore users;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public Auth(UserStore users) {
        this(users, Clock.systemUTC());
    }

    Auth(UserStore users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    /** @param clientKey wie er inlogt (IP-adres), voor de blokkade na foute pogingen */
    public Login login(String user, char[] password, String clientKey) {
        if (isBlocked(clientKey)) {
            return new Login(Result.BLOCKED, null);
        }
        if (!users.verify(user, password)) {
            recordFailure(clientKey);
            return new Login(Result.WRONG, null);
        }
        failures.remove(clientKey);
        removeExpiredSessions();
        String token = newToken();
        sessions.put(token, new Session(user, clock.instant().plus(SESSION_MAX_AGE)));
        return new Login(Result.OK, token);
    }

    /** De ingelogde gebruiker bij deze Cookie-headers, als de sessie geldig is. */
    public Optional<String> user(List<String> cookieHeaders) {
        return token(cookieHeaders).flatMap(token -> {
            Session session = sessions.get(token);
            if (session == null) {
                return Optional.empty();
            }
            if (!clock.instant().isBefore(session.expires())) {
                sessions.remove(token);
                return Optional.empty();
            }
            return Optional.of(session.user());
        });
    }

    public void logout(List<String> cookieHeaders) {
        token(cookieHeaders).ifPresent(sessions::remove);
    }

    static Optional<String> token(List<String> cookieHeaders) {
        if (cookieHeaders == null) {
            return Optional.empty();
        }
        for (String header : cookieHeaders) {
            for (String part : header.split(";")) {
                String cookie = part.strip();
                if (cookie.startsWith(COOKIE + "=")) {
                    String value = cookie.substring(COOKIE.length() + 1);
                    if (!value.isEmpty()) {
                        return Optional.of(value);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private synchronized boolean isBlocked(String clientKey) {
        Failures f = failures.get(clientKey);
        return f != null && f.blockedUntil != null && clock.instant().isBefore(f.blockedUntil);
    }

    private synchronized void recordFailure(String clientKey) {
        Instant now = clock.instant();
        Failures f = failures.computeIfAbsent(clientKey, k -> new Failures());
        if (f.first == null || now.isAfter(f.first.plus(FAILURE_WINDOW))
                || (f.blockedUntil != null && !now.isBefore(f.blockedUntil))) {
            f.count = 0;
            f.first = now;
            f.blockedUntil = null;
        }
        f.count++;
        if (f.count >= MAX_FAILURES) {
            f.blockedUntil = now.plus(BLOCK_DURATION);
        }
    }

    private void removeExpiredSessions() {
        Instant now = clock.instant();
        sessions.values().removeIf(s -> !now.isBefore(s.expires()));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
