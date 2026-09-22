package co.rsk.peg.utils;

import org.apache.tuweni.bytes.Bytes;

/**
 * Formatting of values for log lines and error messages, exactly as RSKj's printable bytes and string trimming
 * formatted them, so messages stay comparable with RSKj's.
 */
public final class Printable {

    private static final int ABBREVIATE_ABOVE_BYTES = 32;
    private static final int HEAD_BYTES = 16;
    private static final int TAIL_BYTES = 15;
    /** 0x plus 32 bytes, each byte as 2 hex characters. */
    private static final int MAX_TEXT_LENGTH = 66;

    private Printable() {
    }

    /**
     * RSKj's {@code Bytes.of(bytes)} as printed: lowercase unprefixed hex, and above 32 bytes the first 16 and the
     * last 15 bytes joined by "..". A null array prints as "null", which is what a null reference printed.
     */
    public static String hex(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        if (bytes.length > ABBREVIATE_ABOVE_BYTES) {
            return hex(bytes, 0, HEAD_BYTES) + ".." + hex(bytes, bytes.length - TAIL_BYTES, TAIL_BYTES);
        }
        return hex(bytes, 0, bytes.length);
    }

    private static String hex(byte[] bytes, int offset, int length) {
        return Bytes.wrap(bytes, offset, length).toUnprefixedHexString();
    }

    /** RSKj's {@code StringUtils.trim}: text longer than 66 characters is cut there and suffixed with "...". */
    public static String trim(String text) {
        if (text == null || text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH) + "...";
    }
}
