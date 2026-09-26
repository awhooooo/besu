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
package co.rsk.federate.rpc;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;

/**
 * The node, as this process is allowed to see it.
 *
 * <p>Only what the peg needs: read state, send a transaction, and find out whether the node is
 * caught up enough to be worth asking. Kept as an interface so that a test can answer without a node
 * and so that a later transport — a socket rather than a port — changes nothing above it.
 */
public interface EthClient {

    /** Runs a call against the chain head without sending anything. */
    Bytes call(Address to, Bytes callData);

    /** Submits an already-signed transaction. Returns its hash. */
    Bytes32Hash sendRawTransaction(Bytes signedTransaction);

    long blockNumber();

    /** The nonce the next transaction from this address should carry, pending ones counted. */
    long pendingNonce(Address address);

    /** False only when the node is caught up; acting on a node that is behind is acting on stale state. */
    boolean syncing();

    /** Present once the transaction is in a block, whether it succeeded there or not. */
    Optional<TransactionReceipt> receipt(Bytes32Hash transactionHash);

    /** A 32-byte hash, named so that a transaction hash cannot be passed where an address belongs. */
    record Bytes32Hash(Bytes value) {
        public Bytes32Hash {
            if (value == null || value.size() != 32) {
                throw new IllegalArgumentException("A hash is 32 bytes, got " + (value == null ? "null" : value.size()));
            }
        }

        public static Bytes32Hash fromHexString(String hex) {
            return new Bytes32Hash(Bytes.fromHexString(hex));
        }

        @Override
        public String toString() {
            return value.toHexString();
        }
    }

    /** Only what the sequencer looks at: whether the transaction it sent did anything. */
    record TransactionReceipt(Bytes32Hash transactionHash, long blockNumber, boolean successful) {}
}
