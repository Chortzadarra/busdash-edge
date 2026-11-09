package nl.chortzadarra.busdash.edge.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.StringJoiner;

/** Minimale JSON-hulp: alleen wat het dashboard nodig heeft, zonder extra dependency. */
final class Json {

    private Json() {
    }

    static Obj object() {
        return new Obj();
    }

    /** Een JSON-object dat je veld voor veld opbouwt. */
    static final class Obj {
        private final StringJoiner fields = new StringJoiner(",", "{", "}");

        Obj put(String name, Object value) {
            fields.add(string(name) + ":" + value(value));
            return this;
        }

        @Override
        public String toString() {
            return fields.toString();
        }
    }

    /** Zet een Java-waarde om naar JSON. Getallen altijd met een punt, los van de taalinstelling. */
    static String value(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return string(s);
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof Obj) {
            return value.toString();
        }
        if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                return "null";
            }
            return BigDecimal.valueOf(d).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        return string(value.toString());
    }

    /** Zet tekst om naar een JSON-string, inclusief aanhalingstekens. */
    static String string(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<' -> sb.append("\\u003c"); // nooit "</script>" in de uitvoer
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
