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
import co.rsk.bitcoinj.core.UTXO;
import co.rsk.peg.constants.BridgeConstants;
import org.hyperledger.besu.datatypes.Hash;
import co.rsk.peg.utils.RskRlp;
import org.hyperledger.besu.ethereum.rlp.RLPInput;

import java.io.IOException;
import java.util.*;

/**
 * DTO to send the contract state.
 * Not production code, just used for debugging.
 *
 * Created by mario on 27/09/2016.
 */
public class BridgeState {
    private final int btcBlockchainBestChainHeight;
    private final long nextPegoutCreationBlockNumber;
    private final List<UTXO> activeFederationBtcUTXOs;
    private final SortedMap<Hash, BtcTransaction> rskTxsWaitingForSignatures;
    private final ReleaseRequestQueue releaseRequestQueue;
    private final PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations;

    protected BridgeState(
        int btcBlockchainBestChainHeight,
        long nextPegoutCreationBlockNumber,
        List<UTXO> activeFederationBtcUTXOs,
        SortedMap<Hash, BtcTransaction> rskTxsWaitingForSignatures,
        ReleaseRequestQueue releaseRequestQueue,
        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations) {

        this.btcBlockchainBestChainHeight = btcBlockchainBestChainHeight;
        this.nextPegoutCreationBlockNumber = nextPegoutCreationBlockNumber;
        this.activeFederationBtcUTXOs = activeFederationBtcUTXOs;
        this.rskTxsWaitingForSignatures = rskTxsWaitingForSignatures;
        this.releaseRequestQueue = releaseRequestQueue;
        this.pegoutsWaitingForConfirmations = pegoutsWaitingForConfirmations;
    }

    public int getBtcBlockchainBestChainHeight() {
        return this.btcBlockchainBestChainHeight;
    }

    public List<UTXO> getActiveFederationBtcUTXOs() {
        return activeFederationBtcUTXOs;
    }

    public SortedMap<Hash, BtcTransaction> getRskTxsWaitingForSignatures() {
        return rskTxsWaitingForSignatures;
    }

    public ReleaseRequestQueue getReleaseRequestQueue() {
        return releaseRequestQueue;
    }

    public PegoutsWaitingForConfirmations getPegoutsWaitingForConfirmations() {
        return pegoutsWaitingForConfirmations;
    }

    public long getNextPegoutCreationBlockNumber() {
        return nextPegoutCreationBlockNumber;
    }

    @Override
    public String toString() {
        return "StateForDebugging{" + "\n" +
                "btcBlockchainBestChainHeight=" + btcBlockchainBestChainHeight + "\n" +
                ", nextPegoutCreationBlockNumber=" + nextPegoutCreationBlockNumber + "\n" +
                ", activeFederationBtcUTXOs=" + activeFederationBtcUTXOs + "\n" +
                ", rskTxsWaitingForSignatures=" + rskTxsWaitingForSignatures + "\n" +
                ", releaseRequestQueue=" + releaseRequestQueue + "\n" +
                ", pegoutsWaitingForConfirmations=" + pegoutsWaitingForConfirmations + "\n" +
                '}';
    }

    public Map<String, Object> stateToMap() {
        Map<String, Object> result = new HashMap<>();
        result.put("rskTxsWaitingForSignatures", this.toStringList(rskTxsWaitingForSignatures.keySet()));
        result.put("btcBlockchainBestChainHeight", this.btcBlockchainBestChainHeight);
        return result;
    }

    // The state is serialized as the list [btcBlockchainBestChainHeight, activeFederationBtcUTXOs,
    // rskTxsWaitingForSignatures, releaseRequestQueue, pegoutsWaitingForConfirmations, nextPegoutCreationBlockNumber],
    // where every item after the first is the serialized collection or value wrapped as an element.
    public byte[] getEncoded() throws IOException {
        byte[] serializedReleaseRequestQueue = BridgeSerializationUtils.serializeReleaseRequestQueueWithTxHash(releaseRequestQueue);
        byte[] serializedPegoutWaitingForConfirmations = BridgeSerializationUtils.serializePegoutsWaitingForConfirmationsWithTxHash(pegoutsWaitingForConfirmations);

        return RskRlp.encode(out -> {
            out.startList();
            RskRlp.writeUnsigned(out, this.btcBlockchainBestChainHeight);
            RskRlp.writeElement(out, BridgeSerializationUtils.serializeUTXOList(activeFederationBtcUTXOs));
            RskRlp.writeElement(out, BridgeSerializationUtils.serializeRskTxsWaitingForSignatures(rskTxsWaitingForSignatures));
            RskRlp.writeElement(out, serializedReleaseRequestQueue);
            RskRlp.writeElement(out, serializedPegoutWaitingForConfirmations);
            RskRlp.writeElement(out, BridgeSerializationUtils.serializeLong(nextPegoutCreationBlockNumber));
            out.endList();
        });
    }

    public static BridgeState create(BridgeConstants bridgeConstants, byte[] data) throws IOException {
        RLPInput in = RskRlp.input(data);
        in.enterList();

        int btcBlockchainBestChainHeight = RskRlp.readUnsigned(in).intValue();
        byte[] btcUTXOsBytes = RskRlp.readData(in);
        List<UTXO> btcUTXOs = BridgeSerializationUtils.deserializeUTXOList(btcUTXOsBytes);
        byte[] rskTxsWaitingForSignaturesBytes = RskRlp.readData(in);
        SortedMap<Hash, BtcTransaction> rskTxsWaitingForSignatures = BridgeSerializationUtils.deserializeRskTxsWaitingForSignatures(rskTxsWaitingForSignaturesBytes, bridgeConstants.getBtcParams());
        byte[] releaseRequestQueueBytes = RskRlp.readData(in);
        ReleaseRequestQueue releaseRequestQueue = new ReleaseRequestQueue(BridgeSerializationUtils.deserializeReleaseRequestQueue(releaseRequestQueueBytes, bridgeConstants.getBtcParams(), true));
        byte[] pegoutsWaitingForConfirmationsBytes = RskRlp.readData(in);
        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = BridgeSerializationUtils.deserializePegoutsWaitingForConfirmations(pegoutsWaitingForConfirmationsBytes, bridgeConstants.getBtcParams(), true);
        byte[] nextPegoutCreationBlockNumberBytes = RskRlp.readData(in);
        long nextPegoutCreationBlockNumber = BridgeSerializationUtils.deserializeOptionalLong(nextPegoutCreationBlockNumberBytes).orElse(0L);
        in.leaveListLenient();

        return new BridgeState(
                btcBlockchainBestChainHeight,
                nextPegoutCreationBlockNumber,
                btcUTXOs,
                rskTxsWaitingForSignatures,
                releaseRequestQueue,
                pegoutsWaitingForConfirmations
        );
    }

    private List<String> toStringList(Set<Hash> keys) {
        List<String> hashes = new ArrayList<>();
        if(keys != null) {
            keys.forEach(s -> hashes.add(s.toHexString()));
        }

        return hashes;
    }
}
