package nl.chortzadarra.busdash.edge.vedirect;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static nl.chortzadarra.busdash.edge.vedirect.VeDirectFrames.ascii;
import static nl.chortzadarra.busdash.edge.vedirect.VeDirectFrames.block;
import static nl.chortzadarra.busdash.edge.vedirect.VeDirectFrames.concat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VeDirectParserTest {

    private final List<VeDirectBlock> blocks = new ArrayList<>();
    private VeDirectParser parser;

    @BeforeEach
    void setUp() {
        parser = new VeDirectParser(blocks::add);
    }

    @Test
    void parsesValidBlock() {
        parser.feed(block("V", "12800", "I", "-1500", "SOC", "876"));

        assertEquals(1, blocks.size());
        assertEquals("12800", blocks.get(0).fields().get("V"));
        assertEquals("-1500", blocks.get(0).fields().get("I"));
        assertEquals("876", blocks.get(0).fields().get("SOC"));
        assertEquals(0, parser.invalidBlocks());
    }

    @Test
    void rejectsBlockWithCorruptedByte() {
        byte[] data = block("V", "12800", "I", "-1500");
        data[5] = '9'; // eerste cijfer van V aangepast: checksum klopt niet meer

        parser.feed(data);

        assertTrue(blocks.isEmpty());
        assertEquals(1, parser.invalidBlocks());
    }

    @Test
    void recoversWhenStartingInTheMiddleOfAStream() {
        byte[] halfBlock = ascii("800\r\nI\t-1500\r\nSOC\t876");
        parser.feed(concat(halfBlock, block("V", "12800"), block("V", "12790")));

        // Het eerste blok bevat ruis van vóór de start en wordt afgekeurd; daarna gaat het goed.
        assertEquals(1, blocks.size());
        assertEquals("12790", blocks.get(0).fields().get("V"));
    }

    @Test
    void handlesDataArrivingOneByteAtATime() {
        for (byte b : block("V", "12800", "I", "-1500")) {
            parser.feed(b);
        }

        assertEquals(1, blocks.size());
    }

    @Test
    void ignoresHexMessageInsideTextBlock() {
        byte[] first = ascii("\r\nV\t12800");
        byte[] hex = ascii(":A0002000148\n");
        byte[] full = block("V", "12800", "I", "-1500");
        byte[] rest = java.util.Arrays.copyOfRange(full, first.length, full.length);

        parser.feed(concat(first, hex, rest));

        assertEquals(1, blocks.size());
        assertEquals("12800", blocks.get(0).fields().get("V"));
        assertEquals("-1500", blocks.get(0).fields().get("I"));
    }

    @ParameterizedTest
    @ValueSource(ints = {':', '\n', '\r', '\t'})
    void acceptsChecksumByteThatLooksLikeAControlCharacter(int checksumByte) {
        byte[] data = blockWithChecksumByte(checksumByte);

        parser.feed(concat(data, block("V", "12790")));

        assertEquals(2, blocks.size(), "beide blokken moeten geldig zijn");
    }

    /** Zoekt een veldwaarde waarbij de checksum-byte precies de gevraagde waarde krijgt. */
    private static byte[] blockWithChecksumByte(int target) {
        for (int padding = 0; padding < 32; padding++) {
            for (int n = 10; n < 100; n++) {
                byte[] candidate = block("V", "12800", "X", "0".repeat(padding) + n);
                if ((candidate[candidate.length - 1] & 0xFF) == target) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("Geen blok gevonden voor checksum " + target);
    }
}
