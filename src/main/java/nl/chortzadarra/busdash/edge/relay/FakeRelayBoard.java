package nl.chortzadarra.busdash.edge.relay;

/** Gesimuleerd relaisbord voor ontwikkelen op je laptop: onthoudt alleen de stand. */
public final class FakeRelayBoard implements RelayBoard {

    private final boolean[] states;

    public FakeRelayBoard() {
        this(WaveshareRelayBoard.BCM_PINS.length);
    }

    public FakeRelayBoard(int channels) {
        this.states = new boolean[channels];
    }

    @Override
    public int channels() {
        return states.length;
    }

    @Override
    public synchronized void set(int channel, boolean on) {
        states[Channels.index(channel, channels())] = on;
    }

    @Override
    public synchronized boolean isOn(int channel) {
        return states[Channels.index(channel, channels())];
    }

    @Override
    public void close() {
        allOff();
    }
}
