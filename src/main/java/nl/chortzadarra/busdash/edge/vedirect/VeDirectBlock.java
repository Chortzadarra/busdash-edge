package nl.chortzadarra.busdash.edge.vedirect;

import java.util.Map;
import java.util.Optional;

/**
 * Eén gevalideerd blok uit het VE.Direct-tekstprotocol: alle label/waarde-paren
 * tussen twee checksums. De BMV-712 verstuurt zijn data in twee afwisselende blokken
 * (live waarden en historie), dus één blok bevat niet altijd alle velden.
 */
public record VeDirectBlock(Map<String, String> fields) {

    public VeDirectBlock {
        fields = Map.copyOf(fields);
    }

    public Optional<String> get(String label) {
        return Optional.ofNullable(fields.get(label));
    }
}
