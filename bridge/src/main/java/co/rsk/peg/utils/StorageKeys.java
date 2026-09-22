package co.rsk.peg.utils;

import co.rsk.bitcoinj.core.Sha256Hash;
import org.hyperledger.besu.datatypes.Hash;

import java.nio.charset.StandardCharsets;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Logical storage keys of the bridge, derived exactly as RSKj's DataWord did so nothing moves under the state:
 * a name is its UTF-8 bytes right-aligned in 32 bytes, a compound key is keccak256 of the joined string,
 * and a Bitcoin hash is used as is.
 */
public final class StorageKeys {

    private StorageKeys() {
    }

    /** Equivalent of {@code DataWord.fromString(name)}. */
    public static Bytes32 name(String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Bytes32.SIZE) {
            throw new IllegalArgumentException("A storage key name must be at most 32 bytes long: " + name);
        }
        return Bytes32.leftPad(Bytes.wrap(bytes));
    }

    /** Equivalent of {@code DataWord.fromLongString(value)}. */
    public static Bytes32 compound(String value) {
        return Bytes32.wrap(Hash.hash(Bytes.wrap(value.getBytes(StandardCharsets.UTF_8))).getBytes().toArrayUnsafe());
    }

    /** Equivalent of {@code DataWord.valueFromHex(hash.toString())}. */
    public static Bytes32 of(Sha256Hash hash) {
        return Bytes32.wrap(hash.getBytes());
    }
}
