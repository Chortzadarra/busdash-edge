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
import nl.chortzadarra.busdash.edge.vedirect.VeDirectSerialSource;
import nl.chortzadarra.busdash.edge.web.DashboardServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;

/**
 * Stap 1: accudata uitlezen en naar de console printen, en relais met de hand schakelen.
 * Later komt hier MQTT bij in plaats van System.out.
 */
public final class App {

    private static final long PRINT_INTERVAL_MS = 2_000;
    private static final long RELAY_TEST_ON_MS = 1_000;
    private static final long RELAY_TEST_PAUSE_MS = 500;
    private static final int DEFAULT_HTTP_PORT = 8080;
    private static final Path DEFAULT_RELAY_CONFIG =
            Path.of(System.getProperty("user.home"), ".busdash", "relays.properties");

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
            default -> printUsage();
        }
    }

    /** Webdashboard om de relais te schakelen en een naam te geven. Blijft draaien tot Ctrl+C. */
    private static void dashboard(String[] args) throws IOException, InterruptedException {
        int httpPort;
        try {
            httpPort = Integer.parseInt(option(args, "--http-port", String.valueOf(DEFAULT_HTTP_PORT)));
        } catch (NumberFormatException e) {
            printUsage();
            return;
        }
        Path config = Path.of(option(args, "--config", DEFAULT_RELAY_CONFIG.toString()));
        RelaySettings settings = RelaySettings.load(config);
        RelayBoard board = relayBoard(args);

        DashboardServer server = new DashboardServer(new InetSocketAddress(httpPort), board, settings);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.printf("Dashboard draait op http://<ip-van-de-pi>:%d/ (lokaal: http://localhost:%d/)%n",
                server.port(), server.port());
        System.out.println("Relaisnamen worden bewaard in " + config.toAbsolutePath());
        Thread.currentThread().join(); // wacht tot Ctrl+C of systemctl stop
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
        boolean fake = List.of(args).contains("--fake");
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
                  --dashboard [--fake] [--http-port 8080] [--config pad]
                                       webdashboard voor de relais (telefoon en browser)
                """);
    }
}
