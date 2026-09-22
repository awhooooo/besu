package co.rsk.peg.bitcoin;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import org.hyperledger.besu.datatypes.Hash;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.tuweni.bytes.Bytes;

/** Ported from RSKj's test utilities; only the members the bridge tests use. */
public final class BitcoinTestUtils {

    private BitcoinTestUtils() {
    }

    /** The key whose private scalar is keccak256 of the seed, as RSKj derived test keys. */
    public static BtcECKey getBtcEcKeyFromSeed(String seed) {
        byte[] serializedSeed = Hash.hash(Bytes.wrap(seed.getBytes(StandardCharsets.UTF_8))).getBytes().toArrayUnsafe();
        return BtcECKey.fromPrivate(serializedSeed);
    }

    public static List<BtcECKey> getBtcEcKeysFromSeeds(String[] seeds, boolean sorted) {
        List<BtcECKey> keys = Arrays.stream(seeds)
            .map(BitcoinTestUtils::getBtcEcKeyFromSeed)
            .collect(Collectors.toList());
        if (sorted) {
            keys.sort(BtcECKey.PUBKEY_COMPARATOR);
        }
        return keys;
    }

    public static Address createP2PKHAddress(NetworkParameters networkParameters, String seed) {
        return getBtcEcKeyFromSeed(seed).toAddress(networkParameters);
    }

    public static Sha256Hash createHash(int nHash) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) (0xFF & nHash);
        bytes[1] = (byte) (0xFF & nHash >> 8);
        bytes[2] = (byte) (0xFF & nHash >> 16);
        bytes[3] = (byte) (0xFF & nHash >> 24);
        return Sha256Hash.wrap(bytes);
    }
}
