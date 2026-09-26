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

import java.math.BigInteger;
import java.util.Objects;

import co.rsk.federate.rpc.EthClient;
import co.rsk.federate.signing.SignerException;
import co.rsk.peg.BridgeMethods;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.PartialMerkleTree;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bridge in Bitcoin's terms.
 *
 * <p>{@link BridgeClient} knows about calls, nonces and gas; this knows what the arguments mean. The
 * split matters because the encodings are exact and easy to get subtly wrong: a hash goes to the
 * bridge as a string in one method and as raw bytes in another, and bitcoinj prints a hash in the
 * reverse of the byte order it stores, so the two are not interchangeable.
 *
 * <p>Powpeg's class of this name reached into the node it was part of. This one is the same surface
 * over a JSON-RPC connection.
 */
public class FederatorSupport {

    private static final Logger logger = LoggerFactory.getLogger(FederatorSupport.class);

    private final BridgeClient bridge;

    public FederatorSupport(BridgeClient bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    public BridgeClient bridgeClient() {
        return bridge;
    }

    /** The height of the bridge's own view of the bitcoin chain. */
    public int getBtcBestBlockChainHeight() {
        BigInteger height = bridge.callOne(BridgeMethods.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT);
        return height.intValueExact();
    }

    /** The height the bridge's bitcoin chain starts at, below which it remembers nothing. */
    public int getBtcBlockchainInitialBlockHeight() {
        BigInteger height = bridge.callOne(BridgeMethods.GET_BTC_BLOCKCHAIN_INITIAL_BLOCK_HEIGHT);
        return height.intValueExact();
    }

    /** The hash of the block this many blocks below the bridge's bitcoin chain head. */
    public Sha256Hash getBtcBlockchainBlockHashAtDepth(int depth) {
        byte[] hash = bridge.callOne(BridgeMethods.GET_BTC_BLOCKCHAIN_BLOCK_HASH_AT_DEPTH, BigInteger.valueOf(depth));
        return Sha256Hash.wrap(hash);
    }

    /** Whether the bridge has already acted on this transaction. Keyed by txid, never wtxid. */
    public boolean isBtcTxHashAlreadyProcessed(Sha256Hash btcTxHash) {
        return bridge.callOne(BridgeMethods.IS_BTC_TX_HASH_ALREADY_PROCESSED, btcTxHash.toString());
    }

    /** The chain height at which the bridge acted on this transaction, or -1 if it has not. */
    public long getBtcTxHashProcessedHeight(Sha256Hash btcTxHash) {
        BigInteger height = bridge.callOne(BridgeMethods.GET_BTC_TX_HASH_PROCESSED_HEIGHT, btcTxHash.toString());
        return height.longValueExact();
    }

    /** Whether the bridge already holds the coinbase of this block. */
    public boolean hasBlockCoinbaseInformed(Sha256Hash blockHash) {
        return bridge.callOne(
            BridgeMethods.HAS_BTC_BLOCK_COINBASE_TRANSACTION_INFORMATION, blockHash.getBytes());
    }

    /** Gives the bridge bitcoin headers to extend its chain with. */
    public void sendReceiveHeaders(Block[] headers) throws SignerException {
        Object[] serialized = new Object[headers.length];
        for (int i = 0; i < headers.length; i++) {
            serialized[i] = headers[i].cloneAsHeader().bitcoinSerialize();
        }
        logger.debug("[sendReceiveHeaders] {} headers, {} to {}",
            headers.length, headers[0].getHash(), headers[headers.length - 1].getHash());
        bridge.send(BridgeMethods.RECEIVE_HEADERS, (Object) serialized);
    }

    /** Asks the bridge to act on a bitcoin transaction, proving it belongs to a block it knows. */
    public void sendRegisterBtcTransaction(Transaction tx, int blockHeight, PartialMerkleTree pmt)
        throws SignerException {
        logger.debug("[sendRegisterBtcTransaction] {} (wtxid {}) at height {}",
            tx.getTxId(), tx.getWTxId(), blockHeight);
        bridge.send(
            BridgeMethods.REGISTER_BTC_TRANSACTION,
            tx.bitcoinSerialize(),
            BigInteger.valueOf(blockHeight),
            pmt.bitcoinSerialize());
    }

    /** Gives the bridge a block's coinbase, so it can check segwit transactions from that block. */
    public void sendRegisterCoinbaseTransaction(CoinbaseInformation coinbase) throws SignerException {
        byte[] reservedValue = coinbase.getCoinbaseWitnessReservedValue();
        if (reservedValue == null) {
            throw new IllegalArgumentException(
                "Coinbase of block " + coinbase.getBlockHash() + " has no 32-byte witness reserved value");
        }
        logger.debug("[sendRegisterCoinbaseTransaction] coinbase of block {}", coinbase.getBlockHash());
        bridge.send(
            BridgeMethods.REGISTER_BTC_COINBASE_TRANSACTION,
            coinbase.getSerializedCoinbaseTransactionWithoutWitness(),
            coinbase.getBlockHash().getBytes(),
            coinbase.getPmt().bitcoinSerialize(),
            coinbase.getWitnessRoot().getBytes(),
            reservedValue);
    }

    /** Tells the bridge to do its periodic work: confirmations, pegouts, expiry. */
    public void sendUpdateCollections() throws SignerException {
        logger.debug("[sendUpdateCollections]");
        bridge.send(BridgeMethods.UPDATE_COLLECTIONS);
    }

    /** The height of the Besu chain the node is following. */
    public long getRskBestChainHeight() {
        return bridge.chainHeight();
    }

    /** True only when the node is caught up enough that its answers are worth acting on. */
    public boolean nodeIsUsable() {
        return bridge.nodeIsUsable();
    }
}
