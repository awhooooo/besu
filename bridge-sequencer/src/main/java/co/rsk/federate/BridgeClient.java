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
import java.util.OptionalLong;

import co.rsk.federate.rpc.EthClient;
import co.rsk.federate.rpc.RpcException;
import co.rsk.federate.signing.SignerException;
import co.rsk.federate.tx.LegacyTransactionSigner;
import co.rsk.peg.BridgeAddresses;
import co.rsk.peg.BridgeMethods;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the bridge and writes to it, and is the only thing here that knows what a transaction is.
 *
 * <p>Powpeg's equivalent lived inside the node and could reach its execution and its transaction pool
 * directly. From outside there is a call and a send, and one thing the pool used to do that now has
 * to be done here: keeping the nonce in order.
 *
 * <p>The nonce is asked of the node once and then counted forward locally, because a transaction the
 * node has accepted but not yet mined has already consumed its number, and asking again too soon
 * would hand out the same one twice and replace the earlier transaction. Anything the node refuses
 * forgets the local count, so that the next attempt starts from what the node actually believes
 * rather than from what this process assumed.
 */
public class BridgeClient {

    private static final Logger logger = LoggerFactory.getLogger(BridgeClient.class);

    private final EthClient node;
    private final LegacyTransactionSigner transactionSigner;
    private final GasPolicy gasPolicy;
    private final long gasLimit;

    /** The next nonce to use, or empty when the node has not been asked yet. */
    private OptionalLong nextNonce = OptionalLong.empty();

    public BridgeClient(
        EthClient node,
        LegacyTransactionSigner transactionSigner,
        GasPolicy gasPolicy,
        long gasLimit) {
        this.node = Objects.requireNonNull(node, "node");
        this.transactionSigner = Objects.requireNonNull(transactionSigner, "transactionSigner");
        this.gasPolicy = Objects.requireNonNull(gasPolicy, "gasPolicy");
        if (gasLimit <= 0) {
            throw new IllegalArgumentException("A gas limit must be positive");
        }
        this.gasLimit = gasLimit;
    }

    /** The address this client sends from. */
    public Address senderAddress() {
        return transactionSigner.senderAddress();
    }

    /**
     * Reads a bridge method without sending anything.
     *
     * @return whatever the method returns, already decoded
     */
    public Object[] call(BridgeMethods method, Object... arguments) {
        Bytes callData = method.getFunction().encode(arguments);
        logger.debug("[call] {}", method);
        Bytes result = node.call(BridgeAddresses.BRIDGE, callData);
        return method.getFunction().decodeResult(result);
    }

    /** Reads a bridge method that returns one value. */
    @SuppressWarnings("unchecked")
    public <T> T callOne(BridgeMethods method, Object... arguments) {
        Object[] results = call(method, arguments);
        if (results.length == 0) {
            throw new RpcException(String.format("%s returned nothing", method));
        }
        return (T) results[0];
    }

    /**
     * Sends a bridge method, signed by the federator's RSK key.
     *
     * @return the hash the node gave the transaction
     */
    public EthClient.Bytes32Hash send(BridgeMethods method, Object... arguments) throws SignerException {
        Bytes payload = method.getFunction().encode(arguments);
        long chainHeight = node.blockNumber();
        BigInteger gasPrice = gasPolicy.gasPriceFor(chainHeight);
        long nonce = takeNonce();

        LegacyTransactionSigner.UnsignedTransaction unsigned =
            new LegacyTransactionSigner.UnsignedTransaction(
                nonce, gasPrice, gasLimit, BridgeAddresses.BRIDGE, BigInteger.ZERO, payload);

        logger.info(
            "[send] {} nonce {} gasPrice {}{}",
            method,
            nonce,
            gasPrice,
            gasPolicy.isWithinBootstrapWindow(chainHeight) ? " (bootstrap window: free)" : "");

        try {
            EthClient.Bytes32Hash hash = node.sendRawTransaction(transactionSigner.sign(unsigned));
            nextNonce = OptionalLong.of(nonce + 1);
            return hash;
        } catch (RuntimeException e) {
            // The node may or may not have taken it. Either way this process no longer knows what the
            // next nonce is, so it asks again rather than guessing.
            forgetNonce();
            throw e;
        }
    }

    /** True only when the node is caught up enough that its state is worth acting on. */
    public boolean nodeIsUsable() {
        return !node.syncing();
    }

    public long chainHeight() {
        return node.blockNumber();
    }

    /** Forgets the local nonce, so that the next send asks the node again. */
    public void forgetNonce() {
        if (nextNonce.isPresent()) {
            logger.debug("[forgetNonce] Dropping the local nonce; the node will be asked again");
        }
        nextNonce = OptionalLong.empty();
    }

    private long takeNonce() {
        if (nextNonce.isEmpty()) {
            long fromNode = node.pendingNonce(senderAddress());
            logger.debug("[takeNonce] The node says the next nonce is {}", fromNode);
            nextNonce = OptionalLong.of(fromNode);
        }
        return nextNonce.getAsLong();
    }
}
