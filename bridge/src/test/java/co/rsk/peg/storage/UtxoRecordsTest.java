package co.rsk.peg.storage;

import static co.rsk.peg.storage.UtxoTestSupport.P2PKH_SCRIPT;
import static co.rsk.peg.storage.UtxoTestSupport.utxo;
import static co.rsk.peg.storage.UtxoTestSupport.utxos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.core.UTXO;
import co.rsk.peg.host.InMemoryBridgeHost;
import org.hyperledger.besu.datatypes.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

class UtxoRecordsTest {

    private static final Bytes32 KEY = Bytes32.leftPad(Bytes.wrap("newFederationBtcUTXOs".getBytes()));
    private static final int RECORD = 76;
    private static final int SLOT = 32;

    private InMemoryBridgeHost host;
    private UtxoRecords records;

    @BeforeEach
    void setUp() {
        host = new InMemoryBridgeHost();
        records = new UtxoRecords(new BridgeStorageAccessorImpl(host), KEY);
    }

    private static int slotsFor(int records) {
        return 1 + (records * RECORD + SLOT - 1) / SLOT;
    }

    private static List<UTXO> utxosOf(List<UtxoRecords.Entry> entries) {
        return entries.stream().map(UtxoRecords.Entry::utxo).toList();
    }

    @Test
    void emptyListHasNoHeaderAndNoSlots() {
        assertEquals(UtxoRecords.Header.EMPTY, records.header());
        assertEquals(0, records.size());
        assertEquals(List.of(), records.loadEntries());
        assertTrue(host.slots().isEmpty());
    }

    @Test
    void appendsThenLoadsInInsertionOrder() {
        for (UTXO utxo : utxos(0, 3)) {
            records.append(utxo);
        }
        assertEquals(new UtxoRecords.Header(3, 3, RECORD), records.header());
        assertEquals(utxos(0, 3), utxosOf(records.loadEntries()));
        assertEquals(List.of(0, 1, 2), records.loadEntries().stream().map(UtxoRecords.Entry::physicalIndex).toList());
        assertEquals(slotsFor(3), host.slots().size());
    }

    @Test
    void slotsAreSequentialFromOneKeccakOfTheKey() {
        records.append(utxo(0));
        UInt256 base = UInt256.fromBytes(Hash.hash(KEY).getBytes());
        assertEquals(Set.of(base, base.add(1), base.add(2), base.add(3)), host.slots().keySet());
    }

    @Test
    void appendTouchesOnlyTheRecordSlotsAndTheHeader() {
        for (UTXO utxo : utxos(0, 40)) {
            records.append(utxo);
        }
        host.resetSlotCounters();
        records.append(utxo(40));
        assertTrue(host.slotWrites() <= 5, "writes: " + host.slotWrites());
        assertTrue(host.slotReads() <= 12, "reads: " + host.slotReads());
        assertEquals(41, records.size());
    }

    @Test
    void removalMarksRecordsDeadAndKeepsOrder() {
        for (UTXO utxo : utxos(0, 6)) {
            records.append(utxo);
        }
        host.resetSlotCounters();
        assertFalse(records.remove(List.of(1, 3)));
        assertTrue(host.slotWrites() <= 3, "writes: " + host.slotWrites());
        assertEquals(new UtxoRecords.Header(4, 6, RECORD), records.header());
        assertEquals(List.of(utxo(0), utxo(2), utxo(4), utxo(5)), utxosOf(records.loadEntries()));
        assertEquals(List.of(0, 2, 4, 5), records.loadEntries().stream().map(UtxoRecords.Entry::physicalIndex).toList());
        assertEquals(slotsFor(6), host.slots().size());
    }

    @Test
    void removingADeadRecordAgainChangesNothing() {
        for (UTXO utxo : utxos(0, 4)) {
            records.append(utxo);
        }
        records.remove(List.of(1));
        host.resetSlotCounters();
        records.remove(List.of(1));
        assertEquals(0, host.slotWrites());
        assertEquals(new UtxoRecords.Header(3, 4, RECORD), records.header());
    }

    @Test
    void compactsInOrderWhenFewerThanHalfAreAlive() {
        for (UTXO utxo : utxos(0, 6)) {
            records.append(utxo);
        }
        assertTrue(records.remove(List.of(0, 1, 2, 4)));
        assertEquals(new UtxoRecords.Header(2, 2, RECORD), records.header());
        assertEquals(List.of(utxo(3), utxo(5)), utxosOf(records.loadEntries()));
        assertEquals(List.of(0, 1), records.loadEntries().stream().map(UtxoRecords.Entry::physicalIndex).toList());
        // exactly the slots a fresh two-record list would use, nothing left behind
        assertEquals(slotsFor(2), host.slots().size());
        InMemoryBridgeHost fresh = new InMemoryBridgeHost();
        UtxoRecords freshRecords = new UtxoRecords(new BridgeStorageAccessorImpl(fresh), KEY);
        freshRecords.append(utxo(3));
        freshRecords.append(utxo(5));
        assertEquals(fresh.slots(), host.slots());
    }

    @Test
    void appendsAfterCompactionReuseTheFreedSpace() {
        for (UTXO utxo : utxos(0, 6)) {
            records.append(utxo);
        }
        records.remove(List.of(0, 1, 2, 3));
        records.append(utxo(6));
        assertEquals(new UtxoRecords.Header(3, 3, RECORD), records.header());
        assertEquals(List.of(utxo(4), utxo(5), utxo(6)), utxosOf(records.loadEntries()));
    }

    @Test
    void clearZeroesEveryUsedSlot() {
        for (UTXO utxo : utxos(0, 9)) {
            records.append(utxo);
        }
        records.remove(List.of(2));
        records.clear();
        assertTrue(host.slots().isEmpty());
        assertEquals(UtxoRecords.Header.EMPTY, records.header());
        records.append(utxo(1));
        assertEquals(List.of(utxo(1)), utxosOf(records.loadEntries()));
    }

    @Test
    void recordLengthIsFixedPerList() {
        records.append(utxo(0));
        assertThrows(IllegalStateException.class, () -> records.append(utxo(1, P2PKH_SCRIPT)));
        // a list started with the longer record accepts only that length
        UtxoRecords other = new UtxoRecords(new BridgeStorageAccessorImpl(new InMemoryBridgeHost()), KEY);
        other.append(utxo(1, P2PKH_SCRIPT));
        assertEquals(78, other.header().recordLength());
        assertThrows(IllegalStateException.class, () -> other.append(utxo(2)));
    }

    @Test
    void tombstoneByteIsNeverSetInARealRecord() {
        UTXO maxMoney = new UTXO(Sha256Hash.ZERO_HASH, 0, NetworkParameters.MAX_MONEY, 0, false, UtxoTestSupport.P2SH_SCRIPT);
        assertEquals(0, UtxoRecords.encode(maxMoney)[7]);
        assertEquals(0, UtxoRecords.encode(utxo(7))[7]);
        assertEquals(RECORD, UtxoRecords.encode(utxo(7)).length);
    }

    @Test
    void removalOutsideTheListIsRejected() {
        records.append(utxo(0));
        assertThrows(IndexOutOfBoundsException.class, () -> records.remove(List.of(1)));
        assertThrows(IndexOutOfBoundsException.class, () -> records.remove(List.of(-1)));
    }

    @Test
    void largeListsRoundTrip() {
        List<UTXO> all = utxos(0, 300);
        for (UTXO utxo : all) {
            records.append(utxo);
        }
        assertEquals(all, utxosOf(records.loadEntries()));
        assertEquals(slotsFor(300), host.slots().size());
        records.remove(List.of(0, 150, 299));
        List<UTXO> expected = new java.util.ArrayList<>(all);
        expected.removeAll(List.of(utxo(0), utxo(150), utxo(299)));
        assertEquals(expected, utxosOf(records.loadEntries()));
    }

    @Test
    void valueOfCoinIsNotConfusedWithATombstone() {
        // the 8-byte little-endian value starts a record; the marker sits in its most significant byte
        records.append(new UTXO(Sha256Hash.of(new byte[] {9}), 0, Coin.valueOf(0x00ffffffffffffffL & 0x0007ffffffffffffL), 1, false, UtxoTestSupport.P2SH_SCRIPT));
        assertEquals(1, records.loadEntries().size());
    }
}
