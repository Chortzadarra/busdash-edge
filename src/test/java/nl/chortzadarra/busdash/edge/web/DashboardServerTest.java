package nl.chortzadarra.busdash.edge.web;

import nl.chortzadarra.busdash.edge.battery.FakeBatteryMonitor;
import nl.chortzadarra.busdash.edge.relay.FakeRelayBoard;
import nl.chortzadarra.busdash.edge.relay.RelaySettings;
import nl.chortzadarra.busdash.edge.solar.ChargeState;
import nl.chortzadarra.busdash.edge.solar.SolarCharger;
import nl.chortzadarra.busdash.edge.solar.SolarReading;
import nl.chortzadarra.busdash.edge.status.MoodTracker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardServerTest {

    private static final String PASSWORD = "geheim-wachtwoord";

    private final FakeRelayBoard board = new FakeRelayBoard();
    private final Path dir;
    private final RelaySettings settings;
    private final HttpClient client = HttpClient.newHttpClient(); // volgt geen redirects
    private SolarReading solarReading;

    DashboardServerTest() throws IOException {
        dir = Files.createTempDirectory("busdash");
        settings = RelaySettings.load(dir.resolve("relays.properties"));
    }

    private final SolarCharger solar = new SolarCharger() {
        @Override
        public void start() {
        }

        @Override
        public Optional<SolarReading> latestReading() {
            return Optional.ofNullable(solarReading);
        }

        @Override
        public void close() {
        }
    };

    private interface ServerTest {
        void run(String base) throws Exception;
    }

    /** Start een server op een vrije poort, zonder login. */
    private void withServer(ServerTest test) throws Exception {
        withServer(null, test);
    }

    private void withServer(Auth auth, ServerTest test) throws Exception {
        FakeBatteryMonitor battery = new FakeBatteryMonitor();
        battery.start();
        try (DashboardServer server = new DashboardServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), board, settings,
                battery, solar, new MoodTracker(100), auth)) {
            server.start();
            test.run("http://localhost:" + server.port());
        }
    }

    private Auth authWithUser() throws IOException {
        UserStore users = UserStore.load(dir.resolve("users.properties"));
        users.setPassword("jeroen", PASSWORD.toCharArray());
        return new Auth(users);
    }

    private HttpResponse<String> get(String url, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (headers.length > 0) {
            b.headers(headers);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** POST zoals de pagina dat doet: met de X-BusDash-header. */
    private HttpResponse<String> post(String url, String body, String... extraHeaders) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "text/plain; charset=utf-8")
                .header(DashboardServer.CSRF_HEADER, "1")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (extraHeaders.length > 0) {
            b.headers(extraHeaders);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> login(String base, String user, String password) throws Exception {
        String form = "user=" + java.net.URLEncoder.encode(user, StandardCharsets.UTF_8)
                + "&password=" + java.net.URLEncoder.encode(password, StandardCharsets.UTF_8);
        return client.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String sessionCookie(HttpResponse<?> res) {
        String setCookie = res.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    // ------------------------------------------------------------ zonder login

    @Test
    void servesDashboardPage() throws Exception {
        withServer(base -> {
            HttpResponse<String> res = get(base + "/");
            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("<title>BusDash</title>"));
            assertEquals("DENY", res.headers().firstValue("X-Frame-Options").orElse(""));
            assertEquals(404, get(base + "/geheim.txt").statusCode());
        });
    }

    @Test
    void statusContainsBatterySolarAndMood() throws Exception {
        solarReading = new SolarReading(Instant.now(), 38.5, 412, 13.6, 30.2, ChargeState.BULK, 1.25, 520, 0);
        withServer(base -> {
            String json = get(base + "/api/status").body();
            assertTrue(json.startsWith("{\"mood\":\"sunny\""), json);
            assertTrue(json.contains("\"panelPower\":412"), json);
            assertTrue(json.contains("\"panelVoltage\":38.5"), json);
            assertTrue(json.contains("\"stateLabel\":\"Bulk\""), json);
            assertTrue(json.contains("\"charging\":true"), json);
            assertTrue(json.contains("\"battery\":{"), json);
            assertTrue(json.contains("\"user\":null"), json);
        });
    }

    @Test
    void statusWithoutSolarDataIsAwake() throws Exception {
        withServer(base -> {
            String json = get(base + "/api/status").body();
            assertTrue(json.startsWith("{\"mood\":\"awake\""), json);
            assertTrue(json.contains("\"solar\":null"), json);
            assertTrue(json.contains("\"solarConnected\":true"), json);
        });
    }

    @Test
    void listsAllEightWithOnlyFirstVisible() throws Exception {
        withServer(base -> {
            String json = get(base + "/api/relays").body();
            assertTrue(json.startsWith("{\"max\":8,"));
            assertTrue(json.contains("{\"channel\":1,\"name\":\"Relais 1\",\"visible\":true,\"on\":false}"));
            assertTrue(json.contains("{\"channel\":8,\"name\":\"Relais 8\",\"visible\":false,\"on\":false}"));
        });
    }

    @Test
    void switchesRelayOnAndOff() throws Exception {
        withServer(base -> {
            HttpResponse<String> res = post(base + "/api/relays/3/on", "");
            assertEquals(200, res.statusCode());
            assertTrue(board.isOn(3));
            post(base + "/api/relays/3/off", "");
            assertFalse(board.isOn(3));
        });
    }

    @Test
    void postWithoutCsrfHeaderIsRefused() throws Exception {
        withServer(base -> {
            HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create(base + "/api/relays/1/on"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, res.statusCode());
            assertFalse(board.isOn(1));
        });
    }

    @Test
    void renamesAndPersists() throws Exception {
        withServer(base -> {
            HttpResponse<String> res = post(base + "/api/relays/1/name", "Koelkast \"groot\" ❄");
            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("\"name\":\"Koelkast \\\"groot\\\" ❄\""));
            assertEquals("Koelkast \"groot\" ❄", RelaySettings.load(dir.resolve("relays.properties")).name(1));
        });
    }

    @Test
    void showAndHide() throws Exception {
        withServer(base -> {
            post(base + "/api/relays/2/show", "");
            assertTrue(settings.visible(2));
            post(base + "/api/relays/2/on", "");
            post(base + "/api/relays/2/hide", "");
            assertFalse(settings.visible(2));
            assertFalse(board.isOn(2), "verborgen relais gaat uit");
        });
    }

    @Test
    void rejectsBadRequests() throws Exception {
        withServer(base -> {
            HttpResponse<String> empty = post(base + "/api/relays/1/name", "   ");
            assertEquals(400, empty.statusCode());
            assertEquals("{\"error\":\"Naam mag niet leeg zijn\"}", empty.body());
            assertEquals(404, post(base + "/api/relays/9/on", "").statusCode());
            assertEquals(404, post(base + "/api/relays/abc/on", "").statusCode());
            assertEquals(404, post(base + "/api/relays/1/ontploffen", "").statusCode());
            assertEquals(404, get(base + "/api/onbekend").statusCode());
            assertEquals(405, get(base + "/api/relays/1/on").statusCode());
            assertEquals(405, post(base + "/api/status", "").statusCode());
            assertEquals(400, post(base + "/api/relays/1/name", "x".repeat(5_000)).statusCode());
            assertFalse(board.isOn(1));
        });
    }

    // ------------------------------------------------------------ met login

    @Test
    void withoutSessionPageRedirectsAndApiRefuses() throws Exception {
        withServer(authWithUser(), base -> {
            HttpResponse<String> page = get(base + "/");
            assertEquals(303, page.statusCode());
            assertEquals("login", page.headers().firstValue("Location").orElse(""));

            assertEquals(200, get(base + "/login").statusCode());
            assertEquals(401, get(base + "/api/status").statusCode());
            assertEquals(401, post(base + "/api/relays/1/on", "").statusCode());
            assertFalse(board.isOn(1));
        });
    }

    @Test
    void loginGivesSessionThatOpensEverything() throws Exception {
        withServer(authWithUser(), base -> {
            HttpResponse<String> res = login(base, "jeroen", PASSWORD);
            assertEquals(303, res.statusCode());
            assertEquals("./", res.headers().firstValue("Location").orElse(""));
            String setCookie = res.headers().firstValue("Set-Cookie").orElseThrow();
            assertTrue(setCookie.contains("HttpOnly"));
            assertTrue(setCookie.contains("SameSite=Strict"));

            String cookie = sessionCookie(res);
            assertEquals(200, get(base + "/", "Cookie", cookie).statusCode());
            assertTrue(get(base + "/api/status", "Cookie", cookie).body().contains("\"user\":\"jeroen\""));
            assertEquals(200, post(base + "/api/relays/1/on", "", "Cookie", cookie).statusCode());
            assertTrue(board.isOn(1));

            // Ingelogd naar /login gaat terug naar het dashboard.
            assertEquals(303, get(base + "/login", "Cookie", cookie).statusCode());
        });
    }

    @Test
    void wrongPasswordGoesBackToLogin() throws Exception {
        withServer(authWithUser(), base -> {
            HttpResponse<String> res = login(base, "jeroen", "fout-wachtwoord");
            assertEquals(303, res.statusCode());
            assertEquals("login?fout=1", res.headers().firstValue("Location").orElse(""));
            assertTrue(res.headers().firstValue("Set-Cookie").isEmpty());

            HttpResponse<String> unknown = login(base, "onbekend", PASSWORD);
            assertEquals("login?fout=1", unknown.headers().firstValue("Location").orElse(""));
        });
    }

    @Test
    void logoutEndsSession() throws Exception {
        withServer(authWithUser(), base -> {
            String cookie = sessionCookie(login(base, "jeroen", PASSWORD));
            HttpResponse<String> out = post(base + "/logout", "", "Cookie", cookie);
            assertEquals(200, out.statusCode());
            assertTrue(out.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"));
            assertEquals(401, get(base + "/api/status", "Cookie", cookie).statusCode());
        });
    }

    @Test
    void secureCookieBehindHttpsProxy() throws Exception {
        withServer(authWithUser(), base -> {
            String form = "user=jeroen&password=" + PASSWORD;
            HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-Forwarded-Proto", "https")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(res.headers().firstValue("Set-Cookie").orElse("").contains("; Secure"));
        });
    }
}
