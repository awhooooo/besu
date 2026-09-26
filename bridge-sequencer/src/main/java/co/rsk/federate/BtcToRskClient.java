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
package co.rsk.federate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.ScriptException;
import co.rsk.bitcoinj.wallet.Wallet;
import co.rsk.federate.adapter.ThinConverter;
import co.rsk.federate.bitcoin.BitcoinWrapper;
import co.rsk.federate.bitcoin.BlockListener;
import co.rsk.federate.bitcoin.TransactionListener;
import co.rsk.federate.io.BtcToRskClientFileData;
import co.rsk.federate.io.BtcToRskClientFileReadResult;
import co.rsk.federate.io.BtcToRskClientFileStorage;
import co.rsk.peg.BridgeUtils;
import co.rsk.peg.PeginInformation;
import co.rsk.peg.btcLockSender.BtcLockSenderProvider;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.pegininstructions.PeginInstructionsException;
import co.rsk.peg.pegininstructions.PeginInstructionsProvider;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.PartialMerkleTree;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.bitcoinj.store.BlockStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells the bridge what happened on Bitcoin.
 *
 * <p>Two things arrive on their own: a transaction paying the federation, which is remembered so
 * that its proof can be built, and a block, which is where that proof comes from. Everything else
 * happens on a turn, when this federator's slot comes round: the bridge is given any headers it is
 * missing, then the coinbase of a block holding a segwit peg-in, then the peg-ins themselves, then
 * a nudge to do its own periodic work.
 *
 * <p>The order is not arbitrary. A proof is checked against a header, so the header goes first; a
 * segwit transaction is identified by a wtxid, which only the coinbase commits to, so the coinbase
 * goes before the transaction that needs it.
 */
public class BtcToRskClient implements BlockListener, TransactionListener {

    /**
     * How many peg-ins to register in one turn. A federator that has been offline could otherwise
     * try to send hundreds of transactions at once, and the bridge will still be there next turn.
     */
    static final int MAXIMUM_REGISTER_BTC_LOCK_TXS_PER_TURN = 40;

    private static final Logger logger = LoggerFactory.getLogger(BtcToRskClient.class);

    private final BitcoinWrapper bitcoinWrapper;
    private final FederatorSupport federatorSupport;
    private final BridgeConstants bridgeConstants;
    private final BtcToRskClientFileStorage storage;
    private final BtcLockSenderProvider btcLockSenderProvider;
    private final PeginInstructionsProvider peginInstructionsProvider;
    private final int amountOfHeadersToSend;
    private final int minimumConfirmationsOnRsk;

    private BtcToRskClientFileData fileData = new BtcToRskClientFileData();
    /** The federation this client acts as a member of, which decides what it may call. */
    private Federation federation;
    /**
     * Every federation whose coins this client will relay transactions for.
     *
     * <p>Usually just the one above. During a federation change the same client is also asked to
     * watch the proposed federation, because the transaction that funds the validation is paid to
     * that address and to its flyover address and to nothing else. Deciding whether to relay by
     * asking only about {@link #federation} would drop it, and dropping it stalls the change: the
     * bridge only learns the funding confirmed when somebody registers it.
     */
    private final Set<Federation> recognisedFederations = new LinkedHashSet<>();

    public BtcToRskClient(
        BitcoinWrapper bitcoinWrapper,
        FederatorSupport federatorSupport,
        BridgeConstants bridgeConstants,
        BtcToRskClientFileStorage storage,
        BtcLockSenderProvider btcLockSenderProvider,
        PeginInstructionsProvider peginInstructionsProvider,
        int amountOfHeadersToSend,
        int minimumConfirmationsOnRsk) throws IOException {
        this.bitcoinWrapper = Objects.requireNonNull(bitcoinWrapper, "bitcoinWrapper");
        this.federatorSupport = Objects.requireNonNull(federatorSupport, "federatorSupport");
        this.bridgeConstants = Objects.requireNonNull(bridgeConstants, "bridgeConstants");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.btcLockSenderProvider = Objects.requireNonNull(btcLockSenderProvider, "btcLockSenderProvider");
        this.peginInstructionsProvider =
            Objects.requireNonNull(peginInstructionsProvider, "peginInstructionsProvider");
        if (amountOfHeadersToSend <= 0) {
            throw new IllegalArgumentException("A turn must send at least one header");
        }
        this.amountOfHeadersToSend = amountOfHeadersToSend;
        this.minimumConfirmationsOnRsk = minimumConfirmationsOnRsk;
        restoreFileData();
    }

    /** Begins watching, for a federation this federator belongs to. */
    public void start(Federation federation) {
        this.federation = Objects.requireNonNull(federation, "federation");
        recognisedFederations.add(federation);
        logger.info("[start] Watching federation {}", federation.getAddress());
        bitcoinWrapper.addBlockListener(this);
        bitcoinWrapper.addFederationListener(federation, this);
    }

    /**
     * Also relay transactions paying this federation, without acting as one of its members.
     *
     * <p>For the proposed federation during a change: its funding transaction pays nobody else,
     * and registering it is open to anyone.
     */
    public void alsoRelayFor(Federation other) {
        if (recognisedFederations.add(Objects.requireNonNull(other, "other"))) {
            logger.info("[alsoRelayFor] Will also relay transactions paying {}", other.getAddress());
        }
    }

    public void stop() {
        logger.info("[stop] No longer watching");
        if (federation != null) {
            bitcoinWrapper.removeFederationListener(federation, this);
            federation = null;
        }
        recognisedFederations.clear();
        bitcoinWrapper.removeBlockListener(this);
    }

    /** One turn's work. */
    public void updateBridge() {
        if (federation == null) {
            logger.warn("[updateBridge] Skipped: no federation");
            return;
        }
        if (!federatorSupport.nodeIsUsable()) {
            logger.warn("[updateBridge] Skipped: the node is still syncing");
            return;
        }

        logger.debug("[updateBridge] Federation {}", federation.getAddress());

        // Two of the four are reserved to federation members and two are open to anyone. A
        // sequencer that is not a member can still do the open ones, and they are the ones that
        // move somebody's coins.
        boolean member = isFederationMember();

        if (member) {
            try {
                int sent = updateBridgeBtcBlockchain();
                logger.debug("[updateBridge] Sent {} headers", sent);
            } catch (Exception e) {
                logger.error("[updateBridge] Informing headers failed: {}", e.getMessage(), e);
            }
        }

        try {
            updateBridgeBtcCoinbaseTransactions();
        } catch (Exception e) {
            logger.error("[updateBridge] Informing a coinbase failed: {}", e.getMessage(), e);
        }

        try {
            updateBridgeBtcTransactions();
        } catch (Exception e) {
            logger.error("[updateBridge] Registering transactions failed: {}", e.getMessage(), e);
        }

        if (member) {
            try {
                federatorSupport.sendUpdateCollections();
            } catch (Exception e) {
                logger.error("[updateBridge] updateCollections failed: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Whether this sequencer may make the calls the bridge reserves for its federation.
     *
     * <p>receiveHeaders and updateCollections are reserved; registerBtcTransaction and
     * registerBtcCoinbaseTransaction are open to anyone, because a peg-in is somebody's coins and
     * the bridge checks the proof rather than who carried it. So a sequencer holding keys that
     * are in no live federation is still useful: it relays peg-ins, and skips the two calls that
     * would revert.
     *
     * <p>That is the state an incoming federation's member is in until their change completes,
     * and running before then is how they are ready when it does. The bridge decides membership
     * from the sending address, not from anything inside the call.
     */
    private boolean isFederationMember() {
        byte[] senderAddress = federatorSupport.senderAddress().getBytes().toArrayUnsafe();
        if (federation.hasMemberWithRskAddress(senderAddress)) {
            return true;
        }
        logger.warn(
            "[isFederationMember] This sequencer sends from {}, which is not a member of federation {}. "
                + "Relaying peg-ins, but leaving headers and updateCollections to the members, whose "
                + "calls the bridge will accept.",
            federatorSupport.senderAddress(), federation.getAddress());
        return false;
    }

    /** A transaction paying the federation: remember it, so its proof gets built when a block lands. */
    @Override
    public void onTransaction(Transaction tx) {
        logger.debug("[onTransaction] {} (wtxid {})", tx.getTxId(), tx.getWTxId());
        synchronized (this) {
            fileData.getTransactionProofs().computeIfAbsent(tx.getWTxId(), key -> new ArrayList<>());
            writeQuietly("onTransaction");
        }
    }

    /** A block: build a proof for any transaction in it that is being waited on. */
    @Override
    public void onBlock(Block block) {
        synchronized (this) {
            Transaction coinbase = null;
            boolean coinbaseStored = false;
            boolean changed = false;

            for (Transaction tx : block.getTransactions()) {
                if (tx.isCoinBase()) {
                    coinbase = tx;
                    continue;
                }

                List<Proof> proofs = fileData.getTransactionProofs().get(tx.getWTxId());
                if (proofs == null) {
                    continue;
                }
                if (proofs.stream().anyMatch(p -> p.getBlockHash().equals(block.getHash()))) {
                    logger.debug("[onBlock] Already have a proof for {} in {}", tx.getTxId(), block.getHash());
                    continue;
                }

                // A peg-in is always proved by wtxid, which for a transaction without witnesses is
                // the same as its txid.
                PartialMerkleTree tree = generatePMT(block, tx, tx.hasWitnesses());

                if (tx.hasWitnesses() && !coinbaseStored) {
                    if (coinbase == null) {
                        logger.error("[onBlock] Block {} has a segwit peg-in but no coinbase", block.getHash());
                        return;
                    }
                    try {
                        storeCoinbase(block, coinbase, tree);
                        coinbaseStored = true;
                        changed = true;
                    } catch (RuntimeException e) {
                        // Without its coinbase the bridge will reject the transaction, so there is
                        // nothing to be gained by storing the proof.
                        logger.error("[onBlock] {}", e.getMessage());
                        return;
                    }
                }

                proofs.add(new Proof(block.getHash(), tree));
                logger.info("[onBlock] Proof for {} (wtxid {}) in block {}", tx.getTxId(), tx.getWTxId(), block.getHash());
                changed = true;
            }

            if (changed) {
                writeQuietly("onBlock");
            }
        }
    }

    private void storeCoinbase(Block block, Transaction coinbase, PartialMerkleTree witnessTree) {
        // The coinbase's own proof uses txids: it has no wtxid in the witness tree, which carries a
        // zero in its place.
        PartialMerkleTree coinbaseTree = generatePMT(block, coinbase, false);
        Sha256Hash witnessMerkleRoot = witnessTree.getTxnHashAndMerkleRoot(new ArrayList<>());

        CoinbaseInformation coinbaseInformation =
            new CoinbaseInformation(coinbase, witnessMerkleRoot, block.getHash(), coinbaseTree);
        validateCoinbaseInformation(coinbaseInformation);

        fileData.getCoinbaseInformationMap().put(block.getHash(), coinbaseInformation);
        logger.debug("[storeCoinbase] Stored the coinbase of block {}", block.getHash());
    }

    /**
     * Checks the coinbase really commits to the witness root that was just computed.
     *
     * <p>The bridge will do this too and reject the block if it fails. Doing it here means finding
     * out before paying for the transaction, and while the block that produced it is still to hand.
     */
    private void validateCoinbaseInformation(CoinbaseInformation coinbaseInformation) {
        byte[] reservedValue = coinbaseInformation.getCoinbaseWitnessReservedValue();
        if (reservedValue == null) {
            throw new IllegalArgumentException(String.format(
                "Block %s has a segwit peg-in but its coinbase has no 32-byte witness reserved value",
                coinbaseInformation.getBlockHash()));
        }

        BtcTransaction thinCoinbase =
            ThinConverter.toThin(bridgeConstants.getBtcParams(), coinbaseInformation.getCoinbaseTransaction());
        Optional<co.rsk.bitcoinj.core.Sha256Hash> committed =
            co.rsk.peg.bitcoin.BitcoinUtils.findWitnessCommitment(thinCoinbase);
        co.rsk.bitcoinj.core.Sha256Hash calculated = co.rsk.bitcoinj.core.Sha256Hash.twiceOf(
            coinbaseInformation.getWitnessRoot().getReversedBytes(), reservedValue);

        if (committed.isEmpty() || !committed.get().equals(calculated)) {
            throw new IllegalArgumentException(String.format(
                "Block %s has a segwit peg-in but its coinbase commits to %s, not %s",
                coinbaseInformation.getBlockHash(), committed.orElse(null), calculated));
        }
    }

    /** Gives the bridge the headers between its bitcoin chain head and this peer's. */
    int updateBridgeBtcBlockchain() throws BlockStoreException, IOException, Exception {
        int bridgeHeight = federatorSupport.getBtcBestBlockChainHeight();
        int peerHeight = bitcoinWrapper.getBestChainHeight();
        if (peerHeight <= bridgeHeight) {
            return 0;
        }

        logger.debug("[updateBridgeBtcBlockchain] peer at {}, bridge at {}", peerHeight, bridgeHeight);

        StoredBlock commonAncestor = findBridgeBtcBlockchainMatchingAncestor(bridgeHeight);
        if (commonAncestor == null) {
            throw new IllegalStateException(
                "The bridge's bitcoin chain has no block this peer also has on its best chain");
        }
        logger.debug("[updateBridgeBtcBlockchain] Common ancestor {}", commonAncestor.getHeader().getHash());

        List<Block> missing = new LinkedList<>();
        StoredBlock current = bitcoinWrapper.getChainHead();
        while (!current.equals(commonAncestor)) {
            missing.add(0, current.getHeader());
            current = bitcoinWrapper.getBlock(current.getHeader().getPrevBlockHash());
            if (current == null) {
                throw new IllegalStateException(
                    "Walked off the end of the block store before reaching the common ancestor");
            }
        }
        if (missing.isEmpty()) {
            return 0;
        }

        List<Block> batch = missing.subList(0, Math.min(amountOfHeadersToSend, missing.size()));
        federatorSupport.sendReceiveHeaders(batch.toArray(new Block[0]));
        markCoinbasesAsReadyToBeInformed(batch);
        logger.debug("[updateBridgeBtcBlockchain] Sent {} of {} missing headers", batch.size(), missing.size());
        return batch.size();
    }

    /**
     * A coinbase can only be informed once the bridge has the header of its block.
     *
     * <p>Powpeg marked these and then lost the mark on restart, because the flag was never written
     * to disk; here it is stored, so a restart does not re-send coinbases the bridge already has.
     */
    void markCoinbasesAsReadyToBeInformed(List<Block> informedBlocks) {
        Map<Sha256Hash, CoinbaseInformation> coinbases = fileData.getCoinbaseInformationMap();
        if (coinbases.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Block informed : informedBlocks) {
            CoinbaseInformation coinbase = coinbases.get(informed.getHash());
            if (coinbase != null && !coinbase.isReadyToInform()) {
                coinbase.setReadyToInform(true);
                changed = true;
                logger.debug("[markCoinbasesAsReadyToBeInformed] Block {} is now known to the bridge", informed.getHash());
            }
        }
        if (changed) {
            synchronized (this) {
                writeQuietly("markCoinbasesAsReadyToBeInformed");
            }
        }
    }

    /**
     * Finds the deepest block the bridge has that this peer also has on its best chain.
     *
     * <p>The search doubles its depth each time. Normally the bridge is a block or two behind and
     * the first probe succeeds; after a bitcoin fork it could be many, and doubling finds the fork
     * point in a handful of calls rather than one call per block.
     */
    private StoredBlock findBridgeBtcBlockchainMatchingAncestor(int bridgeHeight) throws BlockStoreException {
        int initialHeight = federatorSupport.getBtcBlockchainInitialBlockHeight();
        int maxSearchDepth = bridgeHeight - initialHeight;

        int depth = 0;
        int iteration = 0;
        while (true) {
            Sha256Hash hash = federatorSupport.getBtcBlockchainBlockHashAtDepth(depth);
            StoredBlock stored = bitcoinWrapper.getBlock(hash);
            if (stored != null) {
                StoredBlock onBestChain = bitcoinWrapper.getBlockAtHeight(stored.getHeight());
                if (stored.equals(onBestChain)) {
                    return onBestChain;
                }
            }
            if (depth >= maxSearchDepth) {
                return null;
            }
            depth = Math.min(1 << iteration, maxSearchDepth);
            iteration++;
        }
    }

    /** Informs the bridge of one coinbase that is ready, if there is one. */
    void updateBridgeBtcCoinbaseTransactions() throws Exception {
        Optional<CoinbaseInformation> ready = fileData.getCoinbaseInformationMap().values().stream()
            .filter(CoinbaseInformation::isReadyToInform)
            .findFirst();
        if (ready.isEmpty()) {
            return;
        }

        CoinbaseInformation coinbase = ready.get();
        if (federatorSupport.hasBlockCoinbaseInformed(coinbase.getBlockHash())) {
            logger.debug("[updateBridgeBtcCoinbaseTransactions] The bridge already has the coinbase of {}",
                coinbase.getBlockHash());
            fileData.getCoinbaseInformationMap().remove(coinbase.getBlockHash());
            synchronized (this) {
                writeQuietly("updateBridgeBtcCoinbaseTransactions");
            }
            return;
        }

        logger.debug("[updateBridgeBtcCoinbaseTransactions] Informing the coinbase of {}", coinbase.getBlockHash());
        federatorSupport.sendRegisterCoinbaseTransaction(coinbase);
    }

    /** Registers the peg-ins that are confirmed, proved and not yet processed. */
    void updateBridgeBtcTransactions() throws Exception {
        Map<Sha256Hash, Transaction> confirmed =
            bitcoinWrapper.getTransactionMap(bridgeConstants.getBtc2RskMinimumAcceptableConfirmations());

        co.rsk.bitcoinj.core.Context thinContext =
            co.rsk.bitcoinj.core.Context.getOrCreate(bridgeConstants.getBtcParams());
        Wallet federationWallet =
            BridgeUtils.getFederationsNoSpendWallet(thinContext, List.copyOf(recognisedFederations));

        int sent = 0;
        Iterator<Sha256Hash> pending = List.copyOf(fileData.getTransactionProofs().keySet()).iterator();
        boolean changed = false;

        while (pending.hasNext() && sent < MAXIMUM_REGISTER_BTC_LOCK_TXS_PER_TURN) {
            Sha256Hash wtxid = pending.next();
            try {
                Transaction tx = confirmed.get(wtxid);
                if (tx == null) {
                    // Not confirmed deeply enough yet. Keep waiting for it.
                    continue;
                }

                BtcTransaction btcTx = ThinConverter.toThin(bridgeConstants.getBtcParams(), tx);
                if (btcTx.getValueSentToMe(federationWallet).isZero()) {
                    logger.warn("[updateBridgeBtcTransactions] {} pays nothing to any federation this client "
                            + "relays for; dropping it", tx.getTxId());
                    changed |= forget(wtxid);
                    continue;
                }

                if (!isWorthSending(btcTx)) {
                    changed |= forget(wtxid);
                    continue;
                }

                // The bridge tracks what it has processed by txid, not wtxid.
                if (federatorSupport.isBtcTxHashAlreadyProcessed(tx.getTxId())) {
                    changed |= forgetIfBuried(wtxid, tx);
                    continue;
                }

                if (registerIfProved(wtxid, tx)) {
                    sent++;
                }
            } catch (Exception e) {
                logger.error("[updateBridgeBtcTransactions] {} failed: {}", wtxid, e.getMessage(), e);
            }
        }

        if (changed) {
            synchronized (this) {
                writeQuietly("updateBridgeBtcTransactions");
            }
        }
    }

    /**
     * Whether the bridge could make anything of this transaction.
     *
     * <p>Powpeg asked more here, refusing to send a peg-in whose sender it could not identify. That
     * rule belonged to a protocol version that no longer exists: with peg-in instructions always
     * available the bridge takes the transaction either way, and refusing to send it would leave
     * the sender with no record of why their coins did not arrive. What remains is the one case the
     * bridge cannot act on at all, where there is neither a sender nor instructions to read.
     */
    private boolean isWorthSending(BtcTransaction btcTx) {
        PeginInformation peginInformation =
            new PeginInformation(btcLockSenderProvider, peginInstructionsProvider);
        try {
            peginInformation.parse(btcTx);
            return true;
        } catch (PeginInstructionsException e) {
            if (peginInformation.getSenderBtcAddress() != null) {
                logger.debug("[isWorthSending] {} has unreadable instructions; the bridge will refund {}",
                    btcTx.getHash(), peginInformation.getSenderBtcAddress());
                return true;
            }
            logger.warn("[isWorthSending] {} has neither a readable sender nor readable instructions; dropping it",
                btcTx.getHash());
            return false;
        } catch (ScriptException e) {
            // A script the parser cannot even walk, which it throws rather than reports. The bridge
            // runs the same parser and would throw in the same place, so sending this would buy a
            // reverted transaction and the gas for it, once per turn, forever.
            logger.warn("[isWorthSending] {} has a script that cannot be parsed ({}); dropping it",
                btcTx.getHash(), e.getMessage());
            return false;
        }
    }

    /**
     * Sends the transaction with a proof from a block that is on the peer's best chain.
     *
     * <p>A transaction can be proved by several blocks while a fork is unresolved, and the bridge
     * will only accept the one whose header it has on its own best chain. Powpeg asked bitcoinj
     * which blocks a transaction appeared in and then looked for a matching proof; the proofs are
     * already that record, and a proof is needed in any case, so the choice is made among them.
     */
    private boolean registerIfProved(Sha256Hash wtxid, Transaction tx) throws Exception {
        List<Proof> proofs = fileData.getTransactionProofs().get(wtxid);
        if (proofs == null || proofs.isEmpty()) {
            logger.debug("[registerIfProved] No proof yet for {}", wtxid);
            return false;
        }

        for (Proof proof : proofs) {
            StoredBlock stored = bitcoinWrapper.getBlock(proof.getBlockHash());
            if (stored == null) {
                continue;
            }
            StoredBlock onBestChain = bitcoinWrapper.getBlockAtHeight(stored.getHeight());
            if (onBestChain == null || !onBestChain.getHeader().getHash().equals(proof.getBlockHash())) {
                logger.debug("[registerIfProved] Block {} proving {} is not on the best chain",
                    proof.getBlockHash(), wtxid);
                continue;
            }
            federatorSupport.sendRegisterBtcTransaction(tx, stored.getHeight(), proof.getPartialMerkleTree());
            return true;
        }

        logger.debug("[registerIfProved] No proof for {} comes from a best chain block", wtxid);
        return false;
    }

    /**
     * Stops tracking a transaction the bridge has processed, once that is deeply enough buried.
     *
     * <p>Not immediately: the block that processed it could still be reorganised away, and a
     * transaction forgotten too early would never be sent again.
     */
    private boolean forgetIfBuried(Sha256Hash wtxid, Transaction tx) {
        long processedAt = federatorSupport.getBtcTxHashProcessedHeight(tx.getTxId());
        if (processedAt < 0) {
            return false;
        }
        long chainHeight = federatorSupport.getRskBestChainHeight();
        if (chainHeight - processedAt < minimumConfirmationsOnRsk) {
            return false;
        }
        logger.debug("[forgetIfBuried] {} was processed at height {}, now {}; dropping it",
            wtxid, processedAt, chainHeight);
        return forget(wtxid);
    }

    private boolean forget(Sha256Hash wtxid) {
        return fileData.getTransactionProofs().remove(wtxid) != null;
    }

    /**
     * The proof that a transaction is in a block.
     *
     * <p>With {@code useWtxId} the tree is built over witness ids, which is what a segwit block's
     * witness root commits to. In that tree the coinbase's place is taken by a zero hash, because
     * the coinbase is where the commitment itself lives and cannot commit to itself.
     */
    static PartialMerkleTree generatePMT(Block block, Transaction transaction, boolean useWtxId) {
        List<Transaction> transactions = block.getTransactions();
        Sha256Hash wanted = useWtxId ? transaction.getWTxId() : transaction.getTxId();
        List<Sha256Hash> hashes = new ArrayList<>(transactions.size());
        byte[] bits = new byte[(int) Math.ceil(transactions.size() / 8.0)];

        for (int i = 0; i < transactions.size(); i++) {
            Transaction tx = transactions.get(i);
            Sha256Hash hash = useWtxId ? tx.getWTxId() : tx.getTxId();
            if (useWtxId && tx.isCoinBase()) {
                hash = Sha256Hash.ZERO_HASH;
            }
            hashes.add(hash);
            if (hash.equals(wanted)) {
                Utils.setBitLE(bits, i);
            }
        }
        return PartialMerkleTree.buildFromLeaves(block.getParams(), bits, hashes);
    }

    /** What this client is waiting to hear about, for tests and for logging. */
    public synchronized Map<Sha256Hash, List<Proof>> getTransactionsToSendToRsk() {
        return fileData.getTransactionProofs();
    }

    private void restoreFileData() throws IOException {
        BtcToRskClientFileReadResult result = storage.read(ThinConverter.toOriginal(bridgeConstants.getBtcParamsString()));
        if (!result.success()) {
            throw new IOException(
                "Refusing to start from an unreadable " + storage.getInfo().getFilePath()
                    + ": proofs gathered from blocks that may be long past would be silently lost");
        }
        fileData = result.data();
        logger.info("[restoreFileData] {} transactions and {} coinbases restored",
            fileData.getTransactionProofs().size(), fileData.getCoinbaseInformationMap().size());
    }

    private void writeQuietly(String where) {
        try {
            storage.write(fileData);
        } catch (IOException e) {
            logger.error("[{}] Could not write {}: {}", where, storage.getInfo().getFilePath(), e.getMessage(), e);
        }
    }
}
