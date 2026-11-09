package nl.chortzadarra.busdash.edge.status;

/** De stemming van de bus in het dashboard. */
public enum Mood {
    /** Flink aan het laden door de zon: zonnebril op. */
    SUNNY("sunny"),
    /** Er gebeurt niet zoveel: tevreden en wakker. */
    AWAKE("awake"),
    /** Het is nacht (geen paneelspanning): slapen. */
    SLEEPING("sleeping");

    private final String id;

    Mood(String id) {
        this.id = id;
    }

    /** Naam zoals de webpagina hem gebruikt. */
    public String id() {
        return id;
    }
}
