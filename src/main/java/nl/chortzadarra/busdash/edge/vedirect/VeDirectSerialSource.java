package nl.chortzadarra.busdash.edge.vedirect;

import com.fazecast.jSerialComm.SerialPort;

import java.util.function.Consumer;

/**
 * Leest VE.Direct-data van een seriële poort (de Victron VE.Direct-USB-kabel)
 * op een eigen achtergrondthread en geeft gevalideerde blokken door aan de listener.
 *
 * <p>Poortnaam: op de Pi meestal {@code /dev/ttyUSB0}, op Windows {@code COM3} of iets
 * vergelijkbaars. Gebruik {@code --list-ports} in de App om te zien wat er is.
 */
public final class VeDirectSerialSource implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(VeDirectSerialSource.class.getName());
    private static final int BAUD_RATE = 19_200;
    private static final long RECONNECT_DELAY_MS = 2_000;

    private final SerialPort port;
    private final VeDirectParser parser;
    private volatile boolean running;
    private Thread readerThread;

    public VeDirectSerialSource(String portName, Consumer<VeDirectBlock> listener) {
        this.port = SerialPort.getCommPort(portName);
        this.parser = new VeDirectParser(listener);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        port.setComPortParameters(BAUD_RATE, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1_000, 0);
        if (!port.openPort()) {
            throw new IllegalStateException("Kan seriële poort niet openen: " + port.getSystemPortName());
        }
        running = true;
        readerThread = new Thread(this::readLoop, "vedirect-" + port.getSystemPortName());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        byte[] buffer = new byte[256];
        while (running) {
            int read = port.readBytes(buffer, buffer.length);
            if (read > 0) {
                parser.feed(buffer, read);
            } else if (read < 0) {
                // Fout, bijvoorbeeld kabel losgetrokken: rustig opnieuw proberen.
                LOG.log(System.Logger.Level.WARNING, "Leesfout op {0}, opnieuw verbinden...", port.getSystemPortName());
                port.closePort();
                sleepQuietly(RECONNECT_DELAY_MS);
                port.openPort();
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long validBlocks() {
        return parser.validBlocks();
    }

    public long invalidBlocks() {
        return parser.invalidBlocks();
    }

    @Override
    public synchronized void close() {
        running = false;
        port.closePort();
    }

    public static SerialPort[] availablePorts() {
        return SerialPort.getCommPorts();
    }
}
