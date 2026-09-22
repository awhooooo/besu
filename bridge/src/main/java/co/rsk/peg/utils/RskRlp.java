package co.rsk.peg.utils;

import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.rlp.RLPInput;
import org.hyperledger.besu.ethereum.rlp.RLPOutput;

import java.math.BigInteger;
import java.util.function.Consumer;

import org.apache.tuweni.bytes.Bytes;

/**
 * Besu RLP with the conventions RSKj's encoder and decoder had, so that every bridge encoding stays byte for
 * byte what it was and every decoder accepts what RSKj accepted.
 *
 * <p>Encoding: a null or empty element and a zero scalar are both the empty string {@code 0x80}; a scalar is its
 * minimal big-endian bytes. Decoding: a string item yields its payload, the empty string yields null (RSKj's
 * {@code getRLPData}), and a list item yields its whole encoding, which RSKj's decoders re-parsed; scalars are read
 * as unsigned integers without rejecting leading zeros, as RSKj's {@code fromUnsignedByteArray} did.
 */
public final class RskRlp {

    private static final byte[] EMPTY_LIST = {(byte) 0xc0};

    private RskRlp() {
    }

    public static byte[] encode(Consumer<RLPOutput> writer) {
        return RLP.encode(writer).toArrayUnsafe();
    }

    /** RSKj's {@code RLP.encodeElement}. */
    public static byte[] encodeElement(byte[] data) {
        return RLP.encodeOne(wrap(data)).toArrayUnsafe();
    }

    /** RSKj's {@code RLP.encodedEmptyList}. */
    public static byte[] encodedEmptyList() {
        return EMPTY_LIST.clone();
    }

    public static void writeElement(RLPOutput out, byte[] data) {
        out.writeBytes(wrap(data));
    }

    /** RSKj's {@code RLP.encodeBigInteger}, for the long-valued fields the bridge stores. */
    public static void writeUnsigned(RLPOutput out, long value) {
        writeUnsigned(out, BigInteger.valueOf(value));
    }

    public static void writeUnsigned(RLPOutput out, BigInteger value) {
        out.writeBigIntegerScalar(value);
    }

    public static RLPInput input(byte[] data) {
        return RLP.input(Bytes.wrap(data));
    }

    /**
     * RSKj's {@code RLPElement.getRLPData()}: the payload of a string item, null for the empty string, and the
     * complete encoding of a list item.
     */
    public static byte[] readData(RLPInput in) {
        if (in.nextIsList()) {
            return in.readAsRlp().raw().toArrayUnsafe();
        }
        Bytes payload = in.readBytes();
        return payload.isEmpty() ? null : payload.toArrayUnsafe();
    }

    /** RSKj's {@code RLPElement.getRLPRawData()}: as {@link #readData}, but the empty string is an empty array. */
    public static byte[] readRawData(RLPInput in) {
        if (in.nextIsList()) {
            return in.readAsRlp().raw().toArrayUnsafe();
        }
        return in.readBytes().toArrayUnsafe();
    }

    /** RSKj's {@code BigIntegers.fromUnsignedByteArray} over the next item, with the empty string as zero. */
    public static BigInteger readUnsigned(RLPInput in) {
        byte[] data = readData(in);
        return data == null ? BigInteger.ZERO : new BigInteger(1, data);
    }

    private static Bytes wrap(byte[] data) {
        return data == null ? Bytes.EMPTY : Bytes.wrap(data);
    }
}
