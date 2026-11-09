package nl.chortzadarra.busdash.edge.solar;

/** Laadstatus van een Victron-laadregelaar (VE.Direct-veld {@code CS}). */
public enum ChargeState {
    OFF(0, "Uit"),
    LOW_POWER(1, "Laag vermogen"),
    FAULT(2, "Storing"),
    BULK(3, "Bulk"),
    ABSORPTION(4, "Absorptie"),
    FLOAT(5, "Druppelladen"),
    STORAGE(6, "Opslag"),
    EQUALIZE(7, "Egaliseren"),
    INVERTING(9, "Omvormen"),
    POWER_SUPPLY(11, "Voeding"),
    STARTING_UP(245, "Opstarten"),
    REPEATED_ABSORPTION(246, "Herhaalde absorptie"),
    AUTO_EQUALIZE(247, "Auto-egaliseren"),
    BATTERY_SAFE(248, "BatterySafe"),
    EXTERNAL_CONTROL(252, "Externe sturing"),
    UNKNOWN(-1, "Onbekend");

    private final int code;
    private final String label;

    ChargeState(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    /** Nederlandse omschrijving voor het dashboard. */
    public String label() {
        return label;
    }

    /** True als de regelaar de accu op dit moment echt aan het laden is. */
    public boolean charging() {
        return this == BULK || this == ABSORPTION || this == FLOAT || this == EQUALIZE
                || this == REPEATED_ABSORPTION || this == AUTO_EQUALIZE;
    }

    public static ChargeState of(int code) {
        for (ChargeState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        return UNKNOWN;
    }
}
