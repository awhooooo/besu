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
package co.rsk.peg.utils;

import co.rsk.peg.abi.AbiFunction;


import org.hyperledger.besu.datatypes.LogTopic;

import org.hyperledger.besu.datatypes.Log;

import co.rsk.peg.host.BridgeHost;

import co.rsk.peg.BridgeAddresses;

import org.apache.tuweni.bytes.Bytes;


import org.hyperledger.besu.datatypes.Wei;

import co.rsk.bitcoinj.core.*;
import org.hyperledger.besu.datatypes.Hash;
import co.rsk.peg.BridgeEvents;
import co.rsk.peg.bitcoin.UtxoUtils;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.federation.FederationMember;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.pegin.RejectedPeginReason;
import java.util.List;
import java.util.function.Function;
import co.rsk.bitcoinj.core.BtcECKey;

/**
 * Responsible for logging events triggered by BridgeContract.
 *
 * @author martin.medina
 */
public class BridgeEventLoggerImpl implements BridgeEventLogger {
    private final BridgeConstants bridgeConstants;
    private final BridgeHost host;

    public BridgeEventLoggerImpl(BridgeConstants bridgeConstants, BridgeHost host) {
        this.bridgeConstants = bridgeConstants;
        this.host = host;
    }

    @Override
    public void logUpdateCollections(org.hyperledger.besu.datatypes.Address sender) {
        AbiFunction event = BridgeEvents.UPDATE_COLLECTIONS.getEvent();

        List<LogTopic> encodedTopics = event.encodeEventTopics();

        Bytes encodedData = event.encodeEventData(sender.getBytes().toUnprefixedHexString());

        addLog(encodedTopics, encodedData);
    }

    @Override
    public void logAddSignature(FederationMember federationMember, BtcTransaction btcTx, byte[] rskTxHash) {
        BtcECKey federatorRskPublicKey = getFederatorRskPublicKey(federationMember);
        String federatorRskAddress = PublicKeys.addressOf(federatorRskPublicKey).getBytes().toUnprefixedHexString();
        BtcECKey federatorBtcPublicKey = federationMember.getBtcPublicKey();

        logAddSignatureInSolidityFormat(rskTxHash, federatorRskAddress, federatorBtcPublicKey);
    }

    private BtcECKey getFederatorRskPublicKey(FederationMember federationMember) {
        return federationMember.getRskPublicKey();
    }

    private void logAddSignatureInSolidityFormat(byte[] rskTxHash, String federatorRskAddress, BtcECKey federatorPublicKey) {
        AbiFunction event = BridgeEvents.ADD_SIGNATURE.getEvent();

        List<LogTopic> encodedTopics = event.encodeEventTopics(rskTxHash, federatorRskAddress);

        byte[] federatorPublicKeySerialized = federatorPublicKey.getPubKey();
        Bytes encodedData = event.encodeEventData(federatorPublicKeySerialized);

        addLog(encodedTopics, encodedData);
    }

    @Override
    public void logReleaseBtc(BtcTransaction btcTx, byte[] rskTxHash) {
        AbiFunction event = BridgeEvents.RELEASE_BTC.getEvent();

        List<LogTopic> encodedTopics = event.encodeEventTopics(rskTxHash);

        byte[] rawBtcTxSerialized = btcTx.bitcoinSerialize();
        Bytes encodedData = event.encodeEventData(rawBtcTxSerialized);

        addLog(encodedTopics, encodedData);
    }

    @Override
    public void logCommitFederation(long executionBlockNumber, Federation oldFederation, Federation newFederation) {
        AbiFunction event = BridgeEvents.COMMIT_FEDERATION.getEvent();

        // Convert old federation public keys in bytes array
        byte[] oldFederationFlatPubKeys = flatKeysAsByteArray(oldFederation.getBtcPublicKeys());
        String oldFederationBtcAddress = oldFederation.getAddress().toBase58();

        byte[] newFederationFlatPubKeys = flatKeysAsByteArray(newFederation.getBtcPublicKeys());
        String newFederationBtcAddress = newFederation.getAddress().toBase58();

        FederationConstants federationConstants = bridgeConstants.getFederationConstants();
        long newFedActivationBlockNumber = executionBlockNumber + federationConstants.getFederationActivationAge();

        List<LogTopic> encodedTopics = event.encodeEventTopics();

        Bytes encodedData = event.encodeEventData(
            oldFederationFlatPubKeys,
            oldFederationBtcAddress,
            newFederationFlatPubKeys,
            newFederationBtcAddress,
            newFedActivationBlockNumber
        );

        addLog(encodedTopics, encodedData);
    }

    @Override
    public void logCommitFederationFailure(long executionBlockNumber, Federation proposedFederation) {
        AbiFunction event = BridgeEvents.COMMIT_FEDERATION_FAILED.getEvent();

        List<LogTopic> encodedTopics = event.encodeEventTopics();

        byte[] proposedFederationRedeemScriptSerialized = proposedFederation.getRedeemScript().getProgram();

        Bytes encodedData = event.encodeEventData(
            proposedFederationRedeemScriptSerialized,
            executionBlockNumber
        );

        addLog(encodedTopics, encodedData);
    }

    @Override
    public void logPeginBtc(org.hyperledger.besu.datatypes.Address receiver, BtcTransaction btcTx, Coin amount, int protocolVersion) {
        AbiFunction event = BridgeEvents.PEGIN_BTC.getEvent();

        List<LogTopic> encodedTopics = event.encodeEventTopics(receiver.toString(), btcTx.getHash().getBytes());

        Bytes encodedData = event.encodeEventData(amount.getValue(), protocolVersion);

        addLog(encodedTopics, encodedData);
    }

    @Override
    public void logReleaseBtcRequested(byte[] rskTransactionHash, BtcTransaction btcTx, Coin amount) {
        AbiFunction event = BridgeEvents.RELEASE_REQUESTED.getEvent();

        byte[] btcTxHashSerialized = btcTx.getHash().getBytes();
        List<LogTopic> encodedTopics = event.encodeEventTopics(rskTransactionHash, btcTxHashSerialized);

        Bytes encodedData = event.encodeEventData(amount.getValue());

        addLog(encodedTopics, encodedData);
    }

    @Override
    public void logRejectedPegin(BtcTransaction btcTx, RejectedPeginReason reason) {
        AbiFunction event = BridgeEvents.REJECTED_PEGIN.getEvent();

        byte[] btcTxHashSerialized = btcTx.getHash().getBytes();
        List<LogTopic> encodedTopics = event.encodeEventTopics(btcTxHashSerialized);

        Bytes encodedData = event.encodeEventData(reason.getValue());

        addLog(encodedTopics, encodedData);
    }

    @Override
    public void logNonRefundablePegin(BtcTransaction btcTx, NonRefundablePeginReason reason) {
        AbiFunction event = BridgeEvents.UNREFUNDABLE_PEGIN.getEvent();

        byte[] btcTxHashSerialized = btcTx.getHash().getBytes();
        List<LogTopic> encodedTopics = event.encodeEventTopics(btcTxHashSerialized);

        Bytes encodedData = event.encodeEventData(reason.getValue());

        addLog(encodedTopics, encodedData);
    }

    @Override
    public void logReleaseBtcRequestReceived(org.hyperledger.besu.datatypes.Address sender, Address btcDestinationAddress, Wei amountInWeis) {
        logReleaseBtcRequestReceived(sender.getBytes().toUnprefixedHexString(), btcDestinationAddress.toString(), amountInWeis);
    }

    private void logReleaseBtcRequestReceived(String sender, String btcDestinationAddress, Wei amountInWeis) {
        AbiFunction event = BridgeEvents.RELEASE_REQUEST_RECEIVED.getEvent();

        List<LogTopic> encodedTopics = event.encodeEventTopics(sender);

        Bytes encodedData = event.encodeEventData(btcDestinationAddress, amountInWeis.toBigInteger());

        addLog(encodedTopics, encodedData);
    }

    @Override
    public void logReleaseBtcRequestRejected(org.hyperledger.besu.datatypes.Address sender, Wei amountInWeis, RejectedPegoutReason reason) {
        AbiFunction event = BridgeEvents.RELEASE_REQUEST_REJECTED.getEvent();

        List<LogTopic> encodedTopics = event.encodeEventTopics(sender.getBytes().toUnprefixedHexString());

        Bytes encodedData = event.encodeEventData(amountInWeis.toBigInteger(), reason.getValue());

        addLog(encodedTopics, encodedData);
    }

    @Override
    public void logBatchPegoutCreated(Sha256Hash btcTxHash, List<Hash> rskTxHashes) {
        AbiFunction event = BridgeEvents.BATCH_PEGOUT_CREATED.getEvent();

        byte[] btcTxHashSerialized = btcTxHash.getBytes();
        List<LogTopic> encodedTopics = event.encodeEventTopics(btcTxHashSerialized);

        byte[] serializedRskTxHashes = serializeRskTxHashes(rskTxHashes);
        Bytes encodedData = event.encodeEventData(serializedRskTxHashes);

        addLog(encodedTopics, encodedData);
    }

    @Override
    public void logPegoutConfirmed(Sha256Hash btcTxHash, long pegoutCreationRskBlockNumber) {
        AbiFunction event = BridgeEvents.PEGOUT_CONFIRMED.getEvent();

        byte[] btcTxHashSerialized = btcTxHash.getBytes();
        List<LogTopic> encodedTopics = event.encodeEventTopics(btcTxHashSerialized);

        Bytes encodedData = event.encodeEventData(pegoutCreationRskBlockNumber);

        addLog(encodedTopics, encodedData);
    }

    @Override
    public void logPegoutTransactionCreated(Sha256Hash btcTxHash, List<Coin> outpointValues) {
        if (btcTxHash == null){
            throw new IllegalArgumentException("btcTxHash param cannot be null");
        }

        AbiFunction event = BridgeEvents.PEGOUT_TRANSACTION_CREATED.getEvent();

        byte[] btcTxHashSerialized = btcTxHash.getBytes();
        List<LogTopic> encodedTopics = event.encodeEventTopics(btcTxHashSerialized);

        byte[] serializedOutpointValues = UtxoUtils.encodeOutpointValues(outpointValues);
        Bytes encodedData = event.encodeEventData(serializedOutpointValues);

        addLog(encodedTopics, encodedData);
    }

    private byte[] flatKeys(List<BtcECKey> keys, Function<BtcECKey, byte[]> parser) {
        List<byte[]> pubKeys = keys.stream()
                .map(parser)
                .toList();
        int pubKeysLength = pubKeys.stream().mapToInt(key -> key.length).sum();

        byte[] flatPubKeys = new byte[pubKeysLength];
        int copyPos = 0;
        for (byte[] key : pubKeys) {
            System.arraycopy(key, 0, flatPubKeys, copyPos, key.length);
            copyPos += key.length;
        }

        return flatPubKeys;
    }

    private byte[] flatKeysAsByteArray(List<BtcECKey> keys) {
        return flatKeys(keys, BtcECKey::getPubKey);
    }

    private byte[] serializeRskTxHashes(List<Hash> rskTxHashes) {
        List<byte[]> rskTxHashesList = rskTxHashes.stream()
            .map(hash -> hash.getBytes().toArrayUnsafe())
            .toList();
        int rskTxHashesLength = rskTxHashesList.stream().mapToInt(key -> key.length).sum();

        byte[] serializedRskTxHashes = new byte[rskTxHashesLength];
        int copyPos = 0;
        for (byte[] rskTxHash : rskTxHashesList) {
            System.arraycopy(rskTxHash, 0, serializedRskTxHashes, copyPos, rskTxHash.length);
            copyPos += rskTxHash.length;
        }

        return serializedRskTxHashes;
    }

    private void addLog(List<LogTopic> eventEncodedTopics, Bytes eventEncodedData) {
        host.emitLog(new Log(BridgeAddresses.BRIDGE, eventEncodedData, eventEncodedTopics));
    }
}
