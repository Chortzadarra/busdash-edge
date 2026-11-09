package nl.chortzadarra.busdash.edge.relay;

/**
 * Een bord met relais die aan of uit kunnen. Kanalen tellen vanaf 1, zoals op het bord
 * gedrukt staat (CH1..CH8). Op de Pi is dit het Waveshare-bord, op je laptop een simulatie.
 *
 * <p>Bij {@link #close()} gaan alle relais uit: na afsluiten of een crash is "uit" de veilige stand.
 */
public interface RelayBoard extends AutoCloseable {

    int channels();

    void set(int channel, boolean on);

    boolean isOn(int channel);

    default void allOff() {
        for (int channel = 1; channel <= channels(); channel++) {
            set(channel, false);
        }
    }

    @Override
    void close();
}
