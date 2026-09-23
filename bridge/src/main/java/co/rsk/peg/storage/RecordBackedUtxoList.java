package co.rsk.peg.storage;

import co.rsk.bitcoinj.core.UTXO;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A federation UTXO list as the bridge code uses it, over {@link UtxoRecords}. Additions go straight to storage
 * without loading the list; the size comes from the header; reading, iterating and removing load the records once
 * and keep them for the life of the list, which is one bridge call. Removals are written through as tombstones,
 * so relative order is preserved exactly as RSKj's ArrayList preserved it.
 */
public final class RecordBackedUtxoList extends AbstractList<UTXO> {

    private final UtxoRecords records;
    private List<UtxoRecords.Entry> entries;

    public RecordBackedUtxoList(UtxoRecords records) {
        this.records = records;
    }

    private List<UtxoRecords.Entry> entries() {
        if (entries == null) {
            entries = records.loadEntries();
        }
        return entries;
    }

    @Override
    public int size() {
        return entries == null ? records.size() : entries.size();
    }

    @Override
    public UTXO get(int index) {
        return entries().get(index).utxo();
    }

    @Override
    public boolean add(UTXO utxo) {
        int physicalIndex = records.append(utxo);
        if (entries != null) {
            entries.add(new UtxoRecords.Entry(physicalIndex, utxo));
        }
        modCount++;
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends UTXO> utxos) {
        for (UTXO utxo : utxos) {
            add(utxo);
        }
        return !utxos.isEmpty();
    }

    @Override
    public UTXO remove(int index) {
        UtxoRecords.Entry entry = entries().get(index);
        removeEntries(List.of(entry));
        return entry.utxo();
    }

    @Override
    public boolean remove(Object o) {
        int index = indexOf(o);
        if (index < 0) {
            return false;
        }
        remove(index);
        return true;
    }

    @Override
    public boolean removeAll(Collection<?> toRemove) {
        return removeIf(toRemove::contains);
    }

    @Override
    public boolean retainAll(Collection<?> toKeep) {
        return removeIf(utxo -> !toKeep.contains(utxo));
    }

    @Override
    public boolean removeIf(Predicate<? super UTXO> filter) {
        List<UtxoRecords.Entry> matching = new ArrayList<>();
        for (UtxoRecords.Entry entry : entries()) {
            if (filter.test(entry.utxo())) {
                matching.add(entry);
            }
        }
        if (matching.isEmpty()) {
            return false;
        }
        removeEntries(matching);
        return true;
    }

    @Override
    public void clear() {
        records.clear();
        entries = new ArrayList<>();
        modCount++;
    }

    private void removeEntries(List<UtxoRecords.Entry> toRemove) {
        Set<Integer> positions = new HashSet<>();
        for (UtxoRecords.Entry entry : toRemove) {
            positions.add(entry.physicalIndex());
        }
        boolean compacted = records.remove(positions);
        if (compacted) {
            entries = null; // positions changed; reload on the next read
        } else {
            entries.removeIf(entry -> positions.contains(entry.physicalIndex()));
        }
        modCount++;
    }
}
