package nl.chortzadarra.busdash.edge.vedirect;

import com.fazecast.jSerialComm.SerialPort;

import java.util.function.Consumer;

/**
 * Leest VE.Direct-data van een seriële poort (de Victron VE.Direct-USB-kabel)
 * op een eigen achtergrondthread en geeft gevalideerde blokken door aan de listener.
 *
 * <p>De poort wordt op de leesthread geopend. Lukt dat niet (kabel nog niet aangesloten,
 * USB nog niet klaar bij het opstarten van de Pi) of valt de verbinding weg, dan wordt het
 * opnieuw geprobeerd met een oplopende wachttijd tot {@link #MAX_RECONNECT_DELAY_MS}.
 * {@link #start()} gooit dus geen fout als de poort (nog) niet bestaat.
 *
 * <p>Poortnaam: op de Pi bij voorkeur {@code /dev/serial/by-id/usb-VictronEnergy_...}, omdat
 * {@code /dev/ttyUSB0} na opnieuw insteken een ander nummer kan krijgen. Op Windows {@code COM3}
 * of iets vergelijkbaars. Gebruik {@code --list-ports} in de App om te zien wat er is.
 */
public final class VeDirectSerialSource implements BlockSource {

    private static final System.Logger LOG = System.getLogger(VeDirectSerialSource.class.getName());
    private static final int BAUD_RATE = 19_200;
    private static final long RECONNECT_DELAY_MS = 2_000;
    private static final long MAX_RECONNECT_DELAY_MS = 30_000;
    private static final long CLOSE_TIMEOUT_MS = 3_000;

    private final SerialPort port;
    private final VeDirectParser parser;
    private volatile boolean running;
    private Thread readerThread;

    public VeDirectSerialSource(String portName, Consumer<VeDirectBlock> listener) {
        this.port = SerialPort.getCommPort(portName);
        this.parser = new VeDirectParser(listener);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        port.setComPortParameters(BAUD_RATE, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1_000, 0);
        running = true;
        readerThread = new Thread(this::readLoop, "vedirect-" + port.getSystemPortName());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        byte[] buffer = new byte[256];
        long delay = RECONNECT_DELAY_MS;
        boolean openFailureLogged = false;
        while (running) {
            if (!port.isOpen()) {
                if (openIfRunning()) {
                    LOG.log(System.Logger.Level.INFO, "Verbonden met {0}", port.getSystemPortPath());
                    delay = RECONNECT_DELAY_MS;
                    openFailureLogged = false;
                } else {
                    if (!running) {
                        break;
                    }
                    // Eén keer melden, niet elke paar seconden opnieuw: anders loopt het log vol.
                    if (!openFailureLogged) {
                        LOG.log(System.Logger.Level.WARNING,
                                "Kan {0} niet openen, blijft het opnieuw proberen...", port.getSystemPortPath());
                        openFailureLogged = true;
                    }
                    sleepQuietly(delay);
                    delay = Math.min(delay * 2, MAX_RECONNECT_DELAY_MS);
                    continue;
                }
            }

            int read = port.readBytes(buffer, buffer.length);
            if (read > 0) {
                parser.feed(buffer, read);
            } else if (read < 0 && running) {
                // Fout, bijvoorbeeld kabel losgetrokken. Bij close() is running al false
                // en is -1 juist verwacht, dan dus niet opnieuw verbinden.
                LOG.log(System.Logger.Level.WARNING,
                        "Leesfout op {0}, verbinding wordt opnieuw opgebouwd", port.getSystemPortPath());
                closePortLocked();
                sleepQuietly(delay);
            }
        }
    }

    /** Opent de poort, maar alleen als {@link #close()} nog niet is aangeroepen. */
    private synchronized boolean openIfRunning() {
        return running && port.openPort();
    }

    private synchronized void closePortLocked() {
        port.closePort();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // close() maakt ons wakker; running is dan false en de lus stopt.
            Thread.currentThread().interrupt();
        }
    }

    public long validBlocks() {
        return parser.validBlocks();
    }

    public long invalidBlocks() {
        return parser.invalidBlocks();
    }

    /** Stopt de leesthread en sluit de poort. Wacht kort tot de thread echt gestopt is. */
    @Override
    public void close() {
        Thread thread;
        synchronized (this) {
            running = false;
            port.closePort(); // laat een lopende readBytes() direct terugkeren
            thread = readerThread;
            readerThread = null;
        }
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        thread.interrupt(); // haalt hem uit een eventuele wachttijd voor opnieuw verbinden
        try {
            thread.join(CLOSE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static SerialPort[] availablePorts() {
        return SerialPort.getCommPorts();
    }
}
