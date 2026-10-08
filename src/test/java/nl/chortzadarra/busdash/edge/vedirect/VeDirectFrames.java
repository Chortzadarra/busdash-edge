package nl.chortzadarra.busdash.edge.vedirect;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Hulpmiddel om geldige VE.Direct-blokken (met correcte checksum) te bouwen in tests. */
public final class VeDirectFrames {

    private VeDirectFrames() {
    }

    public static byte[] block(String... labelValuePairs) {
        if (labelValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Verwacht label/waarde-paren");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < labelValuePairs.length; i += 2) {
            out.writeBytes(("\r\n" + labelValuePairs[i] + "\t" + labelValuePairs[i + 1])
                    .getBytes(StandardCharsets.US_ASCII));
        }
        out.writeBytes("\r\nChecksum\t".getBytes(StandardCharsets.US_ASCII));
        int sum = 0;
        for (byte b : out.toByteArray()) {
            sum += b & 0xFF;
        }
        out.write((256 - (sum & 0xFF)) & 0xFF);
        return out.toByteArray();
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    public static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
