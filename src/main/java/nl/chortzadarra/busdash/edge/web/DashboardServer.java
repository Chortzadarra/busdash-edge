package nl.chortzadarra.busdash.edge.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import nl.chortzadarra.busdash.edge.battery.BatteryMonitor;
import nl.chortzadarra.busdash.edge.battery.BatteryReading;
import nl.chortzadarra.busdash.edge.relay.RelayBoard;
import nl.chortzadarra.busdash.edge.relay.RelaySettings;
import nl.chortzadarra.busdash.edge.solar.SolarCharger;
import nl.chortzadarra.busdash.edge.solar.SolarReading;
import nl.chortzadarra.busdash.edge.status.Mood;
import nl.chortzadarra.busdash.edge.status.MoodTracker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Webserver voor het dashboard: één pagina met de stemming van de bus, de accu (BMV-712),
 * de zonnelader (SmartSolar MPPT) en de relais. Gebruikt de HTTP-server uit de JDK.
 *
 * <pre>
 *   GET  /                          dashboardpagina (na inloggen)
 *   GET  /login, POST /login        inlogpagina en -formulier
 *   POST /logout                    uitloggen
 *   GET  /api/status                stemming, accu en zon
 *   GET  /api/relays                alle 8 relais: kanaal, naam, zichtbaar, aan
 *   POST /api/relays/{n}/on|off     relais aan/uit
 *   POST /api/relays/{n}/name       nieuwe naam (body: platte tekst, UTF-8)
 *   POST /api/relays/{n}/show|hide  relais tonen/verbergen (verbergen zet hem ook uit)
 * </pre>
 *
 * <p>Beveiliging: met {@link Auth} moet je eerst inloggen; zonder (alleen voor ontwikkelen)
 * is alles open. Elke POST naar de API moet de header {@code X-BusDash: 1} meesturen. Een andere
 * website kan die header niet zetten, dus die kan niet stiekem namens jou schakelen (CSRF).
 */
public final class DashboardServer implements AutoCloseable {

    public static final String CSRF_HEADER = "X-BusDash";
    private static final System.Logger LOG = System.getLogger(DashboardServer.class.getName());
    private static final String RELAYS = "/api/relays";
    private static final int MAX_BODY_BYTES = 4_096;
    private static final String CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; form-action 'self'";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final RelayBoard board;
    private final RelaySettings settings;
    private final BatteryMonitor battery;
    private final SolarCharger solar;
    private final MoodTracker mood;
    private final Auth auth;
    private final byte[] indexHtml;
    private final byte[] loginHtml;

    /**
     * @param battery accu, of {@code null} als er geen BMV is aangesloten
     * @param solar   zonnelader, of {@code null} als er geen MPPT is aangesloten
     * @param auth    login, of {@code null} voor een open dashboard (alleen voor ontwikkelen)
     */
    public DashboardServer(InetSocketAddress address, RelayBoard board, RelaySettings settings,
                           BatteryMonitor battery, SolarCharger solar, MoodTracker mood, Auth auth)
            throws IOException {
        this.board = board;
        this.settings = settings;
        this.battery = battery;
        this.solar = solar;
        this.mood = mood;
        this.auth = auth;
        this.indexHtml = resource("/dashboard/index.html");
        this.loginHtml = resource("/dashboard/login.html");
        this.server = HttpServer.create(address, 0);
        server.setExecutor(executor); // inloggen duurt even (wachtwoordhash), dan loopt de rest door
        server.createContext("/", exchange -> handle(exchange, this::routePage));
        server.createContext("/api/", exchange -> handle(exchange, this::routeApi));
    }

    public void start() {
        server.start();
    }

    /** De werkelijke poort, handig als je poort 0 (willekeurig vrij) hebt gevraagd. */
    public int port() {
        return server.getAddress().getPort();
    }

    public boolean loginRequired() {
        return auth != null;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = DashboardServer.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException(name + " ontbreekt in de resources");
            }
            return in.readAllBytes();
        }
    }

    private interface Route {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** Gemeenschappelijk voor elk verzoek: beveiligingsheaders en nette foutafhandeling. */
    private static void handle(HttpExchange exchange, Route route) throws IOException {
        // De catch staat binnen de try-with-resources, anders is de exchange al dicht.
        try (exchange) {
            try {
                var headers = exchange.getResponseHeaders();
                headers.set("Cache-Control", "no-store");
                headers.set("X-Content-Type-Options", "nosniff");
                headers.set("X-Frame-Options", "DENY");
                headers.set("Referrer-Policy", "same-origin");
                headers.set("Content-Security-Policy", CSP);
                route.handle(exchange);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Fout in dashboard", e);
                sendError(exchange, 500, "Interne fout");
            }
        }
    }

    // ---------------------------------------------------------------- pagina's en login

    private void routePage(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        switch (path) {
            case "/", "/index.html" -> {
                if (!method.equals("GET")) {
                    sendText(exchange, 405, "Alleen GET");
                } else if (currentUser(exchange).isEmpty()) {
                    redirect(exchange, "login");
                } else {
                    send(exchange, 200, "text/html; charset=utf-8", indexHtml);
                }
            }
            case "/login" -> {
                if (auth == null || currentUser(exchange).isPresent()) {
                    redirect(exchange, "./");
                } else if (method.equals("GET")) {
                    send(exchange, 200, "text/html; charset=utf-8", loginHtml);
                } else if (method.equals("POST")) {
                    handleLogin(exchange);
                } else {
                    sendText(exchange, 405, "Gebruik GET of POST");
                }
            }
            case "/logout" -> {
                if (!method.equals("POST") || !hasCsrfHeader(exchange)) {
                    sendError(exchange, 403, "Uitloggen kan alleen vanuit het dashboard");
                    return;
                }
                if (auth != null) {
                    auth.logout(exchange.getRequestHeaders().get("Cookie"));
                }
                exchange.getResponseHeaders().add("Set-Cookie", cookie(exchange, "", 0));
                sendJson(exchange, 200, "{\"ok\":true}");
            }
            default -> sendText(exchange, 404, "Niet gevonden");
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseForm(readBody(exchange));
        String user = form.getOrDefault("user", "").strip();
        char[] password = form.getOrDefault("password", "").toCharArray();
        String client = exchange.getRemoteAddress().getAddress().getHostAddress();

        Auth.Login login = auth.login(user, password, client);
        java.util.Arrays.fill(password, '\0');
        switch (login.result()) {
            case OK -> {
                LOG.log(System.Logger.Level.INFO, "{0} is ingelogd vanaf {1}", user, client);
                exchange.getResponseHeaders().add("Set-Cookie",
                        cookie(exchange, login.token(), Auth.SESSION_MAX_AGE.toSeconds()));
                redirect(exchange, "./");
            }
            case BLOCKED -> redirect(exchange, "login?fout=geblokkeerd");
            case WRONG -> {
                LOG.log(System.Logger.Level.WARNING, "Mislukte login voor {0} vanaf {1}", user, client);
                redirect(exchange, "login?fout=1");
            }
        }
    }

    /** Sessiecookie. Secure als het verzoek via HTTPS binnenkwam (bijv. achter een reverse proxy). */
    private static String cookie(HttpExchange exchange, String value, long maxAgeSeconds) {
        String proto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        boolean https = "https".equalsIgnoreCase(proto);
        return Auth.COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAgeSeconds
                + (https ? "; Secure" : "");
    }

    private Optional<String> currentUser(HttpExchange exchange) {
        if (auth == null) {
            return Optional.of("");
        }
        return auth.user(exchange.getRequestHeaders().get("Cookie"));
    }

    private static boolean hasCsrfHeader(HttpExchange exchange) {
        return "1".equals(exchange.getRequestHeaders().getFirst(CSRF_HEADER));
    }

    // ---------------------------------------------------------------- API

    private void routeApi(HttpExchange exchange) throws IOException {
        Optional<String> user = currentUser(exchange);
        if (user.isEmpty()) {
            sendError(exchange, 401, "Niet ingelogd");
            return;
        }
        String method = exchange.getRequestMethod();
        if (method.equals("POST") && !hasCsrfHeader(exchange)) {
            sendError(exchange, 403, "Header " + CSRF_HEADER + " ontbreekt");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/status")) {
            if (!method.equals("GET")) {
                sendError(exchange, 405, "Gebruik GET");
                return;
            }
            sendJson(exchange, 200, statusJson(user.get()));
        } else if (path.startsWith(RELAYS)) {
            routeRelays(exchange, path.substring(RELAYS.length()), method);
        } else {
            sendError(exchange, 404, "Onbekend adres");
        }
    }

    private String statusJson(String user) {
        Optional<BatteryReading> b = battery == null ? Optional.empty() : battery.latestReading();
        Optional<SolarReading> s = solar == null ? Optional.empty() : solar.latestReading();
        Mood current = mood.update(s);
        return Json.object()
                .put("mood", current.id())
                .put("sunnyWatts", mood.sunnyWatts())
                .put("user", auth == null ? null : user)
                .put("batteryConnected", battery != null)
                .put("solarConnected", solar != null)
                .put("battery", b.map(DashboardServer::batteryJson).orElse(null))
                .put("solar", s.map(DashboardServer::solarJson).orElse(null))
                .toString();
    }

    private static Json.Obj batteryJson(BatteryReading r) {
        return Json.object()
                .put("ageSeconds", age(r.timestamp()))
                .put("voltage", r.voltage())
                .put("current", r.current())
                .put("power", r.power())
                .put("soc", r.stateOfCharge())
                .put("consumedAh", r.consumedAh())
                .put("timeToGoMinutes", r.timeToGoMinutes())
                .put("temperature", r.temperature())
                .put("starterVoltage", r.starterVoltage());
    }

    private static Json.Obj solarJson(SolarReading r) {
        return Json.object()
                .put("ageSeconds", age(r.timestamp()))
                .put("panelVoltage", r.panelVoltage())
                .put("panelPower", r.panelPower())
                .put("batteryVoltage", r.batteryVoltage())
                .put("batteryCurrent", r.batteryCurrent())
                .put("state", r.chargeState() == null ? null : r.chargeState().name().toLowerCase(java.util.Locale.ROOT))
                .put("stateLabel", r.chargeState() == null ? null : r.chargeState().label())
                .put("charging", r.chargeState() != null && r.chargeState().charging())
                .put("yieldTodayKWh", r.yieldTodayKWh())
                .put("maxPowerToday", r.maxPowerToday())
                .put("errorCode", r.errorCode());
    }

    private static long age(Instant timestamp) {
        return Math.max(0, Duration.between(timestamp, Instant.now()).toSeconds());
    }

    private void routeRelays(HttpExchange exchange, String rest, String method) throws IOException {
        if (rest.isEmpty() || rest.equals("/")) {
            if (!method.equals("GET")) {
                sendError(exchange, 405, "Gebruik GET");
                return;
            }
            sendJson(exchange, 200, relaysJson());
            return;
        }
        // Verwacht /{kanaal}/{actie}
        String[] parts = rest.substring(1).split("/");
        if (!rest.startsWith("/") || parts.length != 2) {
            sendError(exchange, 404, "Onbekend adres");
            return;
        }
        int channel;
        try {
            channel = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            sendError(exchange, 404, "Onbekend kanaal");
            return;
        }
        if (channel < 1 || channel > channels()) {
            sendError(exchange, 404, "Kanaal " + channel + " bestaat niet, kies 1 t/m " + channels());
            return;
        }
        if (!method.equals("POST")) {
            sendError(exchange, 405, "Gebruik POST");
            return;
        }
        handleRelayAction(exchange, channel, parts[1]);
    }

    private void handleRelayAction(HttpExchange exchange, int channel, String action) throws IOException {
        // Eén verzoek tegelijk, zodat board en instellingen bij elkaar blijven passen.
        synchronized (this) {
            try {
                switch (action) {
                    case "on" -> board.set(channel, true);
                    case "off" -> board.set(channel, false);
                    case "name" -> settings.rename(channel, readBody(exchange));
                    case "show" -> settings.setVisible(channel, true);
                    case "hide" -> {
                        board.set(channel, false);
                        settings.setVisible(channel, false);
                    }
                    default -> {
                        sendError(exchange, 404, "Onbekende actie: " + action);
                        return;
                    }
                }
            } catch (IllegalArgumentException e) {
                sendError(exchange, 400, e.getMessage());
                return;
            } catch (IOException e) {
                LOG.log(System.Logger.Level.ERROR, "Opslaan van relaisinstellingen mislukt", e);
                sendError(exchange, 500, "Opslaan mislukt");
                return;
            }
            sendJson(exchange, 200, relaysJson());
        }
    }

    private int channels() {
        return Math.min(board.channels(), RelaySettings.CHANNELS);
    }

    private synchronized String relaysJson() {
        StringJoiner relays = new StringJoiner(",", "[", "]");
        for (int channel = 1; channel <= channels(); channel++) {
            relays.add(Json.object()
                    .put("channel", channel)
                    .put("name", settings.name(channel))
                    .put("visible", settings.visible(channel))
                    .put("on", board.isOn(channel))
                    .toString());
        }
        return "{\"max\":" + channels()
                + ",\"maxNameLength\":" + RelaySettings.MAX_NAME_LENGTH
                + ",\"relays\":" + relays + "}";
    }

    // ---------------------------------------------------------------- hulpjes

    private static String readBody(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Te veel gegevens");
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, "{\"error\":" + Json.string(message) + "}");
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
