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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import co.rsk.federate.adapter.ThinConverter;
import co.rsk.peg.federation.Federation;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.listeners.BlocksDownloadedEventListener;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.bitcoinj.wallet.listeners.WalletCoinsSentEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A bitcoinj peer, told which federations to watch. */
public class BitcoinWrapperImpl implements BitcoinWrapper {

    private static final Logger logger = LoggerFactory.getLogger(BitcoinWrapperImpl.class);

    private record FederationListener(Federation federation, TransactionListener listener) {
        @Override
        public boolean equals(Object o) {
            // Identity on the listener, so that the same federation watched by two clients is two
            // entries rather than one.
            return o instanceof FederationListener other
                && other.federation().equals(federation)
                && other.listener() == listener;
        }

        @Override
        public int hashCode() {
            return Objects.hash(federation, System.identityHashCode(listener));
        }
    }

    private final Context btcContext;
    private final Kit kit;

    private final List<FederationListener> watchedFederations = new LinkedList<>();
    private final List<BlockListener> blockListeners = new LinkedList<>();

    private boolean running;

    public BitcoinWrapperImpl(Context btcContext, Kit kit) {
        this.btcContext = Objects.requireNonNull(btcContext, "btcContext");
        this.kit = Objects.requireNonNull(kit, "kit");
    }

    @Override
    public void setup(List<PeerAddress> peerAddresses) {
        BlocksDownloadedEventListener blocksDownloaded = (peer, block, filteredBlock, blocksLeft) -> {
            if (block == null || block.getTransactions() == null || block.getTransactions().isEmpty()) {
                // Headers only, which happens while catching up before the wallet's creation time.
                return;
            }
            Context.propagate(btcContext);
            for (BlockListener listener : List.copyOf(blockListeners)) {
                listener.onBlock(block);
            }
        };
        WalletCoinsReceivedEventListener received = (wallet, tx, prev, now) -> coinsReceivedOrSent(tx);
        WalletCoinsSentEventListener sent = (wallet, tx, prev, now) -> coinsReceivedOrSent(tx);

        kit.setup(blocksDownloaded, received, sent);

        if (!peerAddresses.isEmpty()) {
            kit.setPeerNodes(peerAddresses.toArray(new PeerAddress[0]));
        }
    }

    @Override
    public void start() {
        Context.propagate(btcContext);
        kit.startAsync().awaitRunning();
        running = true;
    }

    @Override
    public void stop() {
        Context.propagate(btcContext);
        kit.stopAsync().awaitTerminated();
        running = false;
    }

    @Override
    public int getBestChainHeight() {
        return kit.chain().getBestChainHeight();
    }

    @Override
    public StoredBlock getChainHead() {
        return kit.chain().getChainHead();
    }

    @Override
    public StoredBlock getBlock(Sha256Hash hash) throws BlockStoreException {
        return kit.store().get(hash);
    }

    @Override
    public StoredBlock getBlockAtHeight(int height) throws BlockStoreException {
        return blockAtHeight(kit.store(), height);
    }

    /**
     * The best chain's block at a height, found by walking back from the head.
     *
     * <p>Walking is what makes it the best chain's block rather than any stored block claiming that
     * height: after a fork the store holds both, and only one is an ancestor of the head. Asking
     * the store by height would sometimes return the other, and the client uses this to decide
     * whether the bridge's chain and this peer's agree.
     */
    static StoredBlock blockAtHeight(BlockStore store, int height) throws BlockStoreException {
        StoredBlock cursor = store.getChainHead();
        if (cursor == null || height > cursor.getHeight() || height < 0) {
            return null;
        }

        for (int i = cursor.getHeight(); i > height; i--) {
            cursor = store.get(cursor.getHeader().getPrevBlockHash());
            if (cursor == null) {
                logger.warn("[blockAtHeight] The store stops short of height {}", height);
                return null;
            }
        }

        if (cursor.getHeight() != height) {
            throw new IllegalStateException(
                String.format("Walked to height %d looking for %d, at block %s",
                    cursor.getHeight(), height, cursor.getHeader().getHash()));
        }
        return cursor;
    }

    @Override
    public Set<Transaction> getTransactions(int minConfirmations) {
        Set<Transaction> confirmed = new HashSet<>();
        for (Transaction tx : kit.wallet().getTransactions(false)) {
            TransactionConfidence confidence = tx.getConfidence();
            if (confidence.getConfidenceType() != ConfidenceType.BUILDING
                || confidence.getDepthInBlocks() < minConfirmations) {
                continue;
            }
            confirmed.add(tx);
        }
        return confirmed;
    }

    @Override
    public Map<Sha256Hash, Transaction> getTransactionMap(int minConfirmations) {
        Map<Sha256Hash, Transaction> byWtxid = new HashMap<>();
        for (Transaction tx : getTransactions(minConfirmations)) {
            byWtxid.put(tx.getWTxId(), tx);
        }
        return byWtxid;
    }

    @Override
    public synchronized void addFederationListener(Federation federation, TransactionListener listener) {
        if (!running) {
            logger.debug("[addFederationListener] Not running; cannot watch {}", federation.getAddress());
            return;
        }

        FederationListener entry = new FederationListener(federation, listener);
        if (watchedFederations.stream().noneMatch(w -> w.federation().equals(federation))) {
            Address address = addressOf(federation);
            kit.wallet().addWatchedAddress(address, federation.getCreationTime().getEpochSecond());
            logger.info("[addFederationListener] Watching federation address {}", address);
        }
        if (!watchedFederations.contains(entry)) {
            watchedFederations.add(entry);
        }
    }

    @Override
    public synchronized void removeFederationListener(Federation federation, TransactionListener listener) {
        if (!running) {
            return;
        }

        watchedFederations.remove(new FederationListener(federation, listener));

        if (watchedFederations.stream().noneMatch(w -> w.federation().equals(federation))) {
            Script script = ScriptBuilder.createOutputScript(addressOf(federation));
            kit.wallet().removeWatchedScripts(List.of(script));
            logger.info("[removeFederationListener] Stopped watching {}", federation.getAddress());
        }
    }

    @Override
    public void addBlockListener(BlockListener listener) {
        blockListeners.add(listener);
    }

    @Override
    public void removeBlockListener(BlockListener listener) {
        blockListeners.remove(listener);
    }

    /**
     * Decides which watchers care about a transaction the wallet just saw.
     *
     * <p>Powpeg classified it here, asking whether it was a valid peg-in or a peg-out before passing
     * it on. Those checks read the protocol version that was active at the time and no longer exist:
     * with every rule active from genesis the bridge accepts any transaction that pays the
     * federation and decides for itself, so classifying here would only be a second opinion that
     * can disagree. What is left is the question a watcher actually asked: does this transaction
     * touch the address I am watching.
     */
    private void coinsReceivedOrSent(Transaction tx) {
        List<FederationListener> watching;
        synchronized (this) {
            if (watchedFederations.isEmpty()) {
                return;
            }
            watching = List.copyOf(watchedFederations);
        }

        Context.propagate(btcContext);
        for (FederationListener watched : watching) {
            if (touches(tx, addressOf(watched.federation()))) {
                logger.debug("[coinsReceivedOrSent] {} (wtxid {}) touches {}",
                    tx.getTxId(), tx.getWTxId(), watched.federation().getAddress());
                watched.listener().onTransaction(tx);
            }
        }
    }

    /** True if the transaction pays this address, or spends something that paid it. */
    private static boolean touches(Transaction tx, Address address) {
        byte[] expected = ScriptBuilder.createOutputScript(address).getProgram();

        for (TransactionOutput output : tx.getOutputs()) {
            if (Arrays.equals(output.getScriptPubKey().getProgram(), expected)) {
                return true;
            }
        }
        for (TransactionInput input : tx.getInputs()) {
            TransactionOutput spent = input.getConnectedOutput();
            if (spent != null && Arrays.equals(spent.getScriptPubKey().getProgram(), expected)) {
                return true;
            }
        }
        return false;
    }

    private Address addressOf(Federation federation) {
        return ThinConverter.toOriginal(kit.params(), federation.getAddress());
    }

    /** The federations currently being watched, for tests and for logging. */
    synchronized Collection<Federation> watchedFederations() {
        List<Federation> federations = new ArrayList<>();
        for (FederationListener watched : watchedFederations) {
            federations.add(watched.federation());
        }
        return federations;
    }
}
