package co.rsk.peg.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The message formats RSKj's printable bytes and string trimming produced. */
class PrintableTest {

    @Test
    void upToThirtyTwoBytesPrintInFull() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", Printable.hex(bytes));
        assertEquals("", Printable.hex(new byte[0]));
        assertEquals("ff", Printable.hex(new byte[] {(byte) 0xff}));
    }

    @Test
    void longerValuesKeepSixteenHeadAndFifteenTailBytes() {
        byte[] bytes = new byte[33];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (0xa0 + i);
        }
        // bytes 0..15, then "..", then bytes 18..32
        assertEquals("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf..b2b3b4b5b6b7b8b9babbbcbdbebfc0", Printable.hex(bytes));
    }

    @Test
    void nullPrintsAsNull() {
        assertEquals("null", Printable.hex(null));
    }

    @Test
    void trimCutsAtSixtySixCharacters() {
        String sixtySix = "x".repeat(66);
        assertEquals(sixtySix, Printable.trim(sixtySix));
        assertEquals(sixtySix + "...", Printable.trim(sixtySix + "y"));
        assertEquals("", Printable.trim(""));
        assertNull(Printable.trim(null));
    }
}
