package nl.chortzadarra.busdash.edge.relay;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;

import java.util.ArrayList;
import java.util.List;

/**
 * Waveshare RPi Relay Board (B), 8 kanalen, via de GPIO-pinnen van de Pi (Pi4J + libgpiod,
 * werkt ook op de Pi 5).
 *
 * <p>Het bord is <b>low-active</b>: een relais zit aan bij een lage pin en uit bij een hoge pin.
 * Die omkering zit alleen in deze klasse; de rest van de app denkt in aan/uit.
 *
 * <p>Pinnen worden bij het aanmaken meteen hoog (= uit) gezet, en Pi4J zet ze bij afsluiten
 * ook weer hoog. De gebruiker moet in de groep {@code gpio} zitten.
 */
public final class WaveshareRelayBoard implements RelayBoard {

    /**
     * BCM-pinnummers voor CH1..CH8 volgens de Waveshare-documentatie van Relay Board (B).
     * Controleer dit met {@code --relay-test} voordat je er iets belangrijks aan hangt.
     */
    static final int[] BCM_PINS = {5, 6, 13, 16, 19, 20, 21, 26};

    private static final String GPIOD_PROVIDER = "gpiod-digital-output";

    /** Eén uitgangspin; los van Pi4J zodat de aan/uit-logica zonder Pi te testen is. */
    interface OutputPin {
        void setHigh(boolean high);

        boolean isHigh();
    }

    private final List<OutputPin> pins;
    private final Runnable onClose;
    private boolean closed;

    /** Het echte bord op de Pi. */
    public WaveshareRelayBoard() {
        this(Pi4J.newAutoContext());
    }

    private WaveshareRelayBoard(Context pi4j) {
        this(createPins(pi4j), pi4j::shutdown);
    }

    WaveshareRelayBoard(List<OutputPin> pins, Runnable onClose) {
        this.pins = List.copyOf(pins);
        this.onClose = onClose;
        allOff();
    }

    private static List<OutputPin> createPins(Context pi4j) {
        try {
            return createPinsOrThrow(pi4j);
        } catch (RuntimeException e) {
            pi4j.shutdown(); // al aangemaakte pinnen weer vrijgeven (en hoog zetten)
            throw e;
        }
    }

    private static List<OutputPin> createPinsOrThrow(Context pi4j) {
        List<OutputPin> pins = new ArrayList<>();
        for (int i = 0; i < BCM_PINS.length; i++) {
            DigitalOutput output = pi4j.create(DigitalOutput.newConfigBuilder(pi4j)
                    .id("relay-ch" + (i + 1))
                    .name("Relais CH" + (i + 1))
                    .address(BCM_PINS[i])
                    .initial(DigitalState.HIGH)   // uit
                    .shutdown(DigitalState.HIGH)  // uit bij afsluiten
                    .provider(GPIOD_PROVIDER));
            pins.add(new OutputPin() {
                @Override
                public void setHigh(boolean high) {
                    output.state(high ? DigitalState.HIGH : DigitalState.LOW);
                }

                @Override
                public boolean isHigh() {
                    return output.isHigh();
                }
            });
        }
        return pins;
    }

    @Override
    public int channels() {
        return pins.size();
    }

    @Override
    public synchronized void set(int channel, boolean on) {
        if (closed) {
            throw new IllegalStateException("Relaisbord is al gesloten");
        }
        pins.get(Channels.index(channel, channels())).setHigh(!on); // low-active
    }

    @Override
    public synchronized boolean isOn(int channel) {
        return !pins.get(Channels.index(channel, channels())).isHigh();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            allOff();
        } finally {
            closed = true;
            onClose.run();
        }
    }
}
