package co.rsk.peg;

import org.hyperledger.besu.ethereum.rlp.RLP;

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes;

/**
 * The RLP primitives RSKj's tests used to build inputs and expectations, on Besu's encoder. Byte-level agreement
 * between the two encoders is pinned by the RSKj bytes recorded in BridgeSerializationUtilsTest; these helpers
 * only build test data.
 */
public final class RlpTestUtils {

    private RlpTestUtils() {
    }

    /** RSKj's {@code RLP.encodeElement}: a null or empty array encodes as the empty string. */
    public static byte[] encodeElement(byte[] data) {
        return RLP.encodeOne(data == null ? Bytes.EMPTY : Bytes.wrap(data)).toArrayUnsafe();
    }

    /** RSKj's {@code RLP.encodeBigInteger}: the minimal unsigned big-endian bytes, zero as the empty string. */
    public static byte[] encodeBigInteger(BigInteger value) {
        return RLP.encode(out -> out.writeBigIntegerScalar(value)).toArrayUnsafe();
    }

    /** RSKj's {@code RLP.encodeList}: a list of already encoded items. */
    public static byte[] encodeList(byte[]... encodedItems) {
        return RLP.encode(out -> {
            out.startList();
            for (byte[] item : encodedItems) {
                out.writeRaw(Bytes.wrap(item));
            }
            out.endList();
        }).toArrayUnsafe();
    }

    public static byte[] encodedEmptyList() {
        return new byte[] {(byte) 0xc0};
    }

    /** RSKj's {@code RLP.decodeBigInteger(data, 0)}: the first item read as an unsigned integer. */
    public static BigInteger decodeBigInteger(byte[] data) {
        return new BigInteger(1, RLP.input(Bytes.wrap(data)).readBytes().toArrayUnsafe());
    }
}
