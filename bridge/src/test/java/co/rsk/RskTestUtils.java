package co.rsk;

import org.hyperledger.besu.datatypes.Hash;

import java.util.Random;

import org.apache.tuweni.bytes.Bytes32;

/** Ported from RSKj's test utilities (RskTestUtils and TestUtils); only the members the bridge tests use. */
public final class RskTestUtils {

    private RskTestUtils() {
    }

    /** RSKj's TestUtils.generateBytes(long, int): the bytes a Random seeded with the value yields. */
    public static byte[] generateBytes(long seed, int length) {
        byte[] result = new byte[length];
        new Random(seed).nextBytes(result);
        return result;
    }

    /** RSKj's TestUtils.generateBytes(String, int): seeded from this class's name and the discriminator. */
    public static byte[] generateBytes(String discriminator, int length) {
        long seedWithSalt = 31L * RskTestUtils.class.getName().hashCode() + discriminator.hashCode();
        return generateBytes(seedWithSalt, length);
    }

    /** RSKj's TestUtils.generateInt(String, int): a value below the bound from a Random seeded with the discriminator. */
    public static int generateInt(String discriminator, int bound) {
        return new Random(discriminator.hashCode()).nextInt(bound);
    }

    /** A 32-byte hash whose first two bytes are the little-endian value, as RSKj's createHash built it. */
    public static Hash createHash(int nHash) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) (nHash & 0xFF);
        bytes[1] = (byte) (nHash >> 8 & 0xFF);
        return Hash.wrap(Bytes32.wrap(bytes));
    }
}
