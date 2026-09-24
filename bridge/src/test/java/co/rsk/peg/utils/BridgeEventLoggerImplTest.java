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

import static co.rsk.peg.bitcoin.BitcoinTestUtils.coinListOf;
import static co.rsk.peg.bitcoin.BitcoinTestUtils.flatKeysAsByteArray;
import static org.junit.jupiter.api.Assertions.*;

import co.rsk.RskTestUtils;
import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.peg.BridgeAddresses;
import co.rsk.peg.BridgeEvents;
import co.rsk.peg.abi.AbiFunction;
import co.rsk.peg.bitcoin.*;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.federation.*;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.pegin.RejectedPeginReason;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.Wei;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/**
 * Test class for BridgeEventLoggerImpl.
 *
 * @author martin.medina
 */
class BridgeEventLoggerImplTest {
    private static final org.hyperledger.besu.datatypes.Address BRIDGE_ADDRESS = BridgeAddresses.BRIDGE;
    private static final BridgeConstants BRIDGE_CONSTANTS = BridgeMainNetConstants.getInstance();
    private static final FederationConstants FEDERATION_CONSTANTS = BRIDGE_CONSTANTS.getFederationConstants();
    private static final NetworkParameters NETWORK_PARAMETERS = BRIDGE_CONSTANTS.getBtcParams();
    private static final BtcTransaction BTC_TRANSACTION = new BtcTransaction(NETWORK_PARAMETERS);
    private static final org.hyperledger.besu.datatypes.Address RSK_ADDRESS = org.hyperledger.besu.datatypes.Address.fromHexString("0x0000000000000000000000000000000000000101");
    private static final Hash RSK_TX_HASH = RskTestUtils.createHash(1);
    private static final long RSK_EXECUTION_BLOCK_NUMBER = 15005L;

    private List<Log> eventLogs;
    private BridgeEventLogger eventLogger;

    @BeforeEach
    void setup() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        eventLogs = host.logs();
        eventLogger = new BridgeEventLoggerImpl(BRIDGE_CONSTANTS, host);
    }

    @Test
    void logPeginBtc() {
        Coin amount = Coin.SATOSHI;
        int protocolVersion = 1;

        // Act
        eventLogger.logPeginBtc(RSK_ADDRESS, BTC_TRANSACTION, amount, protocolVersion);

        // Assert log size
        assertEquals(1, eventLogs.size());

        Log logResult = eventLogs.get(0);
        AbiFunction event = BridgeEvents.PEGIN_BTC.getEvent();

        // Assert address that made the log
        assertEquals(BRIDGE_ADDRESS, logResult.getLogger());

        // Assert log topics
        assertEquals(3, logResult.getTopics().size());
        assertEquals(event.encodeEventTopics(RSK_ADDRESS, BTC_TRANSACTION.getHash().getBytes()), logResult.getTopics());

        // Assert log data
        assertEquals(event.encodeEventData(amount.getValue(), protocolVersion), logResult.getData());
    }

    @Test
    void logUpdateCollections() {
        // Act
        eventLogger.logUpdateCollections(RSK_ADDRESS);

        commonAssertLogs();
        assertTopics(1);
        assertEvent(
            BridgeEvents.UPDATE_COLLECTIONS.getEvent(),
            new Object[]{},
            new Object[]{RSK_ADDRESS}
        );
    }

    private static Stream<Arguments> logAddSignatureArgProvider() {
        BtcECKey btcKey = BtcECKey.fromPrivate(Hex.decode("000000000000000000000000000000000000000000000000000000000000015e")); // 350
        BtcECKey rskKey = BtcECKey.fromPrivate(Hex.decode("000000000000000000000000000000000000000000000000000000000000015f")); // 351
        BtcECKey mstKey = BtcECKey.fromPrivate(Hex.decode("0000000000000000000000000000000000000000000000000000000000000160")); // 352

        org.hyperledger.besu.datatypes.Address addressDerivedFromBtcKey = PublicKeys.addressOf(btcKey);
        org.hyperledger.besu.datatypes.Address addressDerivedFromRskKey = PublicKeys.addressOf(rskKey);

        FederationMember singleKeyFedMember = new FederationMember(
            btcKey,
            BtcECKey.fromPublicOnly(btcKey.getPubKey()),
            BtcECKey.fromPublicOnly(btcKey.getPubKey())
        );

        FederationMember multiKeyFedMember = new FederationMember(
            btcKey,
            rskKey,
            mstKey
        );

        return Stream.of(
            Arguments.of(singleKeyFedMember, addressDerivedFromBtcKey), // Given this is a single key fed member, the rsk address is equal to the one obtained from btc key
            Arguments.of(multiKeyFedMember, addressDerivedFromRskKey)
        );
    }

    @ParameterizedTest()
    @MethodSource("logAddSignatureArgProvider")
    void logAddSignature(FederationMember federationMember, org.hyperledger.besu.datatypes.Address expectedRskAddress) {
        // Arrange
        BtcECKey federatorBtcPubKey = federationMember.getBtcPublicKey();

        // Act
        eventLogger.logAddSignature(federationMember, BTC_TRANSACTION, RSK_TX_HASH.getBytes().toArrayUnsafe());

        // Assert
        commonAssertLogs();
        assertTopics(3);

        AbiFunction bridgeEvent = BridgeEvents.ADD_SIGNATURE.getEvent();
        Object[] eventTopics = new Object[]{RSK_TX_HASH.getBytes().toArrayUnsafe(), expectedRskAddress};
        Object[] eventParams = new Object[]{federatorBtcPubKey.getPubKey()};

        assertEvent(bridgeEvent, eventTopics, eventParams);
    }

    @Test
    void logReleaseBtc() {
        // Act
        eventLogger.logReleaseBtc(BTC_TRANSACTION, RSK_TX_HASH.getBytes().toArrayUnsafe());

        commonAssertLogs();
        assertTopics(2);
        assertEvent(
            BridgeEvents.RELEASE_BTC.getEvent(),
            new Object[]{RSK_TX_HASH.getBytes().toArrayUnsafe()},
            new Object[]{BTC_TRANSACTION.bitcoinSerialize()}
        );
    }

    @Test
    void logCommitFederation() {
        long federationActivationAge = FEDERATION_CONSTANTS.getFederationActivationAge();

        // Setup parameters for test method call
        Instant creationTime = Instant.ofEpochMilli(RSK_EXECUTION_BLOCK_NUMBER);
        Federation oldFederation = getOldFederation(creationTime);
        Federation newFederation = getNewFederation(creationTime);

        // Act
        eventLogger.logCommitFederation(RSK_EXECUTION_BLOCK_NUMBER, oldFederation, newFederation);
        commonAssertLogs();
        assertTopics(1);

        // Assert log data
        byte[] oldFederationFlatPubKeys = flatKeysAsByteArray(oldFederation.getBtcPublicKeys());
        String oldFederationBtcAddress = oldFederation.getAddress().toBase58();
        byte[] newFederationFlatPubKeys = flatKeysAsByteArray(newFederation.getBtcPublicKeys());
        String newFederationBtcAddress = newFederation.getAddress().toBase58();
        long newFedActivationBlockNumber = RSK_EXECUTION_BLOCK_NUMBER + federationActivationAge;

        Object[] data = new Object[]{
            oldFederationFlatPubKeys,
            oldFederationBtcAddress,
            newFederationFlatPubKeys,
            newFederationBtcAddress,
            newFedActivationBlockNumber
        };

        AbiFunction event = BridgeEvents.COMMIT_FEDERATION.getEvent();
        Object[] topics = {};
        assertEvent(event, topics, data);
    }

    private Federation getOldFederation(Instant creationTime) {
        List<BtcECKey> oldFederationKeys = Arrays.asList(
            BtcECKey.fromPublicOnly(Hex.decode("036bb9eab797eadc8b697f0e82a01d01cabbfaaca37e5bafc06fdc6fdd38af894a")),
            BtcECKey.fromPublicOnly(Hex.decode("031da807c71c2f303b7f409dd2605b297ac494a563be3b9ca5f52d95a43d183cc5")),
            BtcECKey.fromPublicOnly(Hex.decode("025eefeeeed5cdc40822880c7db1d0a88b7b986945ed3fc05a0b45fe166fe85e12")),
            BtcECKey.fromPublicOnly(Hex.decode("03c67ad63527012fd4776ae892b5dc8c56f80f1be002dc65cd520a2efb64e37b49"))
        );

        return getFederationFromBtcKeys(oldFederationKeys, creationTime);
    }

    private Federation getNewFederation(Instant creationTime) {
        List<BtcECKey> newFederationKeys = Arrays.asList(
            BtcECKey.fromPublicOnly(Hex.decode("0346cb6b905e4dee49a862eeb2288217d06afcd4ace4b5ca77ebedfbc6afc1c19d")),
            BtcECKey.fromPublicOnly(Hex.decode("0269a0dbe7b8f84d1b399103c466fb20531a56b1ad3a7b44fe419e74aad8c46db7")),
            BtcECKey.fromPublicOnly(Hex.decode("026192d8ab41bd402eb0431457f6756a3f3ce15c955c534d2b87f1e0372d8ba338"))
        );

        return getFederationFromBtcKeys(newFederationKeys, creationTime);
    }

    private Federation getFederationFromBtcKeys(List<BtcECKey> federationKeys, Instant creationTime) {
        NetworkParameters btcParams = BRIDGE_CONSTANTS.getBtcParams();

        List<FederationMember> federationMembers = FederationTestUtils.getFederationMembersWithBtcKeys(federationKeys);
        FederationArgs federationArgs = new FederationArgs(
            federationMembers,
            creationTime,
            15L,
            btcParams
        );

        return FederationFactory.buildStandardMultiSigFederation(federationArgs);
    }

    @Test
    void logCommitFederationFailed() {
        // arrange
        Federation proposedFederation = P2shErpFederationBuilder.builder().build();
        byte[] proposedFederationRedeemScriptSerialized = proposedFederation.getRedeemScript().getProgram();

        AbiFunction event = BridgeEvents.COMMIT_FEDERATION_FAILED.getEvent();
        var topics = new Object[]{};
        var data = new Object[]{
            proposedFederationRedeemScriptSerialized,
            RSK_EXECUTION_BLOCK_NUMBER
        };

        // act
        eventLogger.logCommitFederationFailure(RSK_EXECUTION_BLOCK_NUMBER, proposedFederation);

        // assert
        commonAssertLogs();
        assertTopics(1);
        assertEvent(event, topics, data);
    }

    @Test
    void logReleaseBtcRequested() {
        Coin amount = Coin.SATOSHI;

        eventLogger.logReleaseBtcRequested(RSK_TX_HASH.getBytes().toArrayUnsafe(), BTC_TRANSACTION, amount);

        commonAssertLogs();
        assertTopics(3);
        assertEvent(
            BridgeEvents.RELEASE_REQUESTED.getEvent(),
            new Object[]{RSK_TX_HASH.getBytes().toArrayUnsafe(), BTC_TRANSACTION.getHash().getBytes()},
            new Object[]{amount.getValue()}
        );
    }

    @Test
    void logRejectedPegin() {
        eventLogger.logRejectedPegin(BTC_TRANSACTION, RejectedPeginReason.PEGIN_CAP_SURPASSED);

        assertEquals(1, eventLogs.size());
        Log result = eventLogs.get(0);

        // Assert address that made the log
        assertEquals(BRIDGE_ADDRESS, result.getLogger());

        // Assert log topics
        assertEquals(2, result.getTopics().size());
        AbiFunction event = BridgeEvents.REJECTED_PEGIN.getEvent();
        assertEquals(event.encodeEventTopics(BTC_TRANSACTION.getHash().getBytes()), result.getTopics());

        // Assert log data
        assertEquals(event.encodeEventData(RejectedPeginReason.PEGIN_CAP_SURPASSED.getValue()), result.getData());
    }

    @Test
    void logNonRefundablePegin() {
        // Setup event logger
        eventLogger.logNonRefundablePegin(BTC_TRANSACTION, NonRefundablePeginReason.LEGACY_PEGIN_UNDETERMINED_SENDER);

        assertEquals(1, eventLogs.size());
        Log result = eventLogs.get(0);

        // Assert address that made the log
        assertEquals(BRIDGE_ADDRESS, result.getLogger());

        // Assert log topics
        assertEquals(2, result.getTopics().size());
        AbiFunction event = BridgeEvents.UNREFUNDABLE_PEGIN.getEvent();
        assertEquals(event.encodeEventTopics(BTC_TRANSACTION.getHash().getBytes()), result.getTopics());

        // Assert log data
        assertEquals(
            event.encodeEventData(NonRefundablePeginReason.LEGACY_PEGIN_UNDETERMINED_SENDER.getValue()),
            result.getData()
        );
    }

    @Test
    void logReleaseBtcRequestReceived() {
        Address btcRecipientAddress = new Address(
            NETWORK_PARAMETERS,
            NETWORK_PARAMETERS.getP2SHHeader(),
            Hex.decode("6bf06473af5f595cf97702229b007e50d6cfba83")
        );
        Wei amount = Weis.fromSatoshis(Coin.COIN);

        eventLogger.logReleaseBtcRequestReceived(RSK_ADDRESS, btcRecipientAddress, amount);

        commonAssertLogs();
        assertTopics(2);
        assertEvent(
            BridgeEvents.RELEASE_REQUEST_RECEIVED.getEvent(),
            new Object[]{RSK_ADDRESS},
            new Object[]{btcRecipientAddress.toString(), amount.toBigInteger()}
        );
    }

    @Test
    void logReleaseBtcRequestRejected() {
        Wei amount = Wei.of(100_000_000_000_000_000L);
        RejectedPegoutReason reason = RejectedPegoutReason.LOW_AMOUNT;

        eventLogger.logReleaseBtcRequestRejected(RSK_ADDRESS, amount, reason);

        commonAssertLogs();
        assertTopics(2);
        assertEvent(
            BridgeEvents.RELEASE_REQUEST_REJECTED.getEvent(),
            new Object[]{RSK_ADDRESS},
            new Object[]{amount.toBigInteger(), reason.getValue()}
        );
    }

    @Test
    void logBatchPegoutCreated() {
        List<Hash> rskTxHashes = Arrays.asList(
            RskTestUtils.createHash(0),
            RskTestUtils.createHash(1),
            RskTestUtils.createHash(2)
        );

        eventLogger.logBatchPegoutCreated(BTC_TRANSACTION.getHash(), rskTxHashes);

        commonAssertLogs();
        assertTopics(2);
        assertEvent(
            BridgeEvents.BATCH_PEGOUT_CREATED.getEvent(),
            new Object[]{BTC_TRANSACTION.getHash().getBytes()},
            new Object[]{serializeRskTxHashes(rskTxHashes)}
        );
    }

    @Test
    void logBatchPegoutCreatedWithWitness() {
        List<Hash> rskTxHashes = Arrays.asList(
            RskTestUtils.createHash(0),
            RskTestUtils.createHash(1),
            RskTestUtils.createHash(2)
        );

        TransactionWitness txWitness = new TransactionWitness(1);

        BTC_TRANSACTION.addInput(
            Sha256Hash.ZERO_HASH,
            0,
            ScriptBuilder.createInputScript(null, new BtcECKey())
        );

        txWitness.setPush(0, new byte[]{ 0x1 });
        BTC_TRANSACTION.setWitness(0, txWitness);
        eventLogger.logBatchPegoutCreated(BTC_TRANSACTION.getHash(true), rskTxHashes);

        commonAssertLogs();
        assertTopics(2);
        assertEvent(
            BridgeEvents.BATCH_PEGOUT_CREATED.getEvent(),
            new Object[]{BTC_TRANSACTION.getHash(true).getBytes()},
            new Object[]{serializeRskTxHashes(rskTxHashes)}
        );
    }

    @Test
    void logPegoutConfirmed() {
        long pegoutCreationRskBlockNumber = 50;
        eventLogger.logPegoutConfirmed(BTC_TRANSACTION.getHash(), pegoutCreationRskBlockNumber);

        commonAssertLogs();
        assertTopics(2);
        assertEvent(
            BridgeEvents.PEGOUT_CONFIRMED.getEvent(),
            new Object[]{BTC_TRANSACTION.getHash().getBytes()},
            new Object[]{pegoutCreationRskBlockNumber}
        );
    }

    private static Stream<Arguments> logPegoutTransactionCreatedValidArgProvider() {
        List<Arguments> args = new ArrayList<>();

        args.add(Arguments.of(Sha256Hash.ZERO_HASH, Collections.singletonList(Coin.SATOSHI)));
        args.add(Arguments.of(BitcoinTestUtils.createHash(5), coinListOf(500_000, 400_000, 1_000)));
        args.add(Arguments.of(BitcoinTestUtils.createHash(10), Collections.singletonList(Coin.ZERO)));
        args.add(Arguments.of(BitcoinTestUtils.createHash(10), Collections.EMPTY_LIST));
        args.add(Arguments.of(BitcoinTestUtils.createHash(15), null));

        return args.stream();
    }

    @ParameterizedTest()
    @MethodSource("logPegoutTransactionCreatedValidArgProvider")
    void logPegoutTransactionCreated_ok(Sha256Hash btcTxHash, List<Coin> outpointValues) {
        eventLogger.logPegoutTransactionCreated(btcTxHash, outpointValues);
        commonAssertLogs();

        assertTopics(2);

        AbiFunction expectedEvent = BridgeEvents.PEGOUT_TRANSACTION_CREATED.getEvent();
        Object[] topics = {btcTxHash.getBytes()};
        Object[] params = {UtxoUtils.encodeOutpointValues(outpointValues)};
        assertEvent(expectedEvent, topics, params);
    }

    private static Stream<Arguments> logPegoutTransactionCreatedInvalidArgProvider() {
        List<Arguments> args = new ArrayList<>();

        args.add(Arguments.of(null, Collections.singletonList(null), IllegalArgumentException.class));
        args.add(Arguments.of(null, Collections.singletonList(Coin.SATOSHI), IllegalArgumentException.class));
        args.add(Arguments.of(Sha256Hash.ZERO_HASH, Collections.singletonList(null), InvalidOutpointValueException.class));
        args.add(Arguments.of(BitcoinTestUtils.createHash(1), coinListOf(-100), InvalidOutpointValueException.class));
        args.add(Arguments.of(BitcoinTestUtils.createHash(2), coinListOf(100, -200, 300), InvalidOutpointValueException.class));

        return args.stream();
    }

    @ParameterizedTest()
    @MethodSource("logPegoutTransactionCreatedInvalidArgProvider")
    void logPegoutTransactionCreated_invalidBtcTxHashOrOutpointValues_shouldFail(Sha256Hash btcTxHash, List<Coin> outpointValues, Class<? extends  Exception> expectedException) {
        assertThrows(expectedException, () -> eventLogger.logPegoutTransactionCreated(btcTxHash, outpointValues));
    }

    /**********************************
     *  -------     UTILS     ------- *
     *********************************/
    private void assertEvent(AbiFunction event, Object[] topics, Object[] data) {
        Log log = eventLogs.get(0);

        assertEquals(event.encodeEventTopics(topics), log.getTopics());
        assertEquals(event.encodeEventData(data), log.getData());
    }

    private void assertTopics(int expectedTopicsSize) {
        int topicsSize = eventLogs.get(0).getTopics().size();

        assertEquals(expectedTopicsSize, topicsSize);
    }

    private void commonAssertLogs() {
        assertEquals(1, eventLogs.size());

        // Assert address that made the log
        Log entry = eventLogs.get(0);
        assertEquals(BRIDGE_ADDRESS, entry.getLogger());
    }

    private byte[] serializeRskTxHashes(List<Hash> rskTxHashes) {
        return Bytes.concatenate(rskTxHashes.stream().map(Hash::getBytes).toArray(Bytes[]::new)).toArrayUnsafe();
    }
}
