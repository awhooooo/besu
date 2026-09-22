package co.rsk;

import org.hyperledger.besu.datatypes.Hash;

import org.apache.tuweni.bytes.Bytes32;

/** Ported from RSKj's test utilities; only the members the bridge tests use. */
public final class RskTestUtils {

    private RskTestUtils() {
    }

    /** A 32-byte hash whose first two bytes are the little-endian value, as RSKj's createHash built it. */
    public static Hash createHash(int nHash) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) (nHash & 0xFF);
        bytes[1] = (byte) (nHash >> 8 & 0xFF);
        return Hash.wrap(Bytes32.wrap(bytes));
    }
}
