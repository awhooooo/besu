package co.rsk.peg;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.Sha256Hash;
import org.hyperledger.besu.datatypes.Hash;

import java.util.ArrayList;
import java.util.List;

import org.apache.tuweni.bytes.Bytes32;

/** Ported from RSKj's test utilities; only the members the bridge tests use. */
public final class PegTestUtils {

    private PegTestUtils() {
    }

    /** A 32-byte RSK transaction hash whose first two bytes are the little-endian value. */
    public static Hash createHash3(int nHash) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) (nHash & 0xFF);
        bytes[1] = (byte) (nHash >> 8 & 0xFF);
        return Hash.wrap(Bytes32.wrap(bytes));
    }

    /** A Bitcoin hash whose first four bytes are the little-endian value. */
    public static Sha256Hash createHash(int nHash) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) (0xFF & nHash);
        bytes[1] = (byte) (0xFF & nHash >> 8);
        bytes[2] = (byte) (0xFF & nHash >> 16);
        bytes[3] = (byte) (0xFF & nHash >> 24);
        return Sha256Hash.wrap(bytes);
    }

    public static List<BtcECKey> createRandomBtcECKeys(int keysCount) {
        List<BtcECKey> keys = new ArrayList<>();
        for (int i = 0; i < keysCount; i++) {
            keys.add(new BtcECKey());
        }
        return keys;
    }
}
