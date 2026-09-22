package co.rsk.peg.host;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.account.MutableAccount;

import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Maps a logical 32-byte key holding a value of arbitrary length onto an account's 32-byte storage slots.
 *
 * <p>Layout: the length lives at {@code keccak256(key)}; chunk {@code i} of the value lives at
 * {@code keccak256(key || uint256(i))}, right-padded with zeros. A length of zero means absent.
 * Writes compare against the current slot value first, so storing an unchanged value touches no slot,
 * which is what RSKj's trie did implicitly and what keeps unrelated bridge calls from colliding under
 * parallel block import.
 */
public final class ChunkedStorage {

    private static final int CHUNK = Bytes32.SIZE;

    private final MutableAccount account;

    public ChunkedStorage(MutableAccount account) {
        this.account = account;
    }

    public byte[] get(Bytes32 key) {
        int length = length(key);
        if (length == 0) {
            return null;
        }
        byte[] out = new byte[length];
        int chunks = chunkCount(length);
        for (int i = 0; i < chunks; i++) {
            byte[] chunk = account.getStorageValue(chunkSlot(key, i)).toBytes().toArrayUnsafe();
            int offset = i * CHUNK;
            System.arraycopy(chunk, 0, out, offset, Math.min(CHUNK, length - offset));
        }
        return out;
    }

    public void put(Bytes32 key, byte[] value) {
        int oldLength = length(key);
        int newLength = value == null ? 0 : value.length;
        int oldChunks = chunkCount(oldLength);
        int newChunks = chunkCount(newLength);

        for (int i = 0; i < newChunks; i++) {
            int offset = i * CHUNK;
            byte[] chunk = Arrays.copyOfRange(value, offset, offset + CHUNK); // zero padded past the end
            write(chunkSlot(key, i), UInt256.fromBytes(Bytes32.wrap(chunk)));
        }
        for (int i = newChunks; i < oldChunks; i++) {
            write(chunkSlot(key, i), UInt256.ZERO);
        }
        write(lengthSlot(key), UInt256.valueOf(newLength));
    }

    private int length(Bytes32 key) {
        UInt256 stored = account.getStorageValue(lengthSlot(key));
        if (stored.fitsInt()) {
            return stored.intValue();
        }
        throw new IllegalStateException("Corrupt bridge storage: length does not fit an int under key " + key);
    }

    private void write(UInt256 slot, UInt256 value) {
        if (!account.getStorageValue(slot).equals(value)) {
            account.setStorageValue(slot, value);
        }
    }

    private static int chunkCount(int length) {
        return (length + CHUNK - 1) / CHUNK;
    }

    static UInt256 lengthSlot(Bytes32 key) {
        return UInt256.fromBytes(Hash.hash(key).getBytes());
    }

    static UInt256 chunkSlot(Bytes32 key, int index) {
        return UInt256.fromBytes(Hash.hash(Bytes.concatenate(key, UInt256.valueOf(index))).getBytes());
    }
}
