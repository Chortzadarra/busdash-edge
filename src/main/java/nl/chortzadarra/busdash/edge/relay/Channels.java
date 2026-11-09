package nl.chortzadarra.busdash.edge.relay;

/** Controle op kanaalnummers, gedeeld door de implementaties. */
final class Channels {

    private Channels() {
    }

    /** Zet kanaal 1..n om naar index 0..n-1, of gooit een duidelijke fout. */
    static int index(int channel, int channels) {
        if (channel < 1 || channel > channels) {
            throw new IllegalArgumentException(
                    "Kanaal " + channel + " bestaat niet, kies 1 t/m " + channels);
        }
        return channel - 1;
    }
}
