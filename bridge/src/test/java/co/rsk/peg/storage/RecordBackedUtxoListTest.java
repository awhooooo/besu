package co.rsk.peg.storage;

import static co.rsk.peg.storage.UtxoTestSupport.utxo;
import static co.rsk.peg.storage.UtxoTestSupport.utxos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.rsk.bitcoinj.core.UTXO;
import co.rsk.peg.host.InMemoryBridgeHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

class RecordBackedUtxoListTest {

    private static final Bytes32 KEY = Bytes32.leftPad(Bytes.wrap("oldFederationBtcUTXOs".getBytes()));

    private InMemoryBridgeHost host;
    private RecordBackedUtxoList list;

    @BeforeEach
    void setUp() {
        host = new InMemoryBridgeHost();
        list = new RecordBackedUtxoList(new UtxoRecords(new BridgeStorageAccessorImpl(host), KEY));
    }

    @Test
    void addingAndSizingDoNotLoadTheList() {
        for (UTXO utxo : utxos(0, 50)) {
            list.add(utxo);
        }
        host.resetSlotCounters();
        list.add(utxo(50));
        assertEquals(51, list.size());
        assertTrue(host.slotReads() <= 12, "reads: " + host.slotReads());
        assertTrue(host.slotWrites() <= 5, "writes: " + host.slotWrites());
        assertFalse(list.isEmpty());
    }

    @Test
    void behavesLikeAnArrayListForReads() {
        list.addAll(utxos(0, 7));
        List<UTXO> expected = new ArrayList<>(utxos(0, 7));
        assertEquals(expected, list);
        assertEquals(expected, List.copyOf(list));
        assertEquals(expected.subList(2, 5), list.subList(2, 5));
        assertEquals(utxo(3), list.get(3));
        assertEquals(3, list.indexOf(utxo(3)));
        assertTrue(list.contains(utxo(6)));
        assertEquals(expected, list.stream().toList());
        assertEquals(expected.hashCode(), list.hashCode());
    }

    @Test
    void removeAllKeepsTheOrderOfTheRest() {
        list.addAll(utxos(0, 8));
        assertTrue(list.removeAll(List.of(utxo(1), utxo(5), utxo(9))));
        assertEquals(List.of(utxo(0), utxo(2), utxo(3), utxo(4), utxo(6), utxo(7)), list);
        assertFalse(list.removeAll(List.of(utxo(9))));
        // a new list over the same storage sees the same content
        assertEquals(list, new RecordBackedUtxoList(new UtxoRecords(new BridgeStorageAccessorImpl(host), KEY)));
    }

    @Test
    void removeIfRemoveObjectAndIteratorRemoveWork() {
        list.addAll(utxos(0, 6));
        assertTrue(list.removeIf(utxo -> utxo.getIndex() == 0)); // seeds 0 and 4
        assertEquals(List.of(utxo(1), utxo(2), utxo(3), utxo(5)), list);
        assertTrue(list.remove(utxo(2)));
        assertFalse(list.remove(utxo(2)));
        Iterator<UTXO> iterator = list.iterator();
        iterator.next();
        iterator.remove();
        assertEquals(List.of(utxo(3), utxo(5)), list);
        assertEquals(List.of(utxo(3), utxo(5)), new RecordBackedUtxoList(new UtxoRecords(new BridgeStorageAccessorImpl(host), KEY)));
    }

    @Test
    void compactionMidwayKeepsContentAndOrder() {
        list.addAll(utxos(0, 10));
        assertTrue(list.removeAll(utxos(0, 7)));
        assertEquals(utxos(7, 10), list);
        list.add(utxo(10));
        assertTrue(list.remove(utxo(8)));
        assertEquals(List.of(utxo(7), utxo(9), utxo(10)), list);
        assertEquals(List.of(utxo(7), utxo(9), utxo(10)), new RecordBackedUtxoList(new UtxoRecords(new BridgeStorageAccessorImpl(host), KEY)));
    }

    @Test
    void clearThenAddAllReplacesTheContent() {
        list.addAll(utxos(0, 5));
        list.clear();
        assertTrue(list.isEmpty());
        assertTrue(host.slots().isEmpty());
        list.addAll(utxos(5, 8));
        assertEquals(utxos(5, 8), list);
        assertEquals(utxos(5, 8), new RecordBackedUtxoList(new UtxoRecords(new BridgeStorageAccessorImpl(host), KEY)));
    }

    @Test
    void moveBetweenListsAsTheFederationChangeDoes() {
        RecordBackedUtxoList newList = list;
        RecordBackedUtxoList oldList = new RecordBackedUtxoList(new UtxoRecords(new BridgeStorageAccessorImpl(host), Bytes32.leftPad(Bytes.wrap("newFederationBtcUTXOs".getBytes()))));
        newList.addAll(utxos(0, 4));
        List<UTXO> active = List.copyOf(newList);
        newList.clear();
        oldList.clear();
        oldList.addAll(active);
        assertEquals(utxos(0, 4), oldList);
        assertTrue(newList.isEmpty());
    }
}
