package nl.chortzadarra.busdash.edge.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import nl.chortzadarra.busdash.edge.relay.RelayBoard;
import nl.chortzadarra.busdash.edge.relay.RelaySettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.StringJoiner;

/**
 * Kleine webserver voor het dashboard, met de HTTP-server die in de JDK zit (geen extra dependency).
 *
 * <pre>
 *   GET  /                          dashboardpagina
 *   GET  /api/relays                alle 8 relais: kanaal, naam, zichtbaar, aan
 *   POST /api/relays/{n}/on         relais aan
 *   POST /api/relays/{n}/off        relais uit
 *   POST /api/relays/{n}/name       nieuwe naam (body: platte tekst, UTF-8)
 *   POST /api/relays/{n}/show       relais tonen in het dashboard
 *   POST /api/relays/{n}/hide       relais verbergen (gaat ook uit)
 * </pre>
 *
 * Elke POST geeft dezelfde lijst terug als de GET, zodat de pagina meteen de nieuwe stand heeft.
 *
 * <p>Er is geen login: iedereen op hetzelfde netwerk kan schakelen. Prima op de wifi in de bus,
 * maar zet deze poort niet open naar internet.
 */
public final class DashboardServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DashboardServer.class.getName());
    private static final String API = "/api/relays";
    private static final int MAX_BODY_BYTES = 1_024;

    private final HttpServer server;
    private final RelayBoard board;
    private final RelaySettings settings;
    private final byte[] indexHtml;

    public DashboardServer(InetSocketAddress address, RelayBoard board, RelaySettings settings) throws IOException {
        this.board = board;
        this.settings = settings;
        this.indexHtml = loadIndex();
        this.server = HttpServer.create(address, 0);
        server.createContext("/", this::handlePage);
        server.createContext(API, this::handleApi);
    }

    public void start() {
        server.start();
    }

    /** De werkelijke poort, handig als je poort 0 (willekeurig vrij) hebt gevraagd. */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static byte[] loadIndex() throws IOException {
        try (InputStream in = DashboardServer.class.getResourceAsStream("/dashboard/index.html")) {
            if (in == null) {
                throw new IOException("dashboard/index.html ontbreekt in de resources");
            }
            return in.readAllBytes();
        }
    }

    private void handlePage(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/") && !path.equals("/index.html")) {
                send(exchange, 404, "text/plain; charset=utf-8", "Niet gevonden");
                return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                send(exchange, 405, "text/plain; charset=utf-8", "Alleen GET");
                return;
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            send(exchange, 200, "text/html; charset=utf-8", indexHtml);
        }
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        // Let op: de catch staat binnen de try-with-resources, anders is de exchange al dicht.
        try (exchange) {
            try {
                route(exchange);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Fout in dashboard-API", e);
                sendError(exchange, 500, "Interne fout");
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        String rest = exchange.getRequestURI().getPath().substring(API.length());
        String method = exchange.getRequestMethod();

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
        if (parts.length != 2) {
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
        handleAction(exchange, channel, parts[1]);
    }

    private void handleAction(HttpExchange exchange, int channel, String action) throws IOException {
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

    private static String readBody(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Naam is te lang");
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private synchronized String relaysJson() {
        StringJoiner relays = new StringJoiner(",", "[", "]");
        for (int channel = 1; channel <= channels(); channel++) {
            relays.add("{\"channel\":" + channel
                    + ",\"name\":" + Json.string(settings.name(channel))
                    + ",\"visible\":" + settings.visible(channel)
                    + ",\"on\":" + board.isOn(channel) + "}");
        }
        return "{\"max\":" + channels()
                + ",\"maxNameLength\":" + RelaySettings.MAX_NAME_LENGTH
                + ",\"relays\":" + relays + "}";
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", json);
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, "{\"error\":" + Json.string(message) + "}");
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        send(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
