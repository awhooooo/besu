package co.rsk.peg.storage;

import co.rsk.bitcoinj.core.UTXO;
import org.hyperledger.besu.datatypes.Hash;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * The storage layout of a federation UTXO list, chosen in port step 4e: fixed-length records packed back to back on
 * sequential slots, so that adding a UTXO or spending a few touches a handful of slots instead of rewriting the list.
 *
 * <p>Layout. The header lives at {@code base = keccak256(listKey)}: live record count (8 bytes), physical record
 * count (8 bytes) and record length (4 bytes), big-endian, zero padded to the slot. Record {@code i} occupies bytes
 * {@code [i * length, (i + 1) * length)} of a byte stream that starts at slot {@code base + 1}. A record is the
 * UTXO in bitcoinj's stream form, whose length depends only on the output script, so one list has one record length.
 *
 * <p>Removal marks the record dead by setting the most significant byte of its little-endian value field to 0xff,
 * which no stored UTXO carries because Bitcoin amounts are below 2^51. Survivors keep their order, which is what the
 * bridge's migration transactions depend on. When fewer than half of the physical records are alive, the list is
 * compacted in place, in order, and every freed slot is zeroed, so the same live set always yields the same slots.
 */
public final class UtxoRecords {

    private static final int SLOT = Bytes32.SIZE;
    private static final int LIVE_OFFSET = 0;
    private static final int PHYSICAL_OFFSET = 8;
    private static final int LENGTH_OFFSET = 16;
    /** The most significant byte of the 8-byte little-endian value that starts a record. */
    private static final int TOMBSTONE_OFFSET = 7;
    private static final byte TOMBSTONE = (byte) 0xff;

    /** The header slot as stored. */
    public record Header(long live, long physical, int recordLength) {
        static final Header EMPTY = new Header(0, 0, 0);
    }

    /** A live record and where it sits. */
    public record Entry(int physicalIndex, UTXO utxo) {
    }

    private final StorageAccessor storage;
    private final UInt256 base;

    public UtxoRecords(StorageAccessor storage, Bytes32 listKey) {
        this.storage = storage;
        this.base = UInt256.fromBytes(Hash.hash(listKey).getBytes());
    }

    public Header header() {
        UInt256 stored = storage.getSlot(base);
        if (stored.isZero()) {
            return Header.EMPTY;
        }
        Bytes bytes = stored.toBytes();
        return new Header(
            bytes.slice(LIVE_OFFSET, 8).toLong(),
            bytes.slice(PHYSICAL_OFFSET, 8).toLong(),
            bytes.slice(LENGTH_OFFSET, 4).toInt());
    }

    /** The number of live records, from the header alone. */
    public int size() {
        return Math.toIntExact(header().live());
    }

    /** Every live record with its physical position, in insertion order. */
    public List<Entry> loadEntries() {
        Header header = header();
        List<Entry> entries = new ArrayList<>(Math.toIntExact(header.live()));
        if (header.physical() == 0) {
            return entries;
        }
        int length = header.recordLength();
        byte[] stream = readBytes(0, Math.toIntExact(header.physical()) * length);
        for (int i = 0; i < header.physical(); i++) {
            int offset = i * length;
            if (stream[offset + TOMBSTONE_OFFSET] == TOMBSTONE) {
                continue;
            }
            entries.add(new Entry(i, decode(Arrays.copyOfRange(stream, offset, offset + length))));
        }
        if (entries.size() != header.live()) {
            throw new IllegalStateException("Corrupt UTXO records: the header counts " + header.live() + " live records, the stream holds " + entries.size());
        }
        return entries;
    }

    /** Appends the UTXO and returns its physical position. */
    public int append(UTXO utxo) {
        byte[] record = encode(utxo);
        Header header = header();
        int length = header.physical() == 0 ? record.length : header.recordLength();
        if (record.length != length) {
            throw new IllegalStateException("A UTXO record of " + record.length + " bytes does not fit a list of " + length + "-byte records");
        }
        int index = Math.toIntExact(header.physical());
        writeBytes(index * length, record);
        writeHeader(new Header(header.live() + 1, header.physical() + 1, length));
        return index;
    }

    /**
     * Marks the records at the given physical positions dead. Returns true when the list was compacted afterwards,
     * which renumbers the surviving records.
     */
    public boolean remove(Collection<Integer> physicalIndexes) {
        if (physicalIndexes.isEmpty()) {
            return false;
        }
        Header header = header();
        long removed = 0;
        for (int index : physicalIndexes) {
            if (index < 0 || index >= header.physical()) {
                throw new IndexOutOfBoundsException("No UTXO record at physical position " + index);
            }
            if (markDead(index * header.recordLength() + TOMBSTONE_OFFSET)) {
                removed++;
            }
        }
        long live = header.live() - removed;
        writeHeader(new Header(live, header.physical(), header.recordLength()));
        if (live * 2 < header.physical()) {
            compact();
            return true;
        }
        return false;
    }

    /** Removes every record and zeroes every slot the list used. */
    public void clear() {
        Header header = header();
        int slots = slotsFor(Math.toIntExact(header.physical()) * header.recordLength());
        for (int i = 0; i < slots; i++) {
            storage.putSlot(slot(i), UInt256.ZERO);
        }
        writeHeader(Header.EMPTY);
    }

    private void compact() {
        Header header = header();
        int length = header.recordLength();
        int physical = Math.toIntExact(header.physical());
        byte[] stream = readBytes(0, physical * length);
        ByteArrayOutputStream survivors = new ByteArrayOutputStream();
        for (int i = 0; i < physical; i++) {
            int offset = i * length;
            if (stream[offset + TOMBSTONE_OFFSET] != TOMBSTONE) {
                survivors.write(stream, offset, length);
            }
        }
        byte[] compacted = survivors.toByteArray();
        int live = compacted.length / length;
        // Rewrite the survivors padded to a slot boundary, so no byte of a dead record outlives the compaction.
        int usedSlots = slotsFor(compacted.length);
        writeBytes(0, Arrays.copyOf(compacted, usedSlots * SLOT));
        for (int i = usedSlots; i < slotsFor(physical * length); i++) {
            storage.putSlot(slot(i), UInt256.ZERO);
        }
        writeHeader(new Header(live, live, length));
    }

    private void writeHeader(Header header) {
        if (header.physical() == 0) {
            storage.putSlot(base, UInt256.ZERO);
            return;
        }
        MutableBytes32 encoded = MutableBytes32.create();
        encoded.set(LIVE_OFFSET, Bytes.ofUnsignedLong(header.live()));
        encoded.set(PHYSICAL_OFFSET, Bytes.ofUnsignedLong(header.physical()));
        encoded.set(LENGTH_OFFSET, Bytes.ofUnsignedInt(header.recordLength()));
        storage.putSlot(base, UInt256.fromBytes(encoded));
    }

    private UInt256 slot(int index) {
        return base.add(1 + index);
    }

    private static int slotsFor(int bytes) {
        return (bytes + SLOT - 1) / SLOT;
    }

    private byte[] readBytes(int offset, int length) {
        if (length == 0) {
            return new byte[0];
        }
        int first = offset / SLOT;
        int last = (offset + length - 1) / SLOT;
        byte[] window = new byte[(last - first + 1) * SLOT];
        for (int i = first; i <= last; i++) {
            storage.getSlot(slot(i)).toBytes().copyTo(org.apache.tuweni.bytes.MutableBytes.wrap(window, (i - first) * SLOT, SLOT));
        }
        int start = offset - first * SLOT;
        return Arrays.copyOfRange(window, start, start + length);
    }

    private void writeBytes(int offset, byte[] data) {
        if (data.length == 0) {
            return;
        }
        int first = offset / SLOT;
        int last = (offset + data.length - 1) / SLOT;
        byte[] window = new byte[(last - first + 1) * SLOT];
        for (int i = first; i <= last; i++) {
            storage.getSlot(slot(i)).toBytes().copyTo(org.apache.tuweni.bytes.MutableBytes.wrap(window, (i - first) * SLOT, SLOT));
        }
        System.arraycopy(data, 0, window, offset - first * SLOT, data.length);
        for (int i = first; i <= last; i++) {
            storage.putSlot(slot(i), UInt256.fromBytes(Bytes32.wrap(Arrays.copyOfRange(window, (i - first) * SLOT, (i - first + 1) * SLOT))));
        }
    }

    /** Sets the byte at the stream offset to the tombstone marker; false when it already was one. */
    private boolean markDead(int offset) {
        int index = offset / SLOT;
        byte[] slotBytes = storage.getSlot(slot(index)).toBytes().toArrayUnsafe();
        int position = offset - index * SLOT;
        if (slotBytes[position] == TOMBSTONE) {
            return false;
        }
        slotBytes[position] = TOMBSTONE;
        storage.putSlot(slot(index), UInt256.fromBytes(Bytes32.wrap(slotBytes)));
        return true;
    }

    static byte[] encode(UTXO utxo) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            utxo.serializeToStream(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to serialize UTXO " + utxo, e);
        }
    }

    static UTXO decode(byte[] record) {
        try {
            return new UTXO(new ByteArrayInputStream(record));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to deserialize a UTXO record", e);
        }
    }
}
