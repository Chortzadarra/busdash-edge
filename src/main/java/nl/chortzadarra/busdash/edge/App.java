package nl.chortzadarra.busdash.edge;

import com.fazecast.jSerialComm.SerialPort;
import nl.chortzadarra.busdash.edge.battery.BatteryMonitor;
import nl.chortzadarra.busdash.edge.battery.BatteryReading;
import nl.chortzadarra.busdash.edge.battery.BmvBatteryMonitor;
import nl.chortzadarra.busdash.edge.battery.FakeBatteryMonitor;
import nl.chortzadarra.busdash.edge.vedirect.VeDirectSerialSource;

/**
 * Stap 1: accudata uitlezen en naar de console printen.
 * Later komt hier MQTT bij in plaats van System.out.
 */
public final class App {

    private static final long PRINT_INTERVAL_MS = 2_000;

    public static void main(String[] args) throws InterruptedException {
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
            default -> printUsage();
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
                """);
    }
}
