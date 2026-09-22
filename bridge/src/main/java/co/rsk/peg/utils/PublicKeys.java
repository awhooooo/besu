package co.rsk.peg.utils;

import co.rsk.bitcoinj.core.BtcECKey;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * secp256k1 public key helpers with the exact semantics the bridge relied on from RSKj's ECKey:
 * the account address is the last 20 bytes of keccak256 over the uncompressed key without its prefix byte,
 * and "the public key" of an RSK-side key is its uncompressed SEC encoding.
 */
public final class PublicKeys {

    private static final int UNCOMPRESSED_LENGTH = 65;

    private PublicKeys() {
    }

    /** Parses any SEC encoding, compressed or not. Throws IllegalArgumentException on an invalid key. */
    public static BtcECKey parse(byte[] encoded) {
        return BtcECKey.fromPublicOnly(encoded);
    }

    public static Address addressOf(byte[] encodedPublicKey) {
        return addressOf(parse(encodedPublicKey));
    }

    /** Equivalent of {@code ECKey.fromPublicOnly(key.getPubKey()).getAddress()}. */
    public static Address addressOf(BtcECKey key) {
        byte[] uncompressed = uncompressed(key);
        Bytes xy = Bytes.wrap(uncompressed, 1, UNCOMPRESSED_LENGTH - 1);
        return Address.extract(Bytes32.wrap(Hash.hash(xy).getBytes().toArrayUnsafe()));
    }

    /** Equivalent of {@code ecKey.getPubKey()}: the uncompressed SEC encoding. */
    public static byte[] uncompressed(BtcECKey key) {
        return key.getPubKeyPoint().getEncoded(false);
    }

    /** Equivalent of {@code ecKey.getPubKey(true)}: the compressed SEC encoding. */
    public static byte[] compressed(BtcECKey key) {
        return key.getPubKeyPoint().getEncoded(true);
    }

    /** A copy of the key whose {@code getPubKey()} yields the uncompressed encoding, as RSKj's ECKey did. */
    public static BtcECKey asUncompressedKey(BtcECKey key) {
        return BtcECKey.fromPublicOnly(uncompressed(key));
    }

    /** A copy of the key whose {@code getPubKey()} yields the compressed encoding. */
    public static BtcECKey asCompressedKey(BtcECKey key) {
        return BtcECKey.fromPublicOnly(compressed(key));
    }
}
