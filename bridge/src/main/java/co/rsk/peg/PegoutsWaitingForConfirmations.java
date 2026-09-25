/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.peg;

import co.rsk.bitcoinj.core.BtcTransaction;
import org.hyperledger.besu.datatypes.Hash;
import com.google.common.primitives.UnsignedBytes;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Representation of a queue of BTC release
 * transactions waiting for confirmations
 * on the rsk network.
 *
 * @author Ariel Mendelzon
 */
public class PegoutsWaitingForConfirmations {
    public static class Entry {
        // Compares entries using the lexicographical order of the btc tx's serialized bytes
        public static final Comparator<Entry> BTC_TX_COMPARATOR = new Comparator<Entry>() {
            private Comparator<byte[]> comparator = UnsignedBytes.lexicographicalComparator();

            @Override
            public int compare(Entry e1, Entry e2) {
                return comparator.compare(e1.getBtcTransaction().bitcoinSerialize(), e2.getBtcTransaction().bitcoinSerialize());
            }
        };

        private BtcTransaction btcTransaction;
        private Long pegoutCreationRskBlockNumber;
        private Hash pegoutCreationRskTxHash;

        public Entry(BtcTransaction btcTransaction, Long pegoutCreationRskBlockNumber, Hash pegoutCreationRskTxHash) {
            this.btcTransaction = btcTransaction;
            this.pegoutCreationRskBlockNumber = pegoutCreationRskBlockNumber;
            this.pegoutCreationRskTxHash = pegoutCreationRskTxHash;
        }

        public Entry(BtcTransaction btcTransaction, Long pegoutCreationRskBlockNumber) { this(btcTransaction, pegoutCreationRskBlockNumber, null); }

        public BtcTransaction getBtcTransaction() {
            return btcTransaction;
        }

        public Long getPegoutCreationRskBlockNumber() {
            return pegoutCreationRskBlockNumber;
        }

        public Hash getPegoutCreationRskTxHash() { return pegoutCreationRskTxHash; }

        @Override
        public boolean equals(Object o) {
            if (o == null || this.getClass() != o.getClass()) {
                return false;
            }

            Entry otherEntry = (Entry) o;
            return otherEntry.getBtcTransaction().equals(getBtcTransaction()) &&
                otherEntry.getPegoutCreationRskBlockNumber().equals(getPegoutCreationRskBlockNumber()) &&
                (otherEntry.getPegoutCreationRskTxHash() == null && getPegoutCreationRskTxHash() == null ||
                    otherEntry.getPegoutCreationRskTxHash() != null && otherEntry.getPegoutCreationRskTxHash().equals(getPegoutCreationRskTxHash()));
        }

        @Override
        public int hashCode() {
            return Objects.hash(getBtcTransaction(), getPegoutCreationRskBlockNumber());
        }
    }

    private Set<Entry> entries;

    /** Loads the entries from storage the first time something actually needs them. */
    private final Supplier<Set<Entry>> loader;

    /**
     * The lowest creation block among the stored entries, when the caller knows it without loading them.
     * An entry needs {@code currentBlockNumber - creationBlock >= minimumConfirmations} to be confirmed, so the
     * entry created earliest is the first that can be, and if that one cannot be then none can. That is what lets
     * a call that finds nothing confirmed avoid reading the list at all, which is the common case: a pegout is
     * batched every few thousand blocks and waits tens of thousands more.
     */
    private final OptionalLong earliestCreationBlock;

    public PegoutsWaitingForConfirmations(Set<Entry> entries) {
        this.entries = new HashSet<>(entries);
        this.loader = null;
        this.earliestCreationBlock = OptionalLong.empty();
    }

    /**
     * A set that reads itself from storage when it is first used. The hint must be the lowest creation block
     * among the stored entries, or empty when it is not known; an absent or wrong-but-lower hint only costs a
     * load, while a hint above the true minimum would hide a confirmed pegout, so the writer computes it from
     * the entries themselves every time it stores them.
     */
    public PegoutsWaitingForConfirmations(Supplier<Set<Entry>> loader, OptionalLong earliestCreationBlock) {
        this.entries = null;
        this.loader = loader;
        this.earliestCreationBlock = earliestCreationBlock;
    }

    private Set<Entry> entries() {
        if (entries == null) {
            entries = new HashSet<>(loader.get());
        }
        return entries;
    }

    /** True once the entries have been read, so a caller that never needed them does not store them back. */
    public boolean isLoaded() {
        return entries != null;
    }

    /** The lowest creation block among the entries, which is what a writer stores as the hint. */
    public OptionalLong earliestCreationBlock() {
        return entries().stream().mapToLong(Entry::getPegoutCreationRskBlockNumber).min();
    }

    public Set<Entry> getEntriesWithoutHash() {
        return entries().stream().filter(e -> e.getPegoutCreationRskTxHash() == null).collect(Collectors.toSet());
    }

    public Set<Entry> getEntriesWithHash() {
        return entries().stream().filter(e -> e.getPegoutCreationRskTxHash() != null).collect(Collectors.toSet());
    }

    public Set<Entry> getEntries() {
        return new HashSet<>(entries());
    }

    public void add(BtcTransaction transaction, Long blockNumber) {
        add(transaction, blockNumber, null);
    }

    public void add(BtcTransaction transaction, Long blockNumber, Hash rskTxHash) {
        if (entries().stream().noneMatch(e -> e.getBtcTransaction().equals(transaction))) {
            entries().add(new Entry(transaction, blockNumber, rskTxHash));
        }
    }

    /**
     * Given a block number and a minimum number of confirmations,
     * returns a subset of transactions within the set that have
     * at least that number of confirmations.
     * Optionally supply a maximum slice size to limit the output
     * size.
     * Sliced items are also removed from the set (thus the name, slice).
     * @param currentBlockNumber the current execution block number (height).
     * @param minimumConfirmations the minimum desired confirmations for the slice elements.
     * @return an optional with an entry with enough confirmations if found. If not, an empty optional.
     */
    public Optional<Entry> getNextPegoutWithEnoughConfirmations(Long currentBlockNumber, Integer minimumConfirmations) {
        if (entries == null
            && earliestCreationBlock.isPresent()
            && currentBlockNumber - earliestCreationBlock.getAsLong() < minimumConfirmations) {
            // the entry created earliest is not confirmed yet, so none of them is
            return Optional.empty();
        }
        return entries().stream().filter(entry -> hasEnoughConfirmations(entry, currentBlockNumber, minimumConfirmations)).findFirst();
    }

    public boolean removeEntry(Entry entry){
        return entries().remove(entry);
    }

    private boolean hasEnoughConfirmations(Entry entry, Long currentBlockNumber, Integer minimumConfirmations) {
        return (currentBlockNumber - entry.getPegoutCreationRskBlockNumber()) >= minimumConfirmations;
    }
}
