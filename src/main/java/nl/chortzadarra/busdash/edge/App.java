package nl.chortzadarra.busdash.edge;

import com.fazecast.jSerialComm.SerialPort;
import nl.chortzadarra.busdash.edge.battery.BatteryMonitor;
import nl.chortzadarra.busdash.edge.battery.BatteryReading;
import nl.chortzadarra.busdash.edge.battery.BmvBatteryMonitor;
import nl.chortzadarra.busdash.edge.battery.FakeBatteryMonitor;
import nl.chortzadarra.busdash.edge.relay.FakeRelayBoard;
import nl.chortzadarra.busdash.edge.relay.RelayBoard;
import nl.chortzadarra.busdash.edge.relay.RelayConsole;
import nl.chortzadarra.busdash.edge.relay.RelaySettings;
import nl.chortzadarra.busdash.edge.relay.WaveshareRelayBoard;
import nl.chortzadarra.busdash.edge.solar.FakeSolarCharger;
import nl.chortzadarra.busdash.edge.solar.MpptSolarCharger;
import nl.chortzadarra.busdash.edge.solar.SolarCharger;
import nl.chortzadarra.busdash.edge.status.MoodTracker;
import nl.chortzadarra.busdash.edge.vedirect.VeDirectSerialSource;
import nl.chortzadarra.busdash.edge.web.Auth;
import nl.chortzadarra.busdash.edge.web.DashboardServer;
import nl.chortzadarra.busdash.edge.web.UserStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;

/**
 * Startpunt: het dashboard ({@code --dashboard}) met accu, zon, relais en login, plus losse
 * hulpmiddelen om hardware te testen ({@code --port}, {@code --relay-test}, {@code --relay-console}).
 */
public final class App {

    private static final long PRINT_INTERVAL_MS = 2_000;
    private static final long RELAY_TEST_ON_MS = 1_000;
    private static final long RELAY_TEST_PAUSE_MS = 500;
    private static final int DEFAULT_HTTP_PORT = 8080;
    private static final Path DEFAULT_DATA_DIR = Path.of(System.getProperty("user.home"), ".busdash");
    private static final String RELAYS_FILE = "relays.properties";
    private static final String USERS_FILE = "users.properties";

    public static void main(String[] args) throws InterruptedException, IOException {
        if (args.length == 0) {
            printUsage();
            return;
        }
        switch (args[0]) {
            case "--list-ports" -> listPorts();
            case "--fake" -> run(new FakeBatteryMonitor());
            case "--port" -> {
                if (args.length < 2) {
                    printUsage();
                    return;
                }
                run(new BmvBatteryMonitor(args[1]));
            }
            case "--relay-test" -> relayTest(relayBoard(args));
            case "--relay-console" -> relayConsole(relayBoard(args));
            case "--dashboard" -> dashboard(args);
            case "--set-password" -> setPassword(args);
            default -> printUsage();
        }
    }

    /**
     * Het complete dashboard op één pagina: stemming van de bus, accu (BMV), zon (MPPT) en relais.
     * Blijft draaien tot Ctrl+C of {@code systemctl stop}.
     */
    private static void dashboard(String[] args) throws IOException, InterruptedException {
        boolean fake = flag(args, "--fake");
        int httpPort;
        int sunnyWatts;
        try {
            httpPort = Integer.parseInt(option(args, "--http-port", String.valueOf(DEFAULT_HTTP_PORT)));
            sunnyWatts = Integer.parseInt(option(args, "--sunny-watts", String.valueOf(MoodTracker.DEFAULT_SUNNY_WATTS)));
        } catch (NumberFormatException e) {
            printUsage();
            return;
        }
        Path dataDir = dataDir(args);

        Auth auth = null;
        if (!flag(args, "--no-login")) {
            UserStore users = UserStore.load(dataDir.resolve(USERS_FILE));
            if (users.isEmpty()) {
                System.err.println("""
                        Er is nog geen gebruiker om mee in te loggen. Maak er eerst een aan:
                          java -jar busdash-edge.jar --set-password <naam>
                        (Alleen om te ontwikkelen kun je --no-login gebruiken.)""");
                System.exit(1);
            }
            auth = new Auth(users);
        } else {
            System.out.println("LET OP: dashboard zonder login, iedereen op het netwerk kan schakelen.");
        }

        RelaySettings settings = RelaySettings.load(dataDir.resolve(RELAYS_FILE));
        RelayBoard board = relayBoard(args);
        BatteryMonitor battery = fake ? new FakeBatteryMonitor() : optionalDevice(args, "--bmv-port", BmvBatteryMonitor::new);
        SolarCharger solar = fake ? new FakeSolarCharger() : optionalDevice(args, "--mppt-port", MpptSolarCharger::new);
        if (battery != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(battery::close));
            battery.start();
        }
        if (solar != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(solar::close));
            solar.start();
        }

        DashboardServer server = new DashboardServer(new InetSocketAddress(httpPort), board, settings,
                battery, solar, new MoodTracker(sunnyWatts), auth);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.printf("Dashboard draait op http://<ip-van-de-pi>:%d/ (lokaal: http://localhost:%d/)%n",
                server.port(), server.port());
        System.out.println("Accu: " + (battery == null ? "niet aangesloten (gebruik --bmv-port)" : "ja")
                + ", zon: " + (solar == null ? "niet aangesloten (gebruik --mppt-port)" : "ja"));
        System.out.println("Instellingen in " + dataDir.toAbsolutePath());
        Thread.currentThread().join(); // wacht tot Ctrl+C of systemctl stop
    }

    /** Maakt een gebruiker aan of wijzigt het wachtwoord. Het wachtwoord wordt twee keer gevraagd. */
    private static void setPassword(String[] args) throws IOException {
        if (args.length < 2 || args[1].startsWith("--")) {
            printUsage();
            return;
        }
        String user = args[1];
        UserStore users = UserStore.load(dataDir(args).resolve(USERS_FILE));
        char[] first = readPassword("Nieuw wachtwoord voor " + user + ": ");
        char[] second = readPassword("Nogmaals: ");
        try {
            if (first == null || second == null || !java.util.Arrays.equals(first, second)) {
                System.err.println("Wachtwoorden komen niet overeen, er is niets gewijzigd.");
                System.exit(1);
            }
            users.setPassword(user, first);
            System.out.println("Wachtwoord voor " + user + " opgeslagen.");
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } finally {
            if (first != null) java.util.Arrays.fill(first, '\0');
            if (second != null) java.util.Arrays.fill(second, '\0');
        }
    }

    /** Wachtwoord lezen zonder het te tonen; in een IDE zonder console gewoon een regel lezen. */
    private static char[] readPassword(String prompt) throws IOException {
        java.io.Console console = System.console();
        if (console != null) {
            return console.readPassword("%s", prompt);
        }
        System.out.print(prompt);
        String line = STDIN.readLine();
        return line == null ? null : line.toCharArray();
    }

    private static final BufferedReader STDIN = new BufferedReader(new InputStreamReader(System.in));

    private static Path dataDir(String[] args) {
        return Path.of(option(args, "--data-dir", DEFAULT_DATA_DIR.toString()));
    }

    private static <T> T optionalDevice(String[] args, String option, java.util.function.Function<String, T> factory) {
        String port = option(args, option, null);
        return port == null ? null : factory.apply(port);
    }

    private static boolean flag(String[] args, String name) {
        return List.of(args).contains(name);
    }

    /** Waarde na een optie, bijv. {@code --http-port 8080}, of de standaardwaarde. */
    private static String option(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    /** Echt bord, of een simulatie als {@code --fake} is meegegeven. */
    private static RelayBoard relayBoard(String[] args) {
        boolean fake = flag(args, "--fake");
        RelayBoard board = fake ? new FakeRelayBoard() : new WaveshareRelayBoard();
        // Ook bij Ctrl+C alle relais uit.
        Runtime.getRuntime().addShutdownHook(new Thread(board::close));
        return board;
    }

    /** Zet de relais één voor één kort aan, om te controleren of kanaal en pin kloppen. */
    private static void relayTest(RelayBoard board) throws InterruptedException {
        try (board) {
            for (int channel = 1; channel <= board.channels(); channel++) {
                System.out.printf("CH%d aan...%n", channel);
                board.set(channel, true);
                Thread.sleep(RELAY_TEST_ON_MS);
                board.set(channel, false);
                Thread.sleep(RELAY_TEST_PAUSE_MS);
            }
            System.out.println("Klaar, alle relais uit.");
        }
    }

    /** Relais met de hand schakelen. Bij "stop", Ctrl+C of einde invoer gaan alle relais uit. */
    private static void relayConsole(RelayBoard board) throws IOException {
        try (board) {
            RelayConsole console = new RelayConsole(board);
            System.out.println(RelayConsole.HELP);
            System.out.println(console.status());
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = in.readLine()) != null && !line.trim().equalsIgnoreCase("stop")) {
                if (!line.isBlank()) {
                    System.out.println(console.handle(line));
                }
            }
        }
    }

    private static void run(BatteryMonitor monitor) throws InterruptedException {
        Runtime.getRuntime().addShutdownHook(new Thread(monitor::close));
        monitor.start();
        while (true) {
            monitor.latestReading().ifPresentOrElse(
                    r -> System.out.println(format(r)),
                    () -> System.out.println("Geen actuele data (wachten op BMV)..."));
            Thread.sleep(PRINT_INTERVAL_MS);
        }
    }

    private static String format(BatteryReading r) {
        return String.format("%tT | %.2f V | %+.2f A | SoC %s | Temp %s | Start %s",
                java.util.Date.from(r.timestamp()),
                r.voltage(),
                r.current(),
                r.stateOfCharge() == null ? "--" : String.format("%.1f%%", r.stateOfCharge()),
                r.temperature() == null ? "--" : String.format("%.0f°C", r.temperature()),
                r.starterVoltage() == null ? "--" : String.format("%.2f V", r.starterVoltage()));
    }

    private static void listPorts() {
        SerialPort[] ports = VeDirectSerialSource.availablePorts();
        if (ports.length == 0) {
            System.out.println("Geen seriële poorten gevonden.");
        }
        for (SerialPort port : ports) {
            System.out.printf("%s  (%s)%n", port.getSystemPortPath(), port.getDescriptivePortName());
        }
    }

    private static void printUsage() {
        System.out.println("""
                Gebruik:
                  --fake               gesimuleerde accu (ontwikkelen op je laptop)
                  --port <poort>       echte BMV, bijv. /dev/ttyUSB0 of COM3
                  --list-ports         toon beschikbare seriële poorten
                  --relay-test [--fake]     zet CH1..CH8 één voor één kort aan
                  --relay-console [--fake]  relais met de hand schakelen
                  --dashboard [opties]  webdashboard: bus, accu, zon en relais (telefoon en browser)
                      --bmv-port <poort>    BMV-712 (accu)
                      --mppt-port <poort>   SmartSolar MPPT (zon)
                      --fake                alles gesimuleerd, voor op je laptop
                      --http-port 8080      poort van de webserver
                      --sunny-watts 100     vanaf dit paneelvermogen gaat de zonnebril op
                      --no-login            zonder login (alleen om te ontwikkelen)
                  --set-password <naam>     gebruiker aanmaken of wachtwoord wijzigen
                  Bij --dashboard en --set-password: --data-dir <map> (standaard ~/.busdash)
                """);
    }
}
