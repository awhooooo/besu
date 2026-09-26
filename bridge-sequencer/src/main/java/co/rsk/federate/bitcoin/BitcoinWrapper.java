/*
 * This file is part of RskJ
 * Copyright (C) 2018 RSK Labs Ltd.
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
package co.rsk.federate.bitcoin;

import java.util.List;
import java.util.Map;
import java.util.Set;

import co.rsk.peg.federation.Federation;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.store.BlockStoreException;

/** The Bitcoin side of the sequencer: a peer with a header chain and a watch-only wallet. */
public interface BitcoinWrapper {

    void setup(List<PeerAddress> peerAddresses);

    void start();

    void stop();

    int getBestChainHeight();

    StoredBlock getChainHead();

    /** The stored block with this hash, or null if the peer has never stored it. */
    StoredBlock getBlock(Sha256Hash hash) throws BlockStoreException;

    /** The block at this height on the peer's best chain, or null if it has none. */
    StoredBlock getBlockAtHeight(int height) throws BlockStoreException;

    /** Wallet transactions buried under at least this many blocks, by wtxid. */
    Map<Sha256Hash, Transaction> getTransactionMap(int minConfirmations);

    Set<Transaction> getTransactions(int minConfirmations);

    void addFederationListener(Federation federation, TransactionListener listener);

    void removeFederationListener(Federation federation, TransactionListener listener);

    /**
     * Sends a transaction to the bitcoin peers.
     *
     * <p>Broadcasting the same transaction twice is harmless: peers that have it ignore it, and a
     * repeat is how a broadcast that failed to propagate gets another chance.
     */
    void broadcast(Transaction tx);

    void addBlockListener(BlockListener listener);

    void removeBlockListener(BlockListener listener);
}
