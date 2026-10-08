package nl.chortzadarra.busdash.edge.vedirect;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Streaming parser voor het Victron VE.Direct-tekstprotocol.
 *
 * <p>Formaat: elk veld is {@code \r\n<label>\t<waarde>}. Een blok eindigt met
 * {@code \r\nChecksum\t<byte>}, waarbij die laatste byte zo gekozen is dat de som van
 * alle bytes in het blok modulo 256 gelijk is aan 0.
 *
 * <p>Valkuilen die deze parser afvangt:
 * <ul>
 *   <li>De checksum-byte is een willekeurige byte en kan dus ook {@code \n}, {@code \t} of {@code :} zijn.</li>
 *   <li>Tussen de tekstvelden door kan het apparaat HEX-berichten sturen ({@code :...\n}).
 *       Die tellen niet mee in de checksum en worden overgeslagen.</li>
 *   <li>Bij het opstarten begin je midden in een blok; dat eerste blok wordt afgekeurd.</li>
 * </ul>
 *
 * <p>Niet thread-safe: voer bytes vanuit één thread aan.
 */
public final class VeDirectParser {

    private enum State { IDLE, RECORD_BEGIN, RECORD_NAME, RECORD_VALUE, CHECKSUM, HEX }

    private static final String CHECKSUM_LABEL = "Checksum";
    /** Langer dan dit is geen geldig veld meer, maar ruis: opnieuw synchroniseren. */
    private static final int MAX_FIELD_LENGTH = 64;

    private final Consumer<VeDirectBlock> listener;
    private final Map<String, String> fields = new HashMap<>();
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder value = new StringBuilder();

    private State state = State.IDLE;
    private State stateBeforeHex = State.IDLE;
    private int checksum;
    private long validBlocks;
    private long invalidBlocks;

    public VeDirectParser(Consumer<VeDirectBlock> listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    public void feed(byte[] data) {
        feed(data, data.length);
    }

    public void feed(byte[] data, int length) {
        for (int i = 0; i < length; i++) {
            feed(data[i]);
        }
    }

    public void feed(byte b) {
        int c = b & 0xFF;

        if (c == ':' && state != State.CHECKSUM && state != State.HEX) {
            stateBeforeHex = state;
            state = State.HEX;
        }
        if (state != State.HEX) {
            checksum = (checksum + c) & 0xFF;
        }

        switch (state) {
            case IDLE -> {
                if (c == '\n') {
                    state = State.RECORD_BEGIN;
                }
            }
            case RECORD_BEGIN -> {
                name.setLength(0);
                value.setLength(0);
                name.append((char) c);
                state = State.RECORD_NAME;
            }
            case RECORD_NAME -> {
                if (c == '\t') {
                    state = CHECKSUM_LABEL.contentEquals(name) ? State.CHECKSUM : State.RECORD_VALUE;
                } else if (!append(name, c)) {
                    resync();
                }
            }
            case RECORD_VALUE -> {
                if (c == '\n') {
                    fields.put(name.toString(), value.toString());
                    state = State.RECORD_BEGIN;
                } else if (c != '\r' && !append(value, c)) {
                    resync();
                }
            }
            case CHECKSUM -> {
                if (checksum == 0) {
                    validBlocks++;
                    listener.accept(new VeDirectBlock(fields));
                } else {
                    invalidBlocks++;
                }
                fields.clear();
                checksum = 0;
                state = State.IDLE;
            }
            case HEX -> {
                if (c == '\n') {
                    state = stateBeforeHex;
                }
            }
        }
    }

    private static boolean append(StringBuilder target, int c) {
        if (target.length() >= MAX_FIELD_LENGTH) {
            return false;
        }
        target.append((char) c);
        return true;
    }

    private void resync() {
        invalidBlocks++;
        fields.clear();
        name.setLength(0);
        value.setLength(0);
        checksum = 0;
        state = State.IDLE;
    }

    public long validBlocks() {
        return validBlocks;
    }

    public long invalidBlocks() {
        return invalidBlocks;
    }
}
