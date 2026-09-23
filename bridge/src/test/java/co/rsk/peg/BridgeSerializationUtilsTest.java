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

import static co.rsk.peg.BridgeSerializationUtils.deserializeOutpointsValues;
import static co.rsk.peg.BridgeSerializationUtils.deserializeRskTxHash;
import static co.rsk.peg.BridgeSerializationUtils.serializeOutpointsValues;
import static co.rsk.peg.PegTestUtils.createHash3;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.rsk.RskTestUtils;
import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.core.UTXO;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.peg.PegoutsWaitingForConfirmations.Entry;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.bitcoin.CoinbaseInformation;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.constants.BridgeTestNetConstants;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.federation.FederationArgs;
import co.rsk.peg.federation.FederationFactory;
import co.rsk.peg.federation.FederationMember;
import co.rsk.peg.federation.P2shErpFederationBuilder;
import co.rsk.peg.federation.PendingFederation;
import co.rsk.peg.federation.P2shP2wshErpFederationBuilder;
import co.rsk.peg.federation.StandardMultiSigFederationBuilder;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.utils.HashOrdering;
import co.rsk.peg.utils.MerkleTreeUtils;
import co.rsk.peg.vote.ABICallElection;
import co.rsk.peg.vote.ABICallSpec;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import com.google.common.collect.Lists;
import org.bouncycastle.util.encoders.Hex;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.rlp.RLPInput;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes;

/**
 * Ported from RSKj's BridgeSerializationUtilsTest. Differences from the original: RSK addresses and hashes are
 * Besu types, RSK-side keys are BtcECKey, hex expectations go through Bouncy Castle's Hex, RSKj's RLP helper
 * calls go through {@link RlpTestUtils}, the three non-standard ERP federation cases for the redeem script
 * builders that predate an activation are not ported, and the versionless federation format has no tests since
 * it is never read or written here.
 */
class BridgeSerializationUtilsTest {

    private static final BridgeConstants BRIDGE_MAINNET_CONSTANTS = BridgeMainNetConstants.getInstance();
    private static final NetworkParameters MAINNET_PARAMETERS = BRIDGE_MAINNET_CONSTANTS.getBtcParams();

    private static final BridgeConstants BRIDGE_TESTNET_CONSTANTS = BridgeTestNetConstants.getInstance();
    private static final NetworkParameters TESTNET_PARAMETERS = BRIDGE_TESTNET_CONSTANTS.getBtcParams();

    private static final Address ADDRESS = BitcoinTestUtils.createP2PKHAddress(MAINNET_PARAMETERS, "first");
    private static final Address OTHER_ADDRESS = BitcoinTestUtils.createP2PKHAddress(MAINNET_PARAMETERS, "second");

    @Test
    void serializeAndDeserializeBtcTransaction_withValidDataAndInputs_shouldReturnEqualResults() {
        // Arrange
        BtcTransaction prevTx = new BtcTransaction(MAINNET_PARAMETERS);
        prevTx.addOutput(Coin.FIFTY_COINS, ADDRESS);
        prevTx.addOutput(Coin.FIFTY_COINS, OTHER_ADDRESS);

        BtcTransaction btcTx = new BtcTransaction(MAINNET_PARAMETERS);
        btcTx.addInput(prevTx.getOutput(0));
        btcTx.addInput(prevTx.getOutput(1));
        btcTx.addOutput(Coin.COIN, OTHER_ADDRESS);

        // Act
        byte[] serializedBtcTransaction = BridgeSerializationUtils.serializeBtcTransaction(btcTx);
        BtcTransaction deserializedBtcTransaction = BridgeSerializationUtils.deserializeBtcTransactionWithInputs(serializedBtcTransaction, MAINNET_PARAMETERS);

        // Assert
        assertNotNull(serializedBtcTransaction);
        assertNotNull(deserializedBtcTransaction);
        assertEquals(btcTx, deserializedBtcTransaction);
    }

    @Test
    void serializeAndDeserializeBtcTransaction_withValidDataAndWithoutInputs_shouldReturnEqualResults() {
        // Arrange
        BtcTransaction prevTx = new BtcTransaction(MAINNET_PARAMETERS);
        prevTx.addOutput(Coin.FIFTY_COINS, ADDRESS);
        prevTx.addOutput(Coin.FIFTY_COINS, OTHER_ADDRESS);

        BtcTransaction btcTx = new BtcTransaction(MAINNET_PARAMETERS);
        btcTx.addOutput(Coin.COIN, OTHER_ADDRESS);

        // Act
        byte[] serializedBtcTransaction = BridgeSerializationUtils.serializeBtcTransaction(btcTx);
        BtcTransaction deserializedBtcTransaction = BridgeSerializationUtils.deserializeBtcTransactionWithoutInputs(serializedBtcTransaction, MAINNET_PARAMETERS);

        // Assert
        assertNotNull(serializedBtcTransaction);
        assertNotNull(deserializedBtcTransaction);
        assertEquals(btcTx, deserializedBtcTransaction);
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    void deserializeBtcTransaction_withInvalidData_shouldReturnNull(byte[] data) {
        // Act
        BtcTransaction deserializedTxWithInputs = BridgeSerializationUtils.deserializeBtcTransactionWithInputs(data, MAINNET_PARAMETERS);
        BtcTransaction deserializedTxWithoutInputs = BridgeSerializationUtils.deserializeBtcTransactionWithoutInputs(data, MAINNET_PARAMETERS);

        // Assert
        assertNull(deserializedTxWithInputs);
        assertNull(deserializedTxWithoutInputs);
    }

    @Test
    void serializeAndDeserializeSvpFundTransaction_withValidData_shouldReturnEqualResults() {
        // Arrange
        BtcTransaction prevTx = new BtcTransaction(MAINNET_PARAMETERS);
        prevTx.addOutput(Coin.FIFTY_COINS, ADDRESS);
        prevTx.addOutput(Coin.FIFTY_COINS, OTHER_ADDRESS);

        BtcTransaction svpFundTx = new BtcTransaction(MAINNET_PARAMETERS);
        svpFundTx.addInput(prevTx.getOutput(0));
        svpFundTx.addInput(prevTx.getOutput(1));
        svpFundTx.addOutput(Coin.COIN, OTHER_ADDRESS);

        // Act
        byte[] serializedSvpFundTransaction = BridgeSerializationUtils.serializeBtcTransaction(svpFundTx);
        BtcTransaction deserializedSvpFundTransaction = BridgeSerializationUtils.deserializeBtcTransactionWithInputs(serializedSvpFundTransaction, MAINNET_PARAMETERS);

        // Assert
        assertNotNull(serializedSvpFundTransaction);
        assertNotNull(deserializedSvpFundTransaction);
        assertEquals(svpFundTx, deserializedSvpFundTransaction);
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    void deserializeSvpFundTransaction_withInvalidData_shouldReturnNull(byte[] data) {
        // Act
        BtcTransaction result = BridgeSerializationUtils.deserializeBtcTransactionWithInputs(data, MAINNET_PARAMETERS);

        // Assert
        assertNull(result);
    }

    @Test
    void serializeAndDeserializeRskTxWaitingForSignatures_whenValidData_shouldReturnEqualResults() {
        // Arrange
        BtcTransaction prevTx = new BtcTransaction(MAINNET_PARAMETERS);
        prevTx.addOutput(Coin.FIFTY_COINS, ADDRESS);

        Hash pegoutCreationRskTxHash = createHash3(1);
        BtcTransaction pegoutTx = new BtcTransaction(MAINNET_PARAMETERS);
        pegoutTx.addInput(prevTx.getOutput(0));
        pegoutTx.addOutput(Coin.COIN, OTHER_ADDRESS);

        Map.Entry<Hash, BtcTransaction> pegoutTxWaitingForSignaturesEntry =
            new AbstractMap.SimpleEntry<>(pegoutCreationRskTxHash, pegoutTx);

        // Act
        byte[] serializedEntry =
            BridgeSerializationUtils.serializeRskTxWaitingForSignatures(pegoutTxWaitingForSignaturesEntry);
        Map.Entry<Hash, BtcTransaction> deserializedEntry =
            BridgeSerializationUtils.deserializeRskTxWaitingForSignatures(serializedEntry, MAINNET_PARAMETERS);

        // Assert
        assertNotNull(serializedEntry);
        assertTrue(serializedEntry.length > 0);

        assertNotNull(deserializedEntry);
        assertEquals(pegoutCreationRskTxHash, deserializedEntry.getKey());
        assertArrayEquals(pegoutTx.bitcoinSerialize(), deserializedEntry.getValue().bitcoinSerialize());
    }

    @Test
    void serializeRskTxWaitingForSignatures_whenNullValuePassed_shouldReturnEmptyResult() {
        // Act
        byte[] result =
            BridgeSerializationUtils.serializeRskTxWaitingForSignatures(null);

        // Assert
        assertArrayEquals(RlpTestUtils.encodedEmptyList(), result);
    }

    @ParameterizedTest
    @MethodSource("provideInvalidData")
    void deserializeRskTxWaitingForSignatures_whenInvalidData_shouldReturnEmptyResult(byte[] data) {
        // Act
        Map.Entry<Hash, BtcTransaction> result =
            BridgeSerializationUtils.deserializeRskTxWaitingForSignatures(data, MAINNET_PARAMETERS);

        // Assert
        assertNull(result);
    }

    private static Stream<byte[]> provideInvalidData() {
        return Stream.of(
            null,
            new byte[]{},
            RlpTestUtils.encodedEmptyList());
    }

    @Test
    void serializeAndDeserializeRskTxsWaitingForSignatures_whenValidEntries_shouldReturnEqualsResults() {
        // Arrange
        SortedMap<Hash, BtcTransaction> rskTxsWaitingForSignaturesMap = new TreeMap<>(HashOrdering.RSK);

        BtcTransaction prevTx = new BtcTransaction(MAINNET_PARAMETERS);
        prevTx.addOutput(Coin.COIN.multiply(5), ADDRESS);

        BtcTransaction pegoutTx1 = new BtcTransaction(MAINNET_PARAMETERS);
        pegoutTx1.addInput(prevTx.getOutput(0));
        pegoutTx1.addOutput(Coin.COIN, OTHER_ADDRESS);

        BtcTransaction pegoutTx2 = new BtcTransaction(MAINNET_PARAMETERS);
        pegoutTx2.addInput(prevTx.getOutput(0));
        pegoutTx2.addOutput(Coin.COIN.multiply(10), OTHER_ADDRESS);

        Hash pegoutCreationRskTxHash1 = createHash3(1);
        rskTxsWaitingForSignaturesMap.put(pegoutCreationRskTxHash1, pegoutTx1);

        Hash pegoutCreationRskTxHash2 = createHash3(2);
        rskTxsWaitingForSignaturesMap.put(pegoutCreationRskTxHash2, pegoutTx2);

        // Act
        byte[] serializedRskTxsWaitingForSignaturesMap =
            BridgeSerializationUtils.serializeRskTxsWaitingForSignatures(rskTxsWaitingForSignaturesMap);
        SortedMap<Hash, BtcTransaction> deserializedRskTxsWaitingForSignaturesMap =
            BridgeSerializationUtils.deserializeRskTxsWaitingForSignatures(serializedRskTxsWaitingForSignaturesMap, MAINNET_PARAMETERS);

        // Assert
        assertNotNull(serializedRskTxsWaitingForSignaturesMap);
        assertTrue(serializedRskTxsWaitingForSignaturesMap.length > 0);

        assertEquals(rskTxsWaitingForSignaturesMap, deserializedRskTxsWaitingForSignaturesMap);
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    void deserializeRskTxsWaitingForSignatures_whenInvalidData_shouldReturnEmptyResult(byte[] data) {
        // Act
        SortedMap<Hash, BtcTransaction> result =
            BridgeSerializationUtils.deserializeRskTxsWaitingForSignatures(data, MAINNET_PARAMETERS);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void deserializeRskTxHash_returnsExpectedHash() {
        // arrange
        Hash expectedRskTxHash = RskTestUtils.createHash(1);
        byte[] rskTxHashSerialized = expectedRskTxHash.getBytes().toArrayUnsafe();

        // act
        Hash rskTxHash = deserializeRskTxHash(rskTxHashSerialized);

        // assert
        assertEquals(expectedRskTxHash, rskTxHash);
    }

    @Test
    void deserializeRskTxHash_withInvalidLengthSerializedHash_throwsIllegalArgumentException() {
        // arrange
        byte[] rskTxHashSerialized = new byte[31];

        // act & assert
        assertThrows(IllegalArgumentException.class,
            () -> deserializeRskTxHash(rskTxHashSerialized));
    }

    @Test
    void deserializeRskTxHash_withNullValue_throwsIllegalArgumentException() {
        // act & assert
        assertThrows(IllegalArgumentException.class, () -> deserializeRskTxHash(null));
    }

    @Test
    void serializeAndDeserializeOutpointsValues_shouldReturnExpectedValues() {
        // arrange
        List<Coin> outpointsValues = Arrays.asList(Coin.valueOf(12345), Coin.SATOSHI, Coin.COIN);

        // Act
        byte[] serializedOutpointsValues = serializeOutpointsValues(outpointsValues);
        List<Coin> deserializedOutpointsValues = deserializeOutpointsValues(serializedOutpointsValues);

        // Assert
        assertEquals(outpointsValues, deserializedOutpointsValues);
    }

    @Test
    void deserializeOutpointsValues_withNullValue_throwsIllegalArgumentException() {
        // Assert
        assertThrows(IllegalArgumentException.class, () -> deserializeOutpointsValues(null));
    }

    @Test
    void serializeMapOfHashesToLong() {
        Map<Sha256Hash, Long> sample = new HashMap<>();
        sample.put(Sha256Hash.wrap(charNTimes('b', 64)), 1L);
        sample.put(Sha256Hash.wrap(charNTimes('d', 64)), 2L);
        sample.put(Sha256Hash.wrap(charNTimes('a', 64)), 3L);
        sample.put(Sha256Hash.wrap(charNTimes('c', 64)), 4L);

        byte[] result = BridgeSerializationUtils.serializeMapOfHashesToLong(sample);

        String hexResult = Hex.toHexString(result);
        StringBuilder expectedBuilder = new StringBuilder();

        expectedBuilder.append("f888");

        char[] sorted = new char[]{'a','b','c','d'};

        for (char c : sorted) {
            String key = charNTimes(c, 64);
            expectedBuilder.append("a0");
            expectedBuilder.append(key);
            expectedBuilder.append("0").append(sample.get(Sha256Hash.wrap(key)));
        }

        assertEquals(expectedBuilder.toString(), hexResult);
    }

    @Test
    void deserializeMapOfHashesToLong_emptyOrNull() {
        assertEquals(BridgeSerializationUtils.deserializeMapOfHashesToLong(null), new HashMap<>());
        assertEquals(BridgeSerializationUtils.deserializeMapOfHashesToLong(new byte[]{}), new HashMap<>());
    }

    @Test
    void deserializeMapOfHashesToLong_nonEmpty() {
        byte[] rlpFirstKey = RlpTestUtils.encodeElement(Hex.decode(charNTimes('b', 64)));
        byte[] rlpSecondKey = RlpTestUtils.encodeElement(Hex.decode(charNTimes('d', 64)));
        byte[] rlpThirdKey = RlpTestUtils.encodeElement(Hex.decode(charNTimes('a', 64)));

        byte[] rlpFirstValue = RlpTestUtils.encodeBigInteger(BigInteger.valueOf(7));
        byte[] rlpSecondValue = RlpTestUtils.encodeBigInteger(BigInteger.valueOf(76));
        byte[] rlpThirdValue = RlpTestUtils.encodeBigInteger(BigInteger.valueOf(123));

        byte[] data = RlpTestUtils.encodeList(rlpFirstKey, rlpFirstValue, rlpSecondKey, rlpSecondValue, rlpThirdKey, rlpThirdValue);

        Map<Sha256Hash, Long> result = BridgeSerializationUtils.deserializeMapOfHashesToLong(data);
        assertEquals(3, result.size());
        assertEquals(7L, result.get(Sha256Hash.wrap(charNTimes('b', 64))).longValue());
        assertEquals(76L, result.get(Sha256Hash.wrap(charNTimes('d', 64))).longValue());
        assertEquals(123L, result.get(Sha256Hash.wrap(charNTimes('a', 64))).longValue());
    }

    @Test
    void deserializeMapOfHashesToLong_nonEmptyOddSize() {
        byte[] rlpFirstKey = RlpTestUtils.encodeElement(Hex.decode(charNTimes('b', 64)));
        byte[] rlpSecondKey = RlpTestUtils.encodeElement(Hex.decode(charNTimes('d', 64)));
        byte[] rlpThirdKey = RlpTestUtils.encodeElement(Hex.decode(charNTimes('a', 64)));
        byte[] rlpFourthKey = RlpTestUtils.encodeElement(Hex.decode(charNTimes('e', 64)));

        byte[] rlpFirstValue = RlpTestUtils.encodeBigInteger(BigInteger.valueOf(7));
        byte[] rlpSecondValue = RlpTestUtils.encodeBigInteger(BigInteger.valueOf(76));
        byte[] rlpThirdValue = RlpTestUtils.encodeBigInteger(BigInteger.valueOf(123));

        byte[] data = RlpTestUtils.encodeList(rlpFirstKey, rlpFirstValue, rlpSecondKey, rlpSecondValue, rlpThirdKey, rlpThirdValue, rlpFourthKey);

        boolean thrown = false;

        try {
            BridgeSerializationUtils.deserializeMapOfHashesToLong(data);
        } catch (RuntimeException e) {
            thrown = true;
        }
        assertTrue(thrown);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("serialize and deserialize federations")
    class SerializeAndDeserializeFederation {
        private static final FederationConstants FEDERATION_MAINNET_CONSTANTS = BRIDGE_MAINNET_CONSTANTS.getFederationConstants();
        private static final List<BtcECKey> ERP_FED_PUB_KEYS_MAINNET = FEDERATION_MAINNET_CONSTANTS.getErpFedPubKeysList();
        private static final long ACTIVATION_DELAY_MAINNET = FEDERATION_MAINNET_CONSTANTS.getErpFedActivationDelay();
        private static final FederationConstants FEDERATION_TESTNET_CONSTANTS = BRIDGE_TESTNET_CONSTANTS.getFederationConstants();

        private static final Federation STANDARD_MULTISIG_FEDERATION = StandardMultiSigFederationBuilder.builder().build();
        private static final FederationArgs FEDERATION_ARGS_MAINNET = STANDARD_MULTISIG_FEDERATION.getArgs();

        @Test
        void serializeAndDeserializeStandardMultisigFederation() {
            // act
            byte[] serializedFederation = BridgeSerializationUtils.serializeFederation(STANDARD_MULTISIG_FEDERATION);
            Federation deserializedFederation =
                BridgeSerializationUtils.deserializeStandardMultisigFederation(serializedFederation, MAINNET_PARAMETERS);

            // assert
            assertEquals(STANDARD_MULTISIG_FEDERATION, deserializedFederation);
        }

        // RSKj also covers the non-standard ERP variants built with the redeem script builders that predate an
        // activation; only the current builder exists here.

        @Test
        void serializeAndDeserializeNonStandardErpFederation_testnet() {
            // arrange
            List<BtcECKey> erpFedPubKeys = FEDERATION_TESTNET_CONSTANTS.getErpFedPubKeysList();
            long activationDelay = FEDERATION_TESTNET_CONSTANTS.getErpFedActivationDelay();
            FederationArgs testnetArgs = new FederationArgs(
                FEDERATION_ARGS_MAINNET.getMembers(),
                FEDERATION_ARGS_MAINNET.getCreationTime(),
                FEDERATION_ARGS_MAINNET.getCreationBlockNumber(),
                TESTNET_PARAMETERS
            );

            Federation nonStandardErpFederation = FederationFactory.buildNonStandardErpFederation(
                testnetArgs,
                erpFedPubKeys,
                activationDelay
            );

            // act
            byte[] serializedFederation = BridgeSerializationUtils.serializeFederation(nonStandardErpFederation);
            Federation deserializedFederation = BridgeSerializationUtils.deserializeNonStandardErpFederation(
                serializedFederation,
                FEDERATION_TESTNET_CONSTANTS
            );

            // assert
            assertEquals(nonStandardErpFederation, deserializedFederation);
        }

        @Test
        void serializeAndDeserializeNonStandardErpFederation_mainnet() {
            // arrange
            Federation nonStandardErpFederation = FederationFactory.buildNonStandardErpFederation(
                FEDERATION_ARGS_MAINNET,
                ERP_FED_PUB_KEYS_MAINNET,
                ACTIVATION_DELAY_MAINNET
            );

            // act
            byte[] serializedFederation = BridgeSerializationUtils.serializeFederation(nonStandardErpFederation);
            Federation deserializedFederation = BridgeSerializationUtils.deserializeNonStandardErpFederation(
                serializedFederation,
                FEDERATION_MAINNET_CONSTANTS
            );

            // assert
            assertEquals(nonStandardErpFederation, deserializedFederation);
        }

        @Test
        void serializeAndDeserializeP2shErpFederation() {
            // arrange
            Federation p2shErpFederation = P2shErpFederationBuilder.builder().build();

            // act
            byte[] serializedFederation = BridgeSerializationUtils.serializeFederation(p2shErpFederation);
            Federation deserializedFederation =
                BridgeSerializationUtils.deserializeP2shErpFederation(serializedFederation, FEDERATION_MAINNET_CONSTANTS);

            // assert
            assertEquals(p2shErpFederation, deserializedFederation);
        }

        @Test
        void serializeAndDeserializeP2shP2wshErpFederation() {
            // arrange
            Federation p2ShP2wshErpFederation = P2shP2wshErpFederationBuilder.builder().build();

            // act
            byte[] serializedFederation = BridgeSerializationUtils.serializeFederation(p2ShP2wshErpFederation);
            Federation deserializedFederation =
                BridgeSerializationUtils.deserializeP2shP2wshErpFederation(serializedFederation, FEDERATION_MAINNET_CONSTANTS);

            // assert
            assertEquals(p2ShP2wshErpFederation, deserializedFederation);
        }
    }

    @Test
    void serializeFederation_serializedKeysAreCompressedAndThree() {
        final int NUM_MEMBERS = 10;
        final int EXPECTED_NUM_KEYS = 3;
        final int EXPECTED_PUBLICKEY_SIZE = 33;

        List<FederationMember> members = new ArrayList<>();
        for (int j = 0; j < NUM_MEMBERS; j++) {
            members.add(new FederationMember(new BtcECKey(), new BtcECKey(), new BtcECKey()));
        }

        Instant creationTime = Instant.now();
        long creationBlockNumber = 123;

        FederationArgs federationArgs =
            new FederationArgs(members, creationTime, creationBlockNumber, TESTNET_PARAMETERS);
        Federation testStandardMultisigFederation = FederationFactory.buildStandardMultiSigFederation(federationArgs);
        byte[] serializedFederation = BridgeSerializationUtils.serializeFederation(testStandardMultisigFederation);

        RLPInput serializedList = RLP.input(Bytes.wrap(serializedFederation));
        assertEquals(3, serializedList.enterList());
        serializedList.skipNext(); // creation time
        serializedList.skipNext(); // creation block number

        assertEquals(NUM_MEMBERS, serializedList.enterList());

        for (int i = 0; i < NUM_MEMBERS; i++) {
            RLPInput memberKeys = RLP.input(serializedList.readBytes());
            assertEquals(EXPECTED_NUM_KEYS, memberKeys.enterList());
            for (int j = 0; j < EXPECTED_NUM_KEYS; j++) {
                assertEquals(EXPECTED_PUBLICKEY_SIZE, memberKeys.readBytes().size());
            }
            memberKeys.leaveList();
        }
        serializedList.leaveList();
        serializedList.leaveList();
    }

    @Test
    void deserializeFederation_wrongListSize() {
        byte[] serialized = RlpTestUtils.encodeList(RlpTestUtils.encodeElement(new byte[0]), RlpTestUtils.encodeElement(new byte[0]));
        Exception ex = assertThrows(RuntimeException.class, () -> BridgeSerializationUtils.deserializeStandardMultisigFederation(serialized, TESTNET_PARAMETERS));
        assertTrue(ex.getMessage().contains("Invalid serialized Federation"));
    }

    @Test
    void deserializeFederation_invalidFederationMember() {
        byte[] serialized = RlpTestUtils.encodeList(
            RlpTestUtils.encodeElement(BigInteger.valueOf(1).toByteArray()),
            RlpTestUtils.encodeElement(BigInteger.valueOf(1).toByteArray()),
            RlpTestUtils.encodeList(RlpTestUtils.encodeList(RlpTestUtils.encodeElement(new byte[0]), RlpTestUtils.encodeElement(new byte[0])))
        );

        Exception ex = assertThrows(RuntimeException.class, () -> BridgeSerializationUtils.deserializeStandardMultisigFederation(serialized, TESTNET_PARAMETERS));
        assertTrue(ex.getMessage().contains("Invalid serialized FederationMember"));
    }

    @Test
    void serializeElection() {
        AddressBasedAuthorizer authorizer = getTestingAddressBasedAuthorizer();

        Map<ABICallSpec, List<org.hyperledger.besu.datatypes.Address>> sampleVotes = new HashMap<>();
        sampleVotes.put(
            new ABICallSpec("one-function", new byte[][]{}),
            Arrays.asList(createAddress("8899"), createAddress("aabb"))
        );
        sampleVotes.put(
            new ABICallSpec("another-function", new byte[][]{ Hex.decode("01"), Hex.decode("0203") }),
            Arrays.asList(createAddress("ccdd"), createAddress("eeff"), createAddress("0011"))
        );
        sampleVotes.put(
            new ABICallSpec("yet-another-function", new byte[][]{ Hex.decode("0405") }),
            Arrays.asList(createAddress("fa"), createAddress("ca"))
        );

        ABICallElection sample = new ABICallElection(authorizer, sampleVotes);

        byte[] result = BridgeSerializationUtils.serializeElection(sample);
        String hexResult = Hex.toHexString(result);

        StringBuilder expectedBuilder = new StringBuilder();

        expectedBuilder.append("f8d7d6");

        expectedBuilder.append("90");
        expectedBuilder.append(Hex.toHexString("another-function".getBytes(StandardCharsets.UTF_8)));
        expectedBuilder.append("c4");
        expectedBuilder.append("01");
        expectedBuilder.append("820203");
        expectedBuilder.append("f83f");
        expectedBuilder.append("94").append(hex(createAddress("0011")));
        expectedBuilder.append("94").append(hex(createAddress("ccdd")));
        expectedBuilder.append("94").append(hex(createAddress("eeff")));

        expectedBuilder.append("ce");
        expectedBuilder.append("8c");
        expectedBuilder.append(Hex.toHexString("one-function".getBytes(StandardCharsets.UTF_8)));
        expectedBuilder.append("c0");

        expectedBuilder.append("ea");
        expectedBuilder.append("94").append(hex(createAddress("8899")));
        expectedBuilder.append("94").append(hex(createAddress("aabb")));

        expectedBuilder.append("d9");
        expectedBuilder.append("94");
        expectedBuilder.append(Hex.toHexString("yet-another-function".getBytes(StandardCharsets.UTF_8)));
        expectedBuilder.append("c3");
        expectedBuilder.append("820405");
        expectedBuilder.append("ea");
        expectedBuilder.append("94").append(hex(createAddress("ca"))).append("94").append(hex(createAddress("fa")));

        assertEquals(expectedBuilder.toString(), hexResult);
    }

    @Test
    void deserializeElection_emptyOrNull() {
        AddressBasedAuthorizer authorizer = getTestingAddressBasedAuthorizer();
        ABICallElection election;
        election = BridgeSerializationUtils.deserializeElection(null, authorizer);
        assertEquals(0, election.getVotes().size());
        election = BridgeSerializationUtils.deserializeElection(new byte[]{}, authorizer);
        assertEquals(0, election.getVotes().size());
    }

    @Test
    void deserializeElection_nonEmpty() {
        AddressBasedAuthorizer authorizer = getTestingAddressBasedAuthorizer();

        ABICallSpec firstSpec = new ABICallSpec("funct", new byte[][]{});
        List<org.hyperledger.besu.datatypes.Address> firstVoters = Arrays.asList(
            createAddress("aa"),
            createAddress("bbccdd")
        );

        ABICallSpec secondSpec = new ABICallSpec("other-funct", new byte[][]{
            Hex.decode("1122"),
            Hex.decode("334455")
        });
        List<org.hyperledger.besu.datatypes.Address> secondVoters = Arrays.asList(
            createAddress("55"),
            createAddress("66"),
            createAddress("77")
        );

        ABICallSpec thirdSpec = new ABICallSpec("random-funct", new byte[][]{
            Hex.decode("aabb")
        });

        List<org.hyperledger.besu.datatypes.Address> thirdVoters = Arrays.asList(
            createAddress("1111"),
            createAddress("3333"),
            createAddress("5555"),
            createAddress("77")
        );

        Map<ABICallSpec, List<org.hyperledger.besu.datatypes.Address>> specsVotersToProcess = new HashMap<>();

        specsVotersToProcess.put(firstSpec, firstVoters);
        specsVotersToProcess.put(secondSpec, secondVoters);
        specsVotersToProcess.put(thirdSpec, thirdVoters);

        assertNotEquals(0, thirdVoters.get(0).getBytes().size());

        ABICallElection electionToProcess = new ABICallElection(authorizer, specsVotersToProcess);

        byte[] data = BridgeSerializationUtils.serializeElection(electionToProcess);

        ABICallElection election = BridgeSerializationUtils.deserializeElection(data, authorizer);

        assertEquals(3, election.getVotes().size());
        List<org.hyperledger.besu.datatypes.Address> voters;
        ABICallSpec spec;

        spec = new ABICallSpec("funct", new byte[][]{});
        assertTrue(election.getVotes().containsKey(spec));
        voters = Arrays.asList(
            createAddress("aa"),
            createAddress("bbccdd")
        );

        assertEquals(voters.get(0), election.getVotes().get(spec).get(0));
        assertEquals(voters.get(1), election.getVotes().get(spec).get(1));

        spec = new ABICallSpec("other-funct", new byte[][]{
            Hex.decode("1122"),
            Hex.decode("334455")
        });
        assertTrue(election.getVotes().containsKey(spec));
        voters = Arrays.asList(
            createAddress("55"),
            createAddress("66"),
            createAddress("77")
        );

        assertEquals(voters.get(0), election.getVotes().get(spec).get(0));
        assertEquals(voters.get(1), election.getVotes().get(spec).get(1));
        assertEquals(voters.get(2), election.getVotes().get(spec).get(2));

        spec = new ABICallSpec("random-funct", new byte[][]{
            Hex.decode("aabb")
        });
        assertTrue(election.getVotes().containsKey(spec));
        voters = Arrays.asList(
            createAddress("1111"),
            createAddress("3333"),
            createAddress("5555"),
            createAddress("77")
        );

        assertEquals(4, election.getVotes().get(spec).size());
        assertEquals(voters.get(0), election.getVotes().get(spec).get(0));
        assertEquals(voters.get(1), election.getVotes().get(spec).get(1));
        assertEquals(voters.get(2), election.getVotes().get(spec).get(2));
        assertEquals(voters.get(3), election.getVotes().get(spec).get(3));
    }

    @Test
    void deserializeElection_unevenOuterList() {
        AddressBasedAuthorizer mockedAuthorizer = mock(AddressBasedAuthorizer.class);

        byte[] rlpFirstElement = RlpTestUtils.encodeElement(Hex.decode("010203"));
        byte[] data = RlpTestUtils.encodeList(rlpFirstElement);

        try {
            BridgeSerializationUtils.deserializeElection(data, mockedAuthorizer);
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("expected an even number of entries, but odd given"));
            return;
        }

        fail();
    }

    @Test
    void deserializeElection_invalidCallSpec() {
        AddressBasedAuthorizer authorizer = getTestingAddressBasedAuthorizer();

        byte[] rlpFirstSpec = RlpTestUtils.encodeList(RlpTestUtils.encodeElement(Hex.decode("010203"))); // invalid spec
        byte[] rlpFirstVoters = RlpTestUtils.encodeList(RlpTestUtils.encodeElement(Hex.decode("03"))); // doesn't matter

        byte[] data = RlpTestUtils.encodeList(rlpFirstSpec, rlpFirstVoters);

        try {
            BridgeSerializationUtils.deserializeElection(data, authorizer);
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Invalid serialized ABICallSpec"));
            return;
        }

        fail();
    }

    @Test
    void serializeRequestQueue() {
        List<ReleaseRequestQueue.Entry> sampleEntries = Arrays.asList(
            new ReleaseRequestQueue.Entry(mockAddressHash160("ccdd"), Coin.valueOf(10)),
            new ReleaseRequestQueue.Entry(mockAddressHash160("bb"), Coin.valueOf(50)),
            new ReleaseRequestQueue.Entry(mockAddressHash160("bb"), Coin.valueOf(20)),
            new ReleaseRequestQueue.Entry(mockAddressHash160("aa"), Coin.valueOf(30))
        );
        ReleaseRequestQueue sample = new ReleaseRequestQueue(sampleEntries);

        byte[] result = BridgeSerializationUtils.serializeReleaseRequestQueue(sample);
        String hexResult = Hex.toHexString(result);
        StringBuilder expectedBuilder = new StringBuilder();
        expectedBuilder.append("cd");
        expectedBuilder.append("82ccdd");
        expectedBuilder.append("0a");
        expectedBuilder.append("81bb");
        expectedBuilder.append("32");
        expectedBuilder.append("81bb");
        expectedBuilder.append("14");
        expectedBuilder.append("81aa");
        expectedBuilder.append("1e");
        assertEquals(expectedBuilder.toString(), hexResult);
    }

    @Test
    void deserializeRequestQueue_emptyOrNull() {
        assertEquals(0, BridgeSerializationUtils.deserializeReleaseRequestQueue(null, TESTNET_PARAMETERS).size());
        assertEquals(0, BridgeSerializationUtils.deserializeReleaseRequestQueue(new byte[]{}, TESTNET_PARAMETERS).size());
    }

    @Test
    void deserializeRequestQueue_nonEmpty() {
        NetworkParameters params = TESTNET_PARAMETERS;

        Address a1 = Address.fromBase58(params, "mynmcQfJnVjheAqh9XL6htnxPZnaDFbqkB");
        Address a2 = Address.fromBase58(params, "mfrfxeo5L2f5NDURS6YTtCNfVw2t5HAfty");
        Address a3 = Address.fromBase58(params, "myw7AMh5mpKHao6MArhn7EvkeASGsGJzrZ");

        List<ReleaseRequestQueue.Entry> expectedEntries = Arrays.asList(
            new ReleaseRequestQueue.Entry(a1, Coin.valueOf(10)),
            new ReleaseRequestQueue.Entry(a2, Coin.valueOf(7)),
            new ReleaseRequestQueue.Entry(a3, Coin.valueOf(8))
        );

        byte[][] rlpItems = new byte[6][];

        rlpItems[0] = RlpTestUtils.encodeElement(a1.getHash160());
        rlpItems[1] = RlpTestUtils.encodeBigInteger(BigInteger.valueOf(10));
        rlpItems[2] = RlpTestUtils.encodeElement(a2.getHash160());
        rlpItems[3] = RlpTestUtils.encodeBigInteger(BigInteger.valueOf(7));
        rlpItems[4] = RlpTestUtils.encodeElement(a3.getHash160());
        rlpItems[5] = RlpTestUtils.encodeBigInteger(BigInteger.valueOf(8));

        byte[] data = RlpTestUtils.encodeList(rlpItems);

        ReleaseRequestQueue result = new ReleaseRequestQueue(BridgeSerializationUtils.deserializeReleaseRequestQueue(data, params));

        List<ReleaseRequestQueue.Entry> entries = result.getEntries();
        assertEquals(expectedEntries, entries);
    }

    @Test
    void deserializeRequestQueue_nonEmptyOddSize() {
        NetworkParameters params = TESTNET_PARAMETERS;

        Address a1 = Address.fromBase58(params, "mynmcQfJnVjheAqh9XL6htnxPZnaDFbqkB");
        Address a2 = Address.fromBase58(params, "mfrfxeo5L2f5NDURS6YTtCNfVw2t5HAfty");
        Address a3 = Address.fromBase58(params, "myw7AMh5mpKHao6MArhn7EvkeASGsGJzrZ");

        byte[][] rlpItems = new byte[7][];

        rlpItems[0] = RlpTestUtils.encodeElement(a1.getHash160());
        rlpItems[1] = RlpTestUtils.encodeBigInteger(BigInteger.valueOf(10));
        rlpItems[2] = RlpTestUtils.encodeElement(a2.getHash160());
        rlpItems[3] = RlpTestUtils.encodeBigInteger(BigInteger.valueOf(7));
        rlpItems[4] = RlpTestUtils.encodeElement(a3.getHash160());
        rlpItems[5] = RlpTestUtils.encodeBigInteger(BigInteger.valueOf(8));
        rlpItems[6] = RlpTestUtils.encodeBigInteger(BigInteger.valueOf(8));

        byte[] data = RlpTestUtils.encodeList(rlpItems);

        try {
            BridgeSerializationUtils.deserializeReleaseRequestQueue(data, params);
        } catch (RuntimeException e) {
            return;
        }
        fail();
    }

    @Test
    void serializeTransactionSet() {
        Set<PegoutsWaitingForConfirmations.Entry> sampleEntries = new HashSet<>(Arrays.asList(
            new PegoutsWaitingForConfirmations.Entry(mockBtcTransactionSerialize("ccdd"), 10L),
            new PegoutsWaitingForConfirmations.Entry(mockBtcTransactionSerialize("bb"), 20L),
            new PegoutsWaitingForConfirmations.Entry(mockBtcTransactionSerialize("ba"), 30L),
            new PegoutsWaitingForConfirmations.Entry(mockBtcTransactionSerialize("aa"), 40L)
        ));
        PegoutsWaitingForConfirmations sample = new PegoutsWaitingForConfirmations(sampleEntries);

        byte[] result = BridgeSerializationUtils.serializePegoutsWaitingForConfirmations(sample);
        String hexResult = Hex.toHexString(result);
        StringBuilder expectedBuilder = new StringBuilder();
        expectedBuilder.append("cd");
        expectedBuilder.append("81aa");
        expectedBuilder.append("28");
        expectedBuilder.append("81ba");
        expectedBuilder.append("1e");
        expectedBuilder.append("81bb");
        expectedBuilder.append("14");
        expectedBuilder.append("82ccdd");
        expectedBuilder.append("0a");
        assertEquals(expectedBuilder.toString(), hexResult);
    }

    @Test
    void deserializeTransactionSet_emptyOrNull() {
        assertEquals(0, BridgeSerializationUtils.deserializePegoutsWaitingForConfirmations(null, TESTNET_PARAMETERS).getEntries().size());
        assertEquals(0, BridgeSerializationUtils.deserializePegoutsWaitingForConfirmations(new byte[]{}, TESTNET_PARAMETERS).getEntries().size());
    }

    @Test
    void deserializeTransactionSet_nonEmpty() {
        NetworkParameters params = TESTNET_PARAMETERS;

        BtcTransaction input = new BtcTransaction(params);
        input.addOutput(Coin.FIFTY_COINS, Address.fromBase58(params, "mvc8mwDcdLEq2jGqrL43Ub3sxTR13tB8LL"));

        BtcTransaction t1 = new BtcTransaction(params);
        t1.addInput(input.getOutput(0));
        t1.addOutput(Coin.COIN, Address.fromBase58(params, "n3CaAPu2PR7FDdGK8tFwe8thr7hV7zz599"));
        BtcTransaction t2 = new BtcTransaction(params);
        t2.addInput(input.getOutput(0));
        t2.addOutput(Coin.COIN.multiply(10), Address.fromBase58(params, "n3CaAPu2PR7FDdGK8tFwe8thr7hV7zz599"));
        BtcTransaction t3 = new BtcTransaction(params);
        t3.addInput(input.getOutput(0));
        t3.addOutput(Coin.valueOf(15), Address.fromBase58(params, "n3CaAPu2PR7FDdGK8tFwe8thr7hV7zz599"));
        BtcTransaction t4 = new BtcTransaction(params);
        t4.addInput(input.getOutput(0));
        t4.addOutput(Coin.MILLICOIN, Address.fromBase58(params, "n3CaAPu2PR7FDdGK8tFwe8thr7hV7zz599"));

        Set<PegoutsWaitingForConfirmations.Entry> expectedEntries = new HashSet<>(Arrays.asList(
            new PegoutsWaitingForConfirmations.Entry(t1, 32L),
            new PegoutsWaitingForConfirmations.Entry(t2, 14L),
            new PegoutsWaitingForConfirmations.Entry(t3, 102L),
            new PegoutsWaitingForConfirmations.Entry(t4, 20L)
        ));

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = new PegoutsWaitingForConfirmations(expectedEntries);

        byte[] data = BridgeSerializationUtils.serializePegoutsWaitingForConfirmations(pegoutsWaitingForConfirmations);

        PegoutsWaitingForConfirmations result = BridgeSerializationUtils.deserializePegoutsWaitingForConfirmations(data, params);

        Set<PegoutsWaitingForConfirmations.Entry> entries = result.getEntries();

        assertEquals(expectedEntries, entries);
    }

    @Test
    void deserializeTransactionSet_nonEmpty_withTxHash_fails() {
        NetworkParameters params = TESTNET_PARAMETERS;

        BtcTransaction input = new BtcTransaction(params);
        input.addOutput(Coin.FIFTY_COINS, Address.fromBase58(params, "mvc8mwDcdLEq2jGqrL43Ub3sxTR13tB8LL"));

        BtcTransaction t1 = new BtcTransaction(params);
        t1.addInput(input.getOutput(0));
        t1.addOutput(Coin.COIN, Address.fromBase58(params, "n3CaAPu2PR7FDdGK8tFwe8thr7hV7zz599"));

        Set<PegoutsWaitingForConfirmations.Entry> expectedEntries = new HashSet<>(Collections.singletonList(
            new Entry(t1, 32L, PegTestUtils.createHash3(0))
        ));

        PegoutsWaitingForConfirmations rtc = new PegoutsWaitingForConfirmations(expectedEntries);
        byte[] serializedEntries = BridgeSerializationUtils.serializePegoutsWaitingForConfirmationsWithTxHash(rtc);

        assertThrows(RuntimeException.class, () -> BridgeSerializationUtils.deserializePegoutsWaitingForConfirmations(serializedEntries, params));
    }

    @Test
    void deserializeTransactionSet_nonEmpty_withoutTxHash_fails() {
        NetworkParameters params = TESTNET_PARAMETERS;

        BtcTransaction input = new BtcTransaction(params);
        input.addOutput(Coin.FIFTY_COINS, Address.fromBase58(params, "mvc8mwDcdLEq2jGqrL43Ub3sxTR13tB8LL"));

        BtcTransaction t1 = new BtcTransaction(params);
        t1.addInput(input.getOutput(0));
        t1.addOutput(Coin.COIN, Address.fromBase58(params, "n3CaAPu2PR7FDdGK8tFwe8thr7hV7zz599"));

        Set<PegoutsWaitingForConfirmations.Entry> expectedEntries = new HashSet<>(Collections.singletonList(
            new Entry(t1, 32L)
        ));

        PegoutsWaitingForConfirmations rtc = new PegoutsWaitingForConfirmations(expectedEntries);
        byte[] serializedEntries = BridgeSerializationUtils.serializePegoutsWaitingForConfirmations(rtc);

        assertThrows(RuntimeException.class, () -> BridgeSerializationUtils.deserializePegoutsWaitingForConfirmations(serializedEntries, params, true));
    }

    @Test
    void deserializeTransactionSet_nonEmptyOddSize() {
        byte[] firstItem = RlpTestUtils.encodeElement(Hex.decode("010203"));
        byte[] data = RlpTestUtils.encodeList(firstItem);

        try {
            BridgeSerializationUtils.deserializePegoutsWaitingForConfirmations(data, TESTNET_PARAMETERS);
        } catch (RuntimeException e) {
            return;
        }
        fail();
    }

    @Test
    void serializeDeserializeCoin() {
        byte[] serialized1 = BridgeSerializationUtils.serializeCoin(Coin.COIN);
        assertEquals(Coin.COIN, BridgeSerializationUtils.deserializeCoin(serialized1));
        byte[] serialized2 = BridgeSerializationUtils.serializeCoin(Coin.valueOf(Long.MAX_VALUE));
        assertEquals(Coin.valueOf(Long.MAX_VALUE), BridgeSerializationUtils.deserializeCoin(serialized2));
        byte[] serialized3 = BridgeSerializationUtils.serializeCoin(Coin.ZERO);
        assertEquals(Coin.ZERO, BridgeSerializationUtils.deserializeCoin(serialized3));
        assertNull(BridgeSerializationUtils.deserializeCoin(null));
        assertNull(BridgeSerializationUtils.deserializeCoin(new byte[0]));
    }

    @Test
    void serializeInteger() {
        assertEquals(BigInteger.valueOf(123), RlpTestUtils.decodeBigInteger(BridgeSerializationUtils.serializeInteger(123)));
        assertEquals(BigInteger.valueOf(1200), RlpTestUtils.decodeBigInteger(BridgeSerializationUtils.serializeInteger(1200)));
    }

    @Test
    void deserializeInteger() {
        assertEquals(123, BridgeSerializationUtils.deserializeInteger(RlpTestUtils.encodeBigInteger(BigInteger.valueOf(123))).intValue());
        assertEquals(1200, BridgeSerializationUtils.deserializeInteger(RlpTestUtils.encodeBigInteger(BigInteger.valueOf(1200))).intValue());
    }

    @Test
    void serializeSha256Hash() {
        Sha256Hash originalHash = BitcoinTestUtils.createHash(2);
        byte[] encodedHash = RlpTestUtils.encodeElement(originalHash.getBytes());

        byte[] result = BridgeSerializationUtils.serializeSha256Hash(originalHash);

        assertArrayEquals(encodedHash, result);
    }

    @Test
    void deserializeSha256Hash() {
        Sha256Hash originalHash = BitcoinTestUtils.createHash(2);
        byte[] encodedHash = RlpTestUtils.encodeElement(originalHash.getBytes());

        Sha256Hash result = BridgeSerializationUtils.deserializeSha256Hash(encodedHash);
        assertEquals(originalHash, result);
    }

    @Test
    void deserializeSha256Hash_nullValue() {
        Sha256Hash result = BridgeSerializationUtils.deserializeSha256Hash(null);
        assertNull(result);
    }

    @Test
    void deserializeSha256Hash_hashWithLeadingZero() {
        Sha256Hash originalHash = BitcoinTestUtils.createHash(0);
        byte[] encodedHash = RlpTestUtils.encodeElement(originalHash.getBytes());

        Sha256Hash result = BridgeSerializationUtils.deserializeSha256Hash(encodedHash);
        assertEquals(originalHash, result);
    }

    @Test
    void serializeScript() {
        Script expectedScript = ScriptBuilder.createP2SHOutputScript(2, Lists.newArrayList(new BtcECKey(), new BtcECKey(), new BtcECKey()));

        byte[] actualData = BridgeSerializationUtils.serializeScript(expectedScript);

        RLPInput serialized = RLP.input(Bytes.wrap(actualData));
        serialized.enterList();
        assertEquals(expectedScript, new Script(serialized.readBytes().toArrayUnsafe()));
    }

    @Test
    void deserializeScript() {
        Script expectedScript = ScriptBuilder.createP2SHOutputScript(2, Lists.newArrayList(new BtcECKey(), new BtcECKey(), new BtcECKey()));
        byte[] data = RlpTestUtils.encodeList(RlpTestUtils.encodeElement(expectedScript.getProgram()));

        Script actualScript = BridgeSerializationUtils.deserializeScript(data);

        assertEquals(expectedScript, actualScript);
    }


    @Test
    void deserializeCoinbaseInformation_dataIsNull_returnsNull() {
        assertNull(BridgeSerializationUtils.deserializeCoinbaseInformation(null));
    }

    @Test
    void deserializeCoinbaseInformation_dataContainsInvalidList_throwsRuntimeException() {
        byte[] firstItem = RlpTestUtils.encodeElement(Hex.decode("010101"));
        byte[] secondItem = RlpTestUtils.encodeElement(Hex.decode("010102"));
        byte[] thirdItem = RlpTestUtils.encodeElement(Hex.decode("010103"));
        byte[] data = RlpTestUtils.encodeList(firstItem, secondItem, thirdItem);

        try {
            BridgeSerializationUtils.deserializeCoinbaseInformation(data);
            fail("Runtime exception should be thrown!");
        } catch (RuntimeException e) {
            assertEquals("Invalid serialized coinbase information, expected 1 value but got 3", e.getMessage());
        }
    }

    @Test
    void deserializeCoinbaseInformation_dataIsValid_returnsValidCoinbaseInformation() {
        Sha256Hash secondHashTx = Sha256Hash.wrap(Hex.decode("e3d0840a0825fb7d880e5cb8306745352920a8c7e8a30fac882b275e26c6bb65"));
        Sha256Hash witnessRoot = MerkleTreeUtils.combineLeftRight(Sha256Hash.ZERO_HASH, secondHashTx);

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(witnessRoot);
        byte[] serializedCoinbaseInformation = BridgeSerializationUtils.serializeCoinbaseInformation(coinbaseInformation);

        assertEquals(witnessRoot, BridgeSerializationUtils.deserializeCoinbaseInformation(serializedCoinbaseInformation).getWitnessMerkleRoot());
    }

    private Address mockAddressHash160(String hash160) {
        Address result = mock(Address.class);
        when(result.getHash160()).thenReturn(Hex.decode(hash160));
        return result;
    }

    private BtcTransaction mockBtcTransactionSerialize(String serialized) {
        BtcTransaction result = mock(BtcTransaction.class);
        when(result.bitcoinSerialize()).thenReturn(Hex.decode(serialized));
        return result;
    }

    private String charNTimes(char c, int n) {
        StringBuilder sb = new StringBuilder(n);

        for (int i = 0; i < n; i++) {
            sb.append(c);
        }

        return sb.toString();
    }

    private static final int ADDRESS_LENGTH_IN_BYTES = 20;

    /** An RSK address from a hex prefix, right-padded with zeros to 20 bytes, as RSKj's createAddress built it. */
    private org.hyperledger.besu.datatypes.Address createAddress(String addr) {
        String address;

        if (addr.length() < ADDRESS_LENGTH_IN_BYTES * 2) {
            address = addr + charNTimes('0', ADDRESS_LENGTH_IN_BYTES * 2 - addr.length());
        }
        else {
            address = addr;
        }

        return org.hyperledger.besu.datatypes.Address.wrap(Bytes.fromHexString(address));
    }

    /** RSKj appended {@code RskAddress.toString()}, which is the unprefixed hex. */
    private static String hex(org.hyperledger.besu.datatypes.Address address) {
        return address.getBytes().toUnprefixedHexString();
    }

    private static AddressBasedAuthorizer getTestingAddressBasedAuthorizer() {
        return new AddressBasedAuthorizer(Collections.emptyList(), null) {
            @Override
            public boolean isAuthorized(org.hyperledger.besu.datatypes.Address address) {
                return true;
            }
        };
    }

    /**
     * Bytes recorded from RSKj for fixed inputs. The mainnet cases were produced by RSKj's own code running in the
     * rskj repository (bmb-rskj 42eefc5e2) on the same inputs; the regtest cases by that code as copied into this
     * module before the serializers were rewritten on Besu RLP. They pin what round trips cannot: byte identity
     * with RSKj for the encodings the tests above do not spell out.
     */
    @Nested
    class RecordedRskjBytes {

        private static final FederationConstants REGTEST_FED = new BridgeRegTestConstants().getFederationConstants();
        private static final NetworkParameters REGTEST = REGTEST_FED.getBtcParams();
        private static final FederationConstants MAINNET_FED = BRIDGE_MAINNET_CONSTANTS.getFederationConstants();
        private static final NetworkParameters MAINNET = MAINNET_FED.getBtcParams();

        private BtcECKey publicKey(long privateKey) {
            return BtcECKey.fromPublicOnly(BtcECKey.fromPrivate(BigInteger.valueOf(privateKey)).getPubKey());
        }

        private Hash rskTxHash(int seed) {
            return Hash.hash(Bytes.of((byte) seed));
        }

        private Sha256Hash btcHash(int seed) {
            return Sha256Hash.of(new byte[] {(byte) seed});
        }

        /** RSK and MST keys given uncompressed when asked, to show the encoding compresses them. */
        private FederationMember member(int i, boolean uncompressedInputs) {
            byte[] rsk = publicKey(2001 + i).getPubKeyPoint().getEncoded(!uncompressedInputs);
            byte[] mst = publicKey(3001 + i).getPubKeyPoint().getEncoded(!uncompressedInputs);
            return new FederationMember(publicKey(1001 + i), BtcECKey.fromPublicOnly(rsk), BtcECKey.fromPublicOnly(mst));
        }

        private List<FederationMember> members(int count, boolean uncompressedInputs) {
            List<FederationMember> out = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                out.add(member(i, uncompressedInputs));
            }
            return out;
        }

        private Federation regtestFederation() {
            List<FederationMember> sorted = members(5, false);
            sorted.sort(FederationMember.BTC_RSK_MST_PUBKEYS_COMPARATOR);
            return FederationFactory.buildStandardMultiSigFederation(new FederationArgs(sorted, Instant.ofEpochMilli(1_700_000_000_000L), 42, REGTEST));
        }

        private BtcTransaction regtestBtcTx(int seed) {
            BtcTransaction tx = new BtcTransaction(REGTEST);
            tx.addInput(btcHash(20 + seed), seed, new Script(new byte[0]));
            tx.addOutput(Coin.valueOf(50_000L * seed), publicKey(5001 + seed).toAddress(REGTEST));
            tx.addOutput(Coin.valueOf(1_000), regtestFederation().getAddress());
            return tx;
        }

        private BtcTransaction mainnetBtcTx(int i) {
            BtcTransaction tx = new BtcTransaction(MAINNET);
            tx.addInput(btcHash(20 + i), i, new Script(new byte[0]));
            tx.addOutput(Coin.valueOf(1000L * (i + 1)), publicKey(5001 + i).toAddress(MAINNET));
            return tx;
        }

        @Test
        void serializeFederation_matchesRskjBytes() {
            Federation federation = FederationFactory.buildStandardMultiSigFederation(
                new FederationArgs(members(3, false), Instant.ofEpochMilli(Long.MAX_VALUE / 2), Long.MAX_VALUE / 3, MAINNET));
            String expected = "f90153883fffffffffffffff882aaaaaaaaaaaaaaaf9013eb868f866a1031fb966918db3af46c37234b6a4b043719886"
                + "d6a05859ba32f72742d6141f7ae6a103f814a79cf258f553255c1cb3a054fcc0d0c71d74742b6627210ea84691596729"
                + "a103e5476b1ea99b6a08837315427a3751b83d685b34acc5201e59e9b623ac4b6941b868f866a10370b55404702ffa86"
                + "ecfa4e88e0f354004a0965a5eea5fbbd297436001ae920dfa1029cbf013d04ca50ba852816c2802b06ca5ed37b44be95"
                + "97fc0f95360e209afa97a10395d9fcbfcd5d977d9b3822b49b1c63750541999a71f6305d255060e1e2fcf816b868f866"
                + "a1039d1abaec9f5715a15c7628244170951e0f85e87f68ca5393d3f9fc3fa23a69c8a1038d3f06b158ddd609f83b0531"
                + "466fc2a3da6aa80b433a92ddeeb20435cf33ddaea1021388065ddd7f69a011dec106b539f7e9e00b5aff075688200e33"
                + "f9c1018881ed";
            assertEquals(expected, Hex.toHexString(BridgeSerializationUtils.serializeFederation(federation)));
        }

        @Test
        void serializeFederationMember_matchesRskjBytes() {
            String expected = "f866a1039d1abaec9f5715a15c7628244170951e0f85e87f68ca5393d3f9fc3fa23a69c8a1038d3f06b158ddd609f83b"
                + "0531466fc2a3da6aa80b433a92ddeeb20435cf33ddaea1021388065ddd7f69a011dec106b539f7e9e00b5aff07568820"
                + "0e33f9c1018881ed";
            assertEquals(expected, Hex.toHexString(member(0, true).serialize()));
        }

        @Test
        void serializePendingFederation_matchesRskjBytes() {
            // Members are nested as lists here, unlike a Federation, which wraps each member as an element.
            String expected = "f90138f866a1031fb966918db3af46c37234b6a4b043719886d6a05859ba32f72742d6141f7ae6a103f814a79cf258f5"
                + "53255c1cb3a054fcc0d0c71d74742b6627210ea84691596729a103e5476b1ea99b6a08837315427a3751b83d685b34ac"
                + "c5201e59e9b623ac4b6941f866a10370b55404702ffa86ecfa4e88e0f354004a0965a5eea5fbbd297436001ae920dfa1"
                + "029cbf013d04ca50ba852816c2802b06ca5ed37b44be9597fc0f95360e209afa97a10395d9fcbfcd5d977d9b3822b49b"
                + "1c63750541999a71f6305d255060e1e2fcf816f866a1039d1abaec9f5715a15c7628244170951e0f85e87f68ca5393d3"
                + "f9fc3fa23a69c8a1038d3f06b158ddd609f83b0531466fc2a3da6aa80b433a92ddeeb20435cf33ddaea1021388065ddd"
                + "7f69a011dec106b539f7e9e00b5aff075688200e33f9c1018881ed";
            assertEquals(expected, Hex.toHexString(new PendingFederation(members(3, true)).serialize()));
        }

        @Test
        void pendingFederationHash_matchesRskj() {
            assertEquals(Hash.fromHexString("edcbd672c5ed65fd51152919f24ce0b8b95dac430da7c3d6f2f52dac524afb9b"), new PendingFederation(members(1, false)).getHash());
        }

        @Test
        void serializeUtxoList_matchesRskjBytes() {
            Script script = regtestFederation().getP2SHScript();
            List<UTXO> utxos = List.of(
                new UTXO(btcHash(1), 0, Coin.valueOf(100_000), 700_000, false, script),
                new UTXO(btcHash(2), 3, Coin.COIN.multiply(3), 700_001, true, script),
                new UTXO(btcHash(3), 1, Coin.valueOf(546), 0, false, script));
            String expected = "f8eab84ca08601000000000017000000a91447be763c5ae7fc219ce5c560966fa8ee3e1f2af0874bf5122f344554c53b"
                + "de2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a0000000060ae0a0000b84c00a3e1110000000017000000a914"
                + "47be763c5ae7fc219ce5c560966fa8ee3e1f2af087dbc1b4c900ffe48d575b5da5c638040125f65db0fe3e24494b76ea"
                + "986457d9860300000061ae0a0001b84c220200000000000017000000a91447be763c5ae7fc219ce5c560966fa8ee3e1f"
                + "2af087084fed08b978af4d7d196a7446a86b58009e636b611db16211b65a9aadff29c5010000000000000000";
            assertEquals(expected, Hex.toHexString(BridgeSerializationUtils.serializeUTXOList(utxos)));
        }

        @Test
        void stateForFederator_matchesRskjBytes() {
            SortedMap<Hash, BtcTransaction> waiting = new TreeMap<>(HashOrdering.RSK);
            for (int i = 1; i <= 3; i++) {
                waiting.put(rskTxHash(i), regtestBtcTx(i));
            }
            String expected = "f901cbf901c8a069c322e3248a5dfc29d73c5b0553b0185a35cd5bb6386747517ef7e53b15e287b87501000000013470"
                + "a33a66db68fd4f33af2430cd995d40d36b4c77033d713ee485a75db0118f0300000000ffffffff02f049020000000000"
                + "1976a914b3e6e01241eeb31e17c4f37482f653546964011c88ace80300000000000017a91447be763c5ae7fc219ce5c5"
                + "60966fa8ee3e1f2af08700000000a05fe7f977e71dba2ea1a68e21057beebb9be2ac30c6410aa38d4f3fbe41dcffd2b8"
                + "7501000000010886f56fa0d8da3abcf545d67a306e0647ea80c32e749272d5e18d9be8d10f2f0100000000ffffffff02"
                + "50c30000000000001976a9147184c9c226be8b92253f539f0b5c0c4016ad9eb088ace80300000000000017a91447be76"
                + "3c5ae7fc219ce5c560966fa8ee3e1f2af08700000000a0f2ee15ea639b73fa3db9b34a245bdfa015c260c598b211bf05"
                + "a1ecc4b3e3b4f2b8750100000001ba3978da88efb0ae898ac8fbad4851d223c60ce6aca9d7903565f27c54c4b77c0200"
                + "000000ffffffff02a0860100000000001976a914c08db4e44baf301d7dc425d58e7b3a8e24a78a3b88ace80300000000"
                + "000017a91447be763c5ae7fc219ce5c560966fa8ee3e1f2af08700000000";
            assertEquals(expected, Hex.toHexString(new StateForFederator(waiting).encodeToRlp()));
        }

        @Test
        void stateForProposedFederator_matchesRskjBytes() {
            String expected = "f87af878a00000000000000000000000000000000000000000000000000000000000000000b8550100000001fd614e15"
                + "7cfb70c3b1581311480885d80c5b916f550ef673b1739e0295fcad770700000000ffffffff01401f0000000000001976"
                + "a91434f2bb023e4cba82a291d1e33380f0545131396488ac00000000";
            assertEquals(expected, Hex.toHexString(new StateForProposedFederator(new AbstractMap.SimpleEntry<>(Hash.ZERO, mainnetBtcTx(7))).encodeToRlp()));
        }

        @Test
        void serializeReleaseRequestQueueWithTxHash_matchesRskjBytes() {
            ReleaseRequestQueue queue = new ReleaseRequestQueue(List.of(
                new ReleaseRequestQueue.Entry(publicKey(6001).toAddress(MAINNET), Coin.ZERO),
                new ReleaseRequestQueue.Entry(new Address(MAINNET, new byte[20]), Coin.valueOf(1)),
                new ReleaseRequestQueue.Entry(publicKey(6002).toAddress(MAINNET), Coin.ZERO, Hash.ZERO),
                new ReleaseRequestQueue.Entry(publicKey(6003).toAddress(MAINNET), Coin.valueOf(Long.MAX_VALUE), rskTxHash(3))));
            String expected = "f8769406ec48d570ac8348c49ddd54f7aee452fb92291180a00000000000000000000000000000000000000000000000"
                + "000000000000000000947d9cb6046fd1a266ad381bb71c7bfd24d2233dbd887fffffffffffffffa069c322e3248a5dfc"
                + "29d73c5b0553b0185a35cd5bb6386747517ef7e53b15e287";
            assertEquals(expected, Hex.toHexString(BridgeSerializationUtils.serializeReleaseRequestQueueWithTxHash(queue)));
        }

        @Test
        void serializePegoutsWaitingForConfirmationsWithTxHash_matchesRskjBytes() {
            Set<PegoutsWaitingForConfirmations.Entry> entries = new HashSet<>();
            entries.add(new PegoutsWaitingForConfirmations.Entry(mainnetBtcTx(1), 0L));
            entries.add(new PegoutsWaitingForConfirmations.Entry(mainnetBtcTx(2), 4_294_967_296L));
            entries.add(new PegoutsWaitingForConfirmations.Entry(mainnetBtcTx(3), 0L, Hash.ZERO));
            entries.add(new PegoutsWaitingForConfirmations.Entry(mainnetBtcTx(4), Long.MAX_VALUE, rskTxHash(4)));
            String expected = "f8fab85501000000013470a33a66db68fd4f33af2430cd995d40d36b4c77033d713ee485a75db0118f0300000000ffff"
                + "ffff01a00f0000000000001976a914b3e6e01241eeb31e17c4f37482f653546964011c88ac0000000080a00000000000"
                + "000000000000000000000000000000000000000000000000000000b85501000000014fb764bef114fe141040e96b9085"
                + "11d6c1763c199076be486c2480efdda12b450400000000ffffffff0188130000000000001976a914dc70c1ebc1a9c8c1"
                + "47581699877b41e8c431aa2588ac00000000887fffffffffffffffa0f343681465b9efe82c933c3e8748c70cb8aa0653"
                + "9c361de20f72eac04e766393";
            assertEquals(expected, Hex.toHexString(BridgeSerializationUtils.serializePegoutsWaitingForConfirmationsWithTxHash(new PegoutsWaitingForConfirmations(entries))));
        }

        @Test
        void smallEncodings_matchRskjBytes() throws Exception {
            assertEquals("81ff", Hex.toHexString(BridgeSerializationUtils.serializeLong(-1)));
            assertEquals("8480000000", Hex.toHexString(BridgeSerializationUtils.serializeLong(2147483648L)));
            assertEquals("847fffffff", Hex.toHexString(BridgeSerializationUtils.serializeInteger(Integer.MAX_VALUE)));
            assertEquals("81ff", Hex.toHexString(BridgeSerializationUtils.serializeCoin(Coin.valueOf(-1))));
            assertEquals("a00000000000000000000000000000000000000000000000000000000000000000", Hex.toHexString(BridgeSerializationUtils.serializeSha256Hash(Sha256Hash.ZERO_HASH)));
            assertEquals("c180", Hex.toHexString(BridgeSerializationUtils.serializeScript(new Script(new byte[0]))));
            assertEquals("e1a00000000000000000000000000000000000000000000000000000000000000000", Hex.toHexString(BridgeSerializationUtils.serializeCoinbaseInformation(new CoinbaseInformation(Sha256Hash.ZERO_HASH))));
            assertEquals("c1c0", Hex.toHexString(new StateForFederator(new TreeMap<>(HashOrdering.RSK)).encodeToRlp()));
            assertEquals("cb8081c081c081c081c08180", Hex.toHexString(new BridgeState(0, 0L, new ArrayList<>(), new TreeMap<>(HashOrdering.RSK),
                new ReleaseRequestQueue(new ArrayList<>()), new PegoutsWaitingForConfirmations(new HashSet<>())).getEncoded()));
        }
    }
}
