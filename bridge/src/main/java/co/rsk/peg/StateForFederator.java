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
import co.rsk.bitcoinj.core.NetworkParameters;
import org.hyperledger.besu.datatypes.Hash;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import co.rsk.peg.utils.RskRlp;
import org.hyperledger.besu.ethereum.rlp.RLPInput;

import org.apache.tuweni.bytes.Bytes;

public class StateForFederator {

    private final SortedMap<Hash, BtcTransaction> rskTxsWaitingForSignatures;

    public StateForFederator(SortedMap<Hash, BtcTransaction> rskTxsWaitingForSignatures) {
        Objects.requireNonNull(rskTxsWaitingForSignatures);

        this.rskTxsWaitingForSignatures = Collections.unmodifiableSortedMap(rskTxsWaitingForSignatures);
    }

    public StateForFederator(byte[] rlpData, NetworkParameters networkParameters) {
        this(
            BridgeSerializationUtils.deserializeRskTxsWaitingForSignatures(
                decodeRlpToMap(rlpData), networkParameters));
    }

    /**
     * Retrieves a sorted map of RSK transactions that are waiting for signatures.
     *
     * <p>
     * The returned {@code SortedMap<Hash, BtcTransaction>} contains entries where the key
     * is the hash of the RSK transaction, and the value is the {@code BtcTransaction} object.
     * This method guarantees a non-null result, returning an empty map if no transactions are pending.
     * </p>
     *
     * @return a non-null {@code SortedMap<Hash, BtcTransaction>} of transactions waiting for signatures;
     *         if no transactions are pending, an empty map is returned.
     */
    public SortedMap<Hash, BtcTransaction> getRskTxsWaitingForSignatures() {
        return rskTxsWaitingForSignatures;
    }

    /**
     * Encodes the current state into RLP format.
     * 
     * @return The RLP-encoded byte array representing the current state.
     */
    public byte[] encodeToRlp() {
        byte[] serializedRskTxsWaitingForSignatures =
            BridgeSerializationUtils.serializeRskTxsWaitingForSignatures(rskTxsWaitingForSignatures);
        // The map's own list is the single item of the outer list.
        return RskRlp.encode(out -> {
            out.startList();
            out.writeRLPBytes(Bytes.wrap(serializedRskTxsWaitingForSignatures));
            out.endList();
        });
    }

    private static byte[] decodeRlpToMap(byte[] rlpData) {
        Objects.requireNonNull(rlpData);

        RLPInput in = RskRlp.input(rlpData);
        in.enterList();
        return RskRlp.readData(in);
    }
}
