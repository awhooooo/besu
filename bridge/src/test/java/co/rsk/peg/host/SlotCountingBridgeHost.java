package co.rsk.peg.host;

import java.util.Arrays;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.Wei;

/**
 * Counts the storage slots a piece of bridge code touches, so that one storage design can be compared with
 * another on the number that matters. A slot read is a trie lookup that may reach disk and is what grows with
 * the state; hashing and serializing are CPU and belong to a wall-clock measurement instead.
 *
 * <p>It wraps any host and counts what {@link FrameBridgeHost} would do on a real world state, which is not the
 * same as the number of calls made to it:
 *
 * <ul>
 *   <li>A logical key holding a value of arbitrary length is spread over slots by {@link ChunkedStorage}: one
 *       slot for the length and one per 32 bytes. Reading such a value reads the length slot and every chunk.
 *   <li>Writing one compares before it writes, so it reads the length slot, every chunk it may change and the
 *       length slot again, then writes only the slots whose contents actually differ. That read-before-write is
 *       what keeps an unchanged value from entering a block's changes, so it is counted, not excused.
 *   <li>A record layout such as {@link co.rsk.peg.storage.UtxoRecords} addresses slots directly, so a raw read
 *       is one slot and a raw write is one read plus, if it differs, one write.
 * </ul>
 *
 * <p>The counts therefore hold whether the host underneath keeps state in memory or in a world state, which is
 * what makes a cheap in-memory measurement comparable to a real one.
 */
public final class SlotCountingBridgeHost implements BridgeHost {

    private static final int SLOT = Bytes32.SIZE;

    private final BridgeHost inner;

    private long slotReads;
    private long slotWrites;

    private SlotCountingBridgeHost(BridgeHost inner) {
        this.inner = inner;
    }

    public static SlotCountingBridgeHost over(BridgeHost inner) {
        return new SlotCountingBridgeHost(inner);
    }

    /** Starts counting again, for measuring one phase of a longer setup. */
    public void reset() {
        slotReads = 0;
        slotWrites = 0;
    }

    public long slotReads() {
        return slotReads;
    }

    public long slotWrites() {
        return slotWrites;
    }

    public String summary() {
        return String.format("%d slot reads, %d slot writes", slotReads, slotWrites);
    }

    private static int chunks(byte[] value) {
        return value == null ? 0 : (value.length + SLOT - 1) / SLOT;
    }

    @Override
    public byte[] getStorage(Bytes32 key) {
        byte[] value = inner.getStorage(key);
        // the length slot, then every chunk the value occupies
        slotReads += 1 + chunks(value);
        return value;
    }

    @Override
    public void putStorage(Bytes32 key, byte[] value) {
        byte[] previous = inner.getStorage(key); // the counter's own bookkeeping, not part of the measurement
        int oldChunks = chunks(previous);
        int newChunks = chunks(value);

        // the length slot, every chunk that may change, and the length slot again before it is rewritten
        slotReads += 2 + Math.max(oldChunks, newChunks);
        for (int i = 0; i < Math.max(oldChunks, newChunks); i++) {
            if (!Arrays.equals(chunk(value, i), chunk(previous, i))) {
                slotWrites++;
            }
        }
        if (length(previous) != length(value)) {
            slotWrites++; // the length slot, written only when the length actually changed
        }

        inner.putStorage(key, value);
    }

    private static int length(byte[] value) {
        return value == null ? 0 : value.length;
    }

    /** Chunk {@code index} of the value as stored: 32 bytes, right padded with zeros, or zeros past the end. */
    private static byte[] chunk(byte[] value, int index) {
        byte[] out = new byte[SLOT];
        int offset = index * SLOT;
        if (value != null && offset < value.length) {
            System.arraycopy(value, offset, out, 0, Math.min(SLOT, value.length - offset));
        }
        return out;
    }

    @Override
    public UInt256 getSlot(UInt256 slot) {
        slotReads++;
        return inner.getSlot(slot);
    }

    @Override
    public void putSlot(UInt256 slot, UInt256 value) {
        slotReads++; // a raw write compares before it writes, for the same reason
        if (!inner.getSlot(slot).equals(value)) {
            slotWrites++;
        }
        inner.putSlot(slot, value);
    }

    @Override public long blockNumber() { return inner.blockNumber(); }
    @Override public long blockTimestamp() { return inner.blockTimestamp(); }
    @Override public Address origin() { return inner.origin(); }
    @Override public Address caller() { return inner.caller(); }
    @Override public Hash transactionHash() { return inner.transactionHash(); }
    @Override public Wei callValue() { return inner.callValue(); }
    @Override public CallKind callKind() { return inner.callKind(); }
    @Override public boolean isLocalCall() { return inner.isLocalCall(); }
    @Override public boolean callerIsContract() { return inner.callerIsContract(); }
    @Override public Optional<byte[]> originPublicKey() { return inner.originPublicKey(); }
    @Override public Wei balanceOf(Address account) { return inner.balanceOf(account); }
    @Override public void transfer(Address from, Address to, Wei amount) { inner.transfer(from, to, amount); }
    @Override public void emitLog(Log log) { inner.emitLog(log); }
}
