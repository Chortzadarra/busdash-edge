package nl.chortzadarra.busdash.edge.web;

import nl.chortzadarra.busdash.edge.relay.FakeRelayBoard;
import nl.chortzadarra.busdash.edge.relay.RelaySettings;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardServerTest {

    private final FakeRelayBoard board = new FakeRelayBoard();
    private final Path file;
    private final RelaySettings settings;
    private final HttpClient client = HttpClient.newHttpClient();

    DashboardServerTest() throws IOException {
        file = Files.createTempDirectory("busdash").resolve("relays.properties");
        settings = RelaySettings.load(file);
    }

    /** Start een server op een vrije poort, voert de test uit en stopt hem weer. */
    private void withServer(ServerTest test) throws Exception {
        try (DashboardServer server = new DashboardServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), board, settings)) {
            server.start();
            test.run("http://localhost:" + server.port());
        }
    }

    private interface ServerTest {
        void run(String base) throws Exception;
    }

    private HttpResponse<String> get(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "text/plain; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void servesDashboardPage() throws Exception {
        withServer(base -> {
            HttpResponse<String> res = get(base + "/");
            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("<title>BusDash</title>"));
            assertEquals(404, get(base + "/geheim.txt").statusCode());
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
            assertTrue(res.body().contains("{\"channel\":3,\"name\":\"Relais 3\",\"visible\":false,\"on\":true}"));

            post(base + "/api/relays/3/off", "");
            assertFalse(board.isOn(3));
        });
    }

    @Test
    void renamesAndPersists() throws Exception {
        withServer(base -> {
            HttpResponse<String> res = post(base + "/api/relays/1/name", "Koelkast \"groot\" ❄");
            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("\"name\":\"Koelkast \\\"groot\\\" ❄\""));
            assertEquals("Koelkast \"groot\" ❄", RelaySettings.load(file).name(1));
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
            assertEquals(405, get(base + "/api/relays/1/on").statusCode());
            assertEquals(400, post(base + "/api/relays/1/name", "x".repeat(2_000)).statusCode());
            assertFalse(board.isOn(1));
        });
    }
}
