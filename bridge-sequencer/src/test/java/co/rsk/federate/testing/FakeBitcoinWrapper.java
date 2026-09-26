package co.rsk.federate.testing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import co.rsk.federate.bitcoin.BitcoinWrapper;
import co.rsk.federate.bitcoin.BlockListener;
import co.rsk.federate.bitcoin.TransactionListener;
import co.rsk.peg.federation.Federation;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;

/** A Bitcoin peer whose chain the test lays out by hand. */
public class FakeBitcoinWrapper implements BitcoinWrapper {

    private final List<BlockListener> blockListeners = new ArrayList<>();
    private final Map<Federation, List<TransactionListener>> federationListeners = new LinkedHashMap<>();

    /** Best chain, height 0 upward. */
    private final List<StoredBlock> bestChain = new ArrayList<>();
    /** Everything stored, including blocks that lost a fork. */
    private final Map<Sha256Hash, StoredBlock> stored = new HashMap<>();
    private final Map<Sha256Hash, Transaction> confirmed = new LinkedHashMap<>();

    /** Appends a block to the best chain and stores it. */
    public StoredBlock appendToBestChain(Block block) {
        int height = bestChain.size();
        StoredBlock storedBlock = new StoredBlock(block.cloneAsHeader(), block.getWork(), height);
        bestChain.add(storedBlock);
        stored.put(block.getHash(), storedBlock);
        return storedBlock;
    }

    /** Stores a block without putting it on the best chain: a block that lost a fork. */
    public StoredBlock storeOffChain(Block block, int height) {
        StoredBlock storedBlock = new StoredBlock(block.cloneAsHeader(), block.getWork(), height);
        stored.put(block.getHash(), storedBlock);
        return storedBlock;
    }

    /** Makes a transaction visible to {@link #getTransactionMap}, as if deeply confirmed. */
    public void confirm(Transaction tx) {
        confirmed.put(tx.getWTxId(), tx);
    }

    public void unconfirm(Transaction tx) {
        confirmed.remove(tx.getWTxId());
    }

    /** Delivers a block to whoever is listening, as the peer would on download. */
    public void deliver(Block block) {
        for (BlockListener listener : List.copyOf(blockListeners)) {
            listener.onBlock(block);
        }
    }

    /** Delivers a wallet transaction to the listeners of a federation. */
    public void deliver(Federation federation, Transaction tx) {
        for (TransactionListener listener : List.copyOf(federationListeners.getOrDefault(federation, List.of()))) {
            listener.onTransaction(tx);
        }
    }

    public boolean isWatching(Federation federation) {
        return !federationListeners.getOrDefault(federation, List.of()).isEmpty();
    }

    public boolean hasBlockListeners() {
        return !blockListeners.isEmpty();
    }

    @Override
    public void setup(List<PeerAddress> peerAddresses) {
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public int getBestChainHeight() {
        return bestChain.size() - 1;
    }

    @Override
    public StoredBlock getChainHead() {
        return bestChain.get(bestChain.size() - 1);
    }

    @Override
    public StoredBlock getBlock(Sha256Hash hash) {
        return stored.get(hash);
    }

    @Override
    public StoredBlock getBlockAtHeight(int height) {
        return height >= 0 && height < bestChain.size() ? bestChain.get(height) : null;
    }

    @Override
    public Map<Sha256Hash, Transaction> getTransactionMap(int minConfirmations) {
        return new LinkedHashMap<>(confirmed);
    }

    @Override
    public Set<Transaction> getTransactions(int minConfirmations) {
        return new HashSet<>(confirmed.values());
    }

    @Override
    public void addFederationListener(Federation federation, TransactionListener listener) {
        federationListeners.computeIfAbsent(federation, key -> new ArrayList<>()).add(listener);
    }

    @Override
    public void removeFederationListener(Federation federation, TransactionListener listener) {
        federationListeners.getOrDefault(federation, new ArrayList<>()).remove(listener);
    }

    @Override
    public void addBlockListener(BlockListener listener) {
        blockListeners.add(listener);
    }

    @Override
    public void removeBlockListener(BlockListener listener) {
        blockListeners.remove(listener);
    }
}
