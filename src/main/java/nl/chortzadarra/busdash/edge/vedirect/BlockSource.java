package nl.chortzadarra.busdash.edge.vedirect;

/**
 * Iets dat gevalideerde VE.Direct-blokken aanlevert aan een listener, zoals de seriële poort.
 * Als interface zodat de batterijlaag zonder hardware getest kan worden.
 */
public interface BlockSource extends AutoCloseable {

    void start();

    @Override
    void close();
}
