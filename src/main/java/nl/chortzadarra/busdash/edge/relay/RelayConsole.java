package nl.chortzadarra.busdash.edge.relay;

import java.util.Locale;
import java.util.StringJoiner;

/**
 * Vertaalt getypte opdrachten naar relaisacties, om met de hand te testen.
 * Later komt MQTT/het dashboard hiervoor in de plaats.
 *
 * <pre>
 *   3 aan        kanaal 3 aan
 *   3 uit        kanaal 3 uit
 *   alles uit    alle relais uit
 *   status       stand van alle relais
 * </pre>
 */
public final class RelayConsole {

    public static final String HELP = """
            Opdrachten: <kanaal> aan | <kanaal> uit | alles uit | status | stop""";

    private final RelayBoard board;

    public RelayConsole(RelayBoard board) {
        this.board = board;
    }

    /** Voert één regel uit en geeft het antwoord terug dat getoond moet worden. */
    public String handle(String line) {
        String[] words = line.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length == 1 && words[0].equals("status")) {
            return status();
        }
        if (words.length == 2 && words[0].equals("alles") && words[1].equals("uit")) {
            board.allOff();
            return status();
        }
        if (words.length == 2 && (words[1].equals("aan") || words[1].equals("uit"))) {
            int channel;
            try {
                channel = Integer.parseInt(words[0]);
            } catch (NumberFormatException e) {
                return "Onbekend kanaal: " + words[0];
            }
            try {
                board.set(channel, words[1].equals("aan"));
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
            return status();
        }
        return HELP;
    }

    public String status() {
        StringJoiner joiner = new StringJoiner("  ");
        for (int channel = 1; channel <= board.channels(); channel++) {
            joiner.add("CH" + channel + "=" + (board.isOn(channel) ? "AAN" : "uit"));
        }
        return joiner.toString();
    }
}
