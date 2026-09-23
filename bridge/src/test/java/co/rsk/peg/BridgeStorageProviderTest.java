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
import static co.rsk.peg.BridgeStorageIndexKey.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import co.rsk.RskTestUtils;
import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.crypto.TransactionSignature;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.bitcoin.CoinbaseInformation;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.host.BridgeHost;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.utils.StorageKeys;
import org.hyperledger.besu.datatypes.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Ported from RSKj's BridgeStorageProviderTest over the in-memory host. RSKj's Repository became the host: what
 * was read through {@code repository.getStorageBytes(bridgeAddress, key)} is read through {@code host.getStorage(key)},
 * and a mocked host stands in for a mocked repository. The activation variants, the storage formats that predate
 * an activation, and the flyover and whitelist features are gone.
 *
 * Created by ajlopez on 6/7/2016.
 */
class BridgeStorageProviderTest {

    private static final NetworkParameters testnetBtcParams = NetworkParameters.fromID(NetworkParameters.ID_TESTNET);
    private static final NetworkParameters mainnetBtcParams = NetworkParameters.fromID(NetworkParameters.ID_MAINNET);
    private static final NetworkParameters regtestBtcParams = new BridgeRegTestConstants().getBtcParams();

    private int transactionOffset;

    @Test
    void createInstance() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();

        BridgeStorageProvider bridgeStorageProvider = createBridgeStorageProvider(host, testnetBtcParams);

        ReleaseRequestQueue releaseRequestQueue = bridgeStorageProvider.getReleaseRequestQueue();

        assertNotNull(releaseRequestQueue);
        assertEquals(0, releaseRequestQueue.getEntries().size());

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = bridgeStorageProvider.getPegoutsWaitingForConfirmations();

        assertNotNull(pegoutsWaitingForConfirmations);
        assertEquals(0, pegoutsWaitingForConfirmations.getEntries().size());

        SortedMap<Hash, BtcTransaction> signatures = bridgeStorageProvider.getPegoutsWaitingForSignatures();

        assertNotNull(signatures);
        assertTrue(signatures.isEmpty());
    }

    @Test
    void createSaveAndRecreateInstanceWithProcessedHashes() {
        Sha256Hash hash1 = BitcoinTestUtils.createHash(1);
        Sha256Hash hash2 = BitcoinTestUtils.createHash(2);

        InMemoryBridgeHost host = new InMemoryBridgeHost();

        BridgeStorageProvider provider0 = createBridgeStorageProvider(host, testnetBtcParams);
        provider0.setHeightBtcTxhashAlreadyProcessed(hash1, 1L);
        provider0.setHeightBtcTxhashAlreadyProcessed(hash2, 1L);
        provider0.save();

        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        assertTrue(provider.getHeightIfBtcTxhashIsAlreadyProcessed(hash1).isPresent());
        assertTrue(provider.getHeightIfBtcTxhashIsAlreadyProcessed(hash2).isPresent());
    }

    @Test
    void createSaveAndRecreateInstanceWithTxsWaitingForSignatures() {
        BtcTransaction tx1 = createTransaction();
        BtcTransaction tx2 = createTransaction();
        BtcTransaction tx3 = createTransaction();
        Hash hash1 = PegTestUtils.createHash3(1);
        Hash hash2 = PegTestUtils.createHash3(2);
        Hash hash3 = PegTestUtils.createHash3(3);

        InMemoryBridgeHost host = new InMemoryBridgeHost();

        BridgeStorageProvider provider0 = createBridgeStorageProvider(host, testnetBtcParams);
        provider0.getPegoutsWaitingForSignatures().put(hash1, tx1);
        provider0.getPegoutsWaitingForSignatures().put(hash2, tx2);
        provider0.getPegoutsWaitingForSignatures().put(hash3, tx3);

        provider0.save();

        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        SortedMap<Hash, BtcTransaction> signatures = provider.getPegoutsWaitingForSignatures();

        assertNotNull(signatures);

        assertTrue(signatures.containsKey(hash1));
        assertTrue(signatures.containsKey(hash2));
        assertTrue(signatures.containsKey(hash3));

        assertEquals(tx1.getHash(), signatures.get(hash1).getHash());
        assertEquals(tx2.getHash(), signatures.get(hash2).getHash());
        assertEquals(tx3.getHash(), signatures.get(hash3).getHash());
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("save, set and get svp fund transaction hash unsigned tests")
    class SvpFundTxHashUnsignedTests {
        private final Sha256Hash svpFundTxHash = BitcoinTestUtils.createHash(123_456_789);
        private InMemoryBridgeHost host;
        private BridgeStorageProvider bridgeStorageProvider;

        @BeforeEach
        void setup() {
            host = new InMemoryBridgeHost();
            bridgeStorageProvider = createBridgeStorageProvider(host, mainnetBtcParams);
        }

        @Test
        void saveSvpFundTxHashUnsigned_shouldSaveInStorage() {
            // Act
            bridgeStorageProvider.setSvpFundTxHashUnsigned(svpFundTxHash);
            bridgeStorageProvider.save();

            // Assert
            byte[] svpFundTxHashSerialized = BridgeSerializationUtils.serializeSha256Hash(svpFundTxHash);
            byte[] actualSvpFundTxHashSerialized = host.getStorage(SVP_FUND_TX_HASH_UNSIGNED.getKey());
            assertArrayEquals(svpFundTxHashSerialized, actualSvpFundTxHashSerialized);
        }

        @Test
        void saveSvpFundTxHashUnsigned_whenResettingToNull_shouldSaveNullInStorage() {
            // Initially setting a valid hash in storage
            bridgeStorageProvider.setSvpFundTxHashUnsigned(svpFundTxHash);
            bridgeStorageProvider.save();

            // Act
            bridgeStorageProvider.clearSvpFundTxHashUnsigned();
            bridgeStorageProvider.save();

            // Assert
            byte[] actualSvpFundTxHashSerialized = host.getStorage(SVP_FUND_TX_HASH_UNSIGNED.getKey());
            assertNull(actualSvpFundTxHashSerialized);
        }

        @Test
        void getSvpFundTxHashUnsigned_whenThereIsNoSvpFundTxHashUnsignedSavedNorSet_shouldReturnEmpty() {
            Optional<Sha256Hash> svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();
            assertEquals(Optional.empty(), svpFundTxHashUnsigned);
        }

        @Test
        void getSvpFundTxHashUnsigned_whenHashSetButNotSavedToStorage_shouldReturnTheHash() {
            // Arrange
            bridgeStorageProvider.setSvpFundTxHashUnsigned(svpFundTxHash);

            // Act
            Optional<Sha256Hash> svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();

            // Assert
            assertTrue(svpFundTxHashUnsigned.isPresent());
            assertEquals(svpFundTxHash, svpFundTxHashUnsigned.get());
        }

        @Test
        void getSvpFundTxHashUnsigned_whenDifferentHashIsInStorageAndAnotherIsSetButNotSaved_shouldReturnTheSetHash() {
            // Arrange
            Sha256Hash anotherSvpFundTxHash = BitcoinTestUtils.createHash(987_654_321);
            host.putStorage(SVP_FUND_TX_HASH_UNSIGNED.getKey(), BridgeSerializationUtils.serializeSha256Hash(anotherSvpFundTxHash));
            bridgeStorageProvider.setSvpFundTxHashUnsigned(svpFundTxHash);

            // Act
            Optional<Sha256Hash> svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();

            // Assert
            assertTrue(svpFundTxHashUnsigned.isPresent());
            assertEquals(svpFundTxHash, svpFundTxHashUnsigned.get());
        }

        @Test
        void getSvpFundTxHashUnsigned_whenStorageIsNotEmptyAndHashSetToNullButNotSaved_shouldReturnEmpty() {
            // Arrange
            host.putStorage(SVP_FUND_TX_HASH_UNSIGNED.getKey(), BridgeSerializationUtils.serializeSha256Hash(svpFundTxHash));
            bridgeStorageProvider.clearSvpFundTxHashUnsigned();

            // Act
            Optional<Sha256Hash> svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();

            // Assert
            assertEquals(Optional.empty(), svpFundTxHashUnsigned);
        }

        @Test
        void getSvpFundTxHashUnsigned_whenHashSetAndSaved_shouldReturnTheHash() {
            // Arrange
            bridgeStorageProvider.setSvpFundTxHashUnsigned(svpFundTxHash);
            bridgeStorageProvider.save();

            // Act
            Optional<Sha256Hash> svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();

            // Assert
            assertTrue(svpFundTxHashUnsigned.isPresent());
            assertEquals(svpFundTxHash, svpFundTxHashUnsigned.get());
        }

        @Test
        void getSvpFundTxHashUnsigned_whenHashDirectlySavedInStorage_shouldReturnTheHash() {
            // Arrange
            host.putStorage(SVP_FUND_TX_HASH_UNSIGNED.getKey(), BridgeSerializationUtils.serializeSha256Hash(svpFundTxHash));

            // Act
            Optional<Sha256Hash> svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();

            // Assert
            assertTrue(svpFundTxHashUnsigned.isPresent());
            assertEquals(svpFundTxHash, svpFundTxHashUnsigned.get());
        }

        @Test
        void getSvpFundTxHashUnsigned_whenSetToNull_shouldReturnEmpty() {
            // Arrange
            bridgeStorageProvider.clearSvpFundTxHashUnsigned();

            // Act
            Optional<Sha256Hash> svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();

            // Assert
            assertEquals(Optional.empty(), svpFundTxHashUnsigned);
        }

        @Test
        void getSvpFundTxHashUnsigned_whenHashIsNullInStorage_shouldReturnEmpty() {
            // Arrange
            host.putStorage(SVP_FUND_TX_HASH_UNSIGNED.getKey(), null);

            // Act
            Optional<Sha256Hash> svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();

            // Assert
            assertEquals(Optional.empty(), svpFundTxHashUnsigned);
        }

        @Test
        void getSvpFundTxHashUnsigned_whenNullHashIsSetAndSaved_shouldReturnEmpty() {
            // Arrange
            bridgeStorageProvider.clearSvpFundTxHashUnsigned();
            bridgeStorageProvider.save();

            // Act
            Optional<Sha256Hash> svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();

            // Assert
            assertEquals(Optional.empty(), svpFundTxHashUnsigned);
        }

        @Test
        void getSvpFundTxHashUnsigned_whenHashIsCached_shouldReturnTheCachedHash() {
            // Arrange
            // Manually saving a hash in storage to then cache it
            host.putStorage(SVP_FUND_TX_HASH_UNSIGNED.getKey(), BridgeSerializationUtils.serializeSha256Hash(svpFundTxHash));

            // Calling method, so it retrieves the hash from storage and caches it
            bridgeStorageProvider.getSvpFundTxHashUnsigned();

            // Setting a different hash in storage to make sure that when calling the method again it returns the cached one, not this one
            Sha256Hash anotherSvpFundTxHash = BitcoinTestUtils.createHash(987_654_321);
            host.putStorage(SVP_FUND_TX_HASH_UNSIGNED.getKey(), BridgeSerializationUtils.serializeSha256Hash(anotherSvpFundTxHash));

            // Act
            Optional<Sha256Hash> svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();

            // Assert
            assertTrue(svpFundTxHashUnsigned.isPresent());
            assertEquals(svpFundTxHash, svpFundTxHashUnsigned.get());
        }

        @Test
        void clearSvpFundTxHashUnsigned() {
            // Arrange
            bridgeStorageProvider.setSvpFundTxHashUnsigned(svpFundTxHash);

            // Ensure it is set
            Optional<Sha256Hash> svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();
            assertTrue(svpFundTxHashUnsigned.isPresent());
            assertEquals(svpFundTxHash, svpFundTxHashUnsigned.get());

            // Act
            bridgeStorageProvider.clearSvpFundTxHashUnsigned();

            // Assert
            svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();
            assertTrue(svpFundTxHashUnsigned.isEmpty());
        }

        @Test
        void clearSvpFundTxHashUnsigned_whenHashIsCached_shouldClearTheCachedHash() {
            // Arrange
            // Manually saving a hash in storage to then cache it
            host.putStorage(
                SVP_FUND_TX_HASH_UNSIGNED.getKey(),
                BridgeSerializationUtils.serializeSha256Hash(svpFundTxHash)
            );

            // Calling method, so it retrieves the hash from storage and caches it
            Optional<Sha256Hash> svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();
            assertTrue(svpFundTxHashUnsigned.isPresent());
            assertEquals(svpFundTxHash, svpFundTxHashUnsigned.get());

            // Act
            bridgeStorageProvider.clearSvpFundTxHashUnsigned();

            // Assert
            svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();
            assertTrue(svpFundTxHashUnsigned.isEmpty());
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("save, set and get svp fund transaction signed tests")
    class SvpFundTxSignedTests {
        private final BtcTransaction svpFundTx = new BtcTransaction(mainnetBtcParams);
        private final BtcTransaction anotherSvpFundTx = new BtcTransaction(mainnetBtcParams);
        private InMemoryBridgeHost host;
        private BridgeStorageProvider bridgeStorageProvider;

        @BeforeEach
        void setup() {
            host = new InMemoryBridgeHost();
            bridgeStorageProvider = createBridgeStorageProvider(host, mainnetBtcParams);

            BtcTransaction prevTx = new BtcTransaction(mainnetBtcParams);
            Address address = BitcoinTestUtils.createP2PKHAddress(mainnetBtcParams, "address");
            prevTx.addOutput(Coin.FIFTY_COINS, address);
            svpFundTx.addInput(prevTx.getOutput(0));
        }

        @Test
        void saveSvpFundTxSigned_shouldSaveInStorage() {
            // Act
            bridgeStorageProvider.setSvpFundTxSigned(svpFundTx);
            bridgeStorageProvider.save();

            // Assert
            byte[] svpFundTxSerialized = BridgeSerializationUtils.serializeBtcTransaction(svpFundTx);
            byte[] actualSvpFundTxSerialized = host.getStorage(SVP_FUND_TX_SIGNED.getKey());
            assertArrayEquals(svpFundTxSerialized, actualSvpFundTxSerialized);
        }

        @Test
        void saveSvpFundTxSigned_whenResettingToNull_shouldSaveNullInStorage() {
            // Initially setting a valid tx in storage
            bridgeStorageProvider.setSvpFundTxSigned(svpFundTx);
            bridgeStorageProvider.save();

            // Act
            bridgeStorageProvider.clearSvpFundTxSigned();
            bridgeStorageProvider.save();

            // Assert
            byte[] actualSvpFundTxSerialized = host.getStorage(SVP_FUND_TX_SIGNED.getKey());
            assertNull(actualSvpFundTxSerialized);
        }

        @Test
        void getSvpFundTxSigned_whenThereIsNosvpFundTxSignedSavedNorSet_shouldReturnEmpty() {
            Optional<BtcTransaction> svpFundTxSigned = bridgeStorageProvider.getSvpFundTxSigned();
            assertEquals(Optional.empty(), svpFundTxSigned);
        }

        @Test
        void getSvpFundTxSigned_whenHashSet_shouldReturnTheHash() {
            // Arrange
            bridgeStorageProvider.setSvpFundTxSigned(svpFundTx);

            // Act
            Optional<BtcTransaction> svpFundTxSigned = bridgeStorageProvider.getSvpFundTxSigned();

            // Assert
            assertTrue(svpFundTxSigned.isPresent());
            assertEquals(svpFundTx, svpFundTxSigned.get());
        }

        @Test
        void getSvpFundTxSigned_whenHashSetToNull_shouldReturnEmpty() {
            // Arrange
            bridgeStorageProvider.clearSvpFundTxSigned();

            // Act
            Optional<BtcTransaction> svpFundTxSigned = bridgeStorageProvider.getSvpFundTxSigned();

            // Assert
            assertEquals(Optional.empty(), svpFundTxSigned);
        }

        @Test
        void getSvpFundTxSigned_whenHashSavedAndHashSet_shouldReturnTheSetHash() {
            // Arrange
            host.putStorage(
                SVP_FUND_TX_SIGNED.getKey(),
                BridgeSerializationUtils.serializeBtcTransaction(svpFundTx)
            );
            bridgeStorageProvider.setSvpFundTxSigned(anotherSvpFundTx);

            // Act
            Optional<BtcTransaction> svpFundTxSigned = bridgeStorageProvider.getSvpFundTxSigned();

            // Assert
            assertTrue(svpFundTxSigned.isPresent());
            assertEquals(anotherSvpFundTx, svpFundTxSigned.get());
        }

        @Test
        void getSvpFundTxSigned_whenHashSavedAndHashSetToNull_shouldReturnEmpty() {
            // Arrange
            host.putStorage(
                SVP_FUND_TX_SIGNED.getKey(),
                BridgeSerializationUtils.serializeBtcTransaction(svpFundTx)
            );
            bridgeStorageProvider.clearSvpFundTxSigned();

            // Act
            Optional<BtcTransaction> svpFundTxSigned = bridgeStorageProvider.getSvpFundTxSigned();

            // Assert
            assertEquals(Optional.empty(), svpFundTxSigned);
        }

        @Test
        void getSvpFundTxSigned_whenHashSaved_shouldReturnTheHash() {
            // Arrange
            host.putStorage(
                SVP_FUND_TX_SIGNED.getKey(),
                BridgeSerializationUtils.serializeBtcTransaction(svpFundTx)
            );

            // Act
            Optional<BtcTransaction> svpFundTxSigned = bridgeStorageProvider.getSvpFundTxSigned();

            // Assert
            assertTrue(svpFundTxSigned.isPresent());
            assertEquals(svpFundTx, svpFundTxSigned.get());
        }

        @Test
        void getSvpFundTxSigned_whenNullHashSaved_shouldReturnEmpty() {
            // Arrange
            host.putStorage(
                SVP_FUND_TX_SIGNED.getKey(),
                null
            );

            // Act
            Optional<BtcTransaction> svpFundTxSigned = bridgeStorageProvider.getSvpFundTxSigned();

            // Assert
            assertEquals(Optional.empty(), svpFundTxSigned);
        }

        @Test
        void getSvpFundTxSigned_whenHashIsCached_shouldReturnTheCachedHash() {
            // Arrange
            // Manually saving a tx in storage to then cache it
            host.putStorage(
                SVP_FUND_TX_SIGNED.getKey(),
                BridgeSerializationUtils.serializeBtcTransaction(svpFundTx)
            );

            // Calling method, so it retrieves the tx from storage and caches it
            bridgeStorageProvider.getSvpFundTxSigned();

            // Setting a different tx in storage to make sure that when calling the method again it returns the cached one, not this one
            host.putStorage(
                SVP_FUND_TX_SIGNED.getKey(),
                BridgeSerializationUtils.serializeBtcTransaction(anotherSvpFundTx)
            );

            // Act
            Optional<BtcTransaction> svpFundTxSigned = bridgeStorageProvider.getSvpFundTxSigned();

            // Assert
            assertTrue(svpFundTxSigned.isPresent());
            assertEquals(svpFundTx, svpFundTxSigned.get());
        }

        @Test
        void getSvpFundTxSigned_whenNullHashIsCached_shouldReturnNewSavedHash() {
            // Arrange
            // Manually saving a null tx in storage to then cache it
            host.putStorage(
                SVP_FUND_TX_SIGNED.getKey(),
                null
            );

            // Calling method, so it retrieves the tx from storage and caches it
            bridgeStorageProvider.getSvpFundTxSigned();

            // Setting a tx in storage
            host.putStorage(
                SVP_FUND_TX_SIGNED.getKey(),
                BridgeSerializationUtils.serializeBtcTransaction(anotherSvpFundTx)
            );

            // Act
            Optional<BtcTransaction> svpFundTxSigned = bridgeStorageProvider.getSvpFundTxSigned();

            // Assert
            // since null tx was directly saved and not set, method returns new saved tx
            assertTrue(svpFundTxSigned.isPresent());
            assertEquals(anotherSvpFundTx, svpFundTxSigned.get());
        }

        @Test
        void clearSvpFundTxSigned() {
            // Arrange
            bridgeStorageProvider.setSvpFundTxSigned(svpFundTx);

            // Ensure it is set
            Optional<BtcTransaction> svpFundTxSigned = bridgeStorageProvider.getSvpFundTxSigned();
            assertTrue(svpFundTxSigned.isPresent());
            assertEquals(svpFundTx, svpFundTxSigned.get());

            // Act
            bridgeStorageProvider.clearSvpFundTxSigned();

            // Assert
            svpFundTxSigned = bridgeStorageProvider.getSvpFundTxSigned();
            assertTrue(svpFundTxSigned.isEmpty());
        }

        @Test
        void clearSvpFundTxSigned_whenHashIsCached_shouldClearTheCachedHash() {
            // Arrange
            // Manually saving a hash in storage to then cache it
            host.putStorage(
                SVP_FUND_TX_SIGNED.getKey(),
                BridgeSerializationUtils.serializeBtcTransaction(svpFundTx)
            );

            // Calling method, so it retrieves the hash from storage and caches it
            Optional<BtcTransaction> svpFundTxSigned = bridgeStorageProvider.getSvpFundTxSigned();
            assertTrue(svpFundTxSigned.isPresent());
            assertEquals(svpFundTx, svpFundTxSigned.get());

            // Act
            bridgeStorageProvider.clearSvpFundTxSigned();

            // Assert
            svpFundTxSigned = bridgeStorageProvider.getSvpFundTxSigned();
            assertTrue(svpFundTxSigned.isEmpty());
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("save, set and get svp spend transaction hash unsigned tests")
    class SvpSpendTxHashUnsignedTests {
        private final Sha256Hash svpSpendTxHash = BitcoinTestUtils.createHash(123_456_789);
        private InMemoryBridgeHost host;
        private BridgeStorageProvider bridgeStorageProvider;

        @BeforeEach
        void setup() {
            host = new InMemoryBridgeHost();
            bridgeStorageProvider = createBridgeStorageProvider(host, mainnetBtcParams);
        }

        @Test
        void saveSvpSpendTxHashUnsigned_shouldSaveInStorage() {
            // Act
            bridgeStorageProvider.setSvpSpendTxHashUnsigned(svpSpendTxHash);
            bridgeStorageProvider.save();

            // Assert
            byte[] svpSpendTxHashSerialized = BridgeSerializationUtils.serializeSha256Hash(svpSpendTxHash);
            byte[] actualSvpSpendTxHashSerialized = host.getStorage(SVP_SPEND_TX_HASH_UNSIGNED.getKey());
            assertArrayEquals(svpSpendTxHashSerialized, actualSvpSpendTxHashSerialized);
        }

        @Test
        void saveSvpSpendTxHashUnsigned_whenResettingToNull_shouldSaveNullInStorage() {
            // Initially setting a valid hash in storage
            bridgeStorageProvider.setSvpSpendTxHashUnsigned(svpSpendTxHash);
            bridgeStorageProvider.save();

            // Act
            bridgeStorageProvider.clearSvpSpendTxHashUnsigned();
            bridgeStorageProvider.save();

            // Assert
            byte[] actualSvpSpendTxHashSerialized = host.getStorage(SVP_SPEND_TX_HASH_UNSIGNED.getKey());
            assertNull(actualSvpSpendTxHashSerialized);
        }

        @Test
        void getSvpSpendTxHashUnsigned_whenThereIsNoSvpSpendTxHashUnsignedSavedNorSet_shouldReturnEmpty() {
            Optional<Sha256Hash> svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();
            assertEquals(Optional.empty(), svpSpendTxHashUnsigned);
        }

        @Test
        void getSvpSpendTxHashUnsigned_whenHashSetButNotSavedToStorage_shouldReturnTheHash() {
            // Arrange
            bridgeStorageProvider.setSvpSpendTxHashUnsigned(svpSpendTxHash);

            // Act
            Optional<Sha256Hash> svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();

            // Assert
            assertTrue(svpSpendTxHashUnsigned.isPresent());
            assertEquals(svpSpendTxHash, svpSpendTxHashUnsigned.get());
        }

        @Test
        void getSvpSpendTxHashUnsigned_whenDifferentHashIsInStorageAndAnotherIsSetButNotSaved_shouldReturnTheSetHash() {
            // Arrange
            Sha256Hash anotherSvpSpendTxHash = BitcoinTestUtils.createHash(987_654_321);
            host.putStorage(SVP_SPEND_TX_HASH_UNSIGNED.getKey(), BridgeSerializationUtils.serializeSha256Hash(anotherSvpSpendTxHash));
            bridgeStorageProvider.setSvpSpendTxHashUnsigned(svpSpendTxHash);

            // Act
            Optional<Sha256Hash> svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();

            // Assert
            assertTrue(svpSpendTxHashUnsigned.isPresent());
            assertEquals(svpSpendTxHash, svpSpendTxHashUnsigned.get());
        }

        @Test
        void getSvpFundTxHashUnsigned_whenStorageIsNotEmptyAndHashSetToNullButNotSaved_shouldReturnEmpty() {
            // Arrange
            host.putStorage(SVP_SPEND_TX_HASH_UNSIGNED.getKey(), BridgeSerializationUtils.serializeSha256Hash(svpSpendTxHash));
            bridgeStorageProvider.clearSvpSpendTxHashUnsigned();

            // Act
            Optional<Sha256Hash> svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();

            // Assert
            assertEquals(Optional.empty(), svpSpendTxHashUnsigned);
        }

        @Test
        void getSvpSpendTxHashUnsigned_whenHashSetAndSaved_shouldReturnTheHash() {
            // Arrange
            bridgeStorageProvider.setSvpSpendTxHashUnsigned(svpSpendTxHash);
            bridgeStorageProvider.save();

            // Act
            Optional<Sha256Hash> svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();

            // Assert
            assertTrue(svpSpendTxHashUnsigned.isPresent());
            assertEquals(svpSpendTxHash, svpSpendTxHashUnsigned.get());
        }

        @Test
        void getSvpSpendTxHashUnsigned_whenHashDirectlySavedInStorage_shouldReturnTheHash() {
            // Arrange
            host.putStorage(SVP_SPEND_TX_HASH_UNSIGNED.getKey(), BridgeSerializationUtils.serializeSha256Hash(svpSpendTxHash));

            // Act
            Optional<Sha256Hash> svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();

            // Assert
            assertTrue(svpSpendTxHashUnsigned.isPresent());
            assertEquals(svpSpendTxHash, svpSpendTxHashUnsigned.get());
        }

        @Test
        void getSvpSpendTxHashUnsigned_whenSetToNull_shouldReturnEmpty() {
            // Arrange
            bridgeStorageProvider.clearSvpSpendTxHashUnsigned();

            // Act
            Optional<Sha256Hash> svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();

            // Assert
            assertEquals(Optional.empty(), svpSpendTxHashUnsigned);
        }

        @Test
        void getSvpSpendTxHashUnsigned_whenHashIsNullInStorage_shouldReturnEmpty() {
            // Arrange
            host.putStorage(SVP_SPEND_TX_HASH_UNSIGNED.getKey(), null);

            // Act
            Optional<Sha256Hash> svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();

            // Assert
            assertEquals(Optional.empty(), svpSpendTxHashUnsigned);
        }

        @Test
        void getSvpSpendTxHashUnsigned_whenNullHashIsSetAndSaved_shouldReturnEmpty() {
            // Arrange
            bridgeStorageProvider.clearSvpSpendTxHashUnsigned();
            bridgeStorageProvider.save();

            // Act
            Optional<Sha256Hash> svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();

            // Assert
            assertEquals(Optional.empty(), svpSpendTxHashUnsigned);
        }

        @Test
        void getSvpSpendTxHashUnsigned_whenHashIsCached_shouldReturnTheCachedHash() {
            // Arrange
            // Manually saving a hash in storage to then cache it
            host.putStorage(
                SVP_SPEND_TX_HASH_UNSIGNED.getKey(),
                BridgeSerializationUtils.serializeSha256Hash(svpSpendTxHash)
            );

            // Calling method, so it retrieves the hash from storage and caches it
            bridgeStorageProvider.getSvpSpendTxHashUnsigned();

            // Setting a different hash in storage to make sure that when calling the method again it returns the cached one, not this one
            Sha256Hash anotherSvpSpendTxHash = BitcoinTestUtils.createHash(987_654_321);
            host.putStorage(SVP_SPEND_TX_HASH_UNSIGNED.getKey(), BridgeSerializationUtils.serializeSha256Hash(anotherSvpSpendTxHash));

            // Act
            Optional<Sha256Hash> svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();

            // Assert
            assertTrue(svpSpendTxHashUnsigned.isPresent());
            assertEquals(svpSpendTxHash, svpSpendTxHashUnsigned.get());
        }

        @Test
        void clearSvpSpendTxHashUnsigned() {
            // Arrange
            bridgeStorageProvider.setSvpSpendTxHashUnsigned(svpSpendTxHash);

            // Ensure it is set
            Optional<Sha256Hash> svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();
            assertTrue(svpSpendTxHashUnsigned.isPresent());
            assertEquals(svpSpendTxHash, svpSpendTxHashUnsigned.get());

            // Act
            bridgeStorageProvider.clearSvpSpendTxHashUnsigned();

            // Assert
            svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();
            assertTrue(svpSpendTxHashUnsigned.isEmpty());
        }

        @Test
        void clearSvpSpendTxHashUnsigned_whenHashIsCached_shouldClearTheCachedHash() {
            // Arrange
            // Manually saving a hash in storage to then cache it
            host.putStorage(
                SVP_SPEND_TX_HASH_UNSIGNED.getKey(),
                BridgeSerializationUtils.serializeSha256Hash(svpSpendTxHash)
            );

            // Calling method, so it retrieves the hash from storage and caches it
            Optional<Sha256Hash> svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();
            assertTrue(svpSpendTxHashUnsigned.isPresent());
            assertEquals(svpSpendTxHash, svpSpendTxHashUnsigned.get());

            // Act
            bridgeStorageProvider.clearSvpSpendTxHashUnsigned();

            // Assert
            svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();
            assertTrue(svpSpendTxHashUnsigned.isEmpty());
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("save, set and get svp spend transaction waiting for signatures tests")
    class SvpSpendTxWaitingForSignaturesTests {
        private static final Hash spendTxCreationHash = RskTestUtils.createHash(1);
        private static final BtcTransaction svpSpendTx = new BtcTransaction(mainnetBtcParams);
        private final Map.Entry<Hash, BtcTransaction> svpSpendTxWaitingForSignatures =
            new AbstractMap.SimpleEntry<>(spendTxCreationHash, svpSpendTx);
        private InMemoryBridgeHost host;
        private BridgeStorageProvider bridgeStorageProvider;

        @BeforeEach
        void setup() {
            host = new InMemoryBridgeHost();
            bridgeStorageProvider = createBridgeStorageProvider(host, mainnetBtcParams);
        }

        private static Stream<Arguments> invalidEntryArgs() {
            return Stream.of(
                Arguments.of(null, null),
                Arguments.of(spendTxCreationHash, null),
                Arguments.of(null, svpSpendTx)
            );
        }

        @ParameterizedTest
        @MethodSource("invalidEntryArgs")
        void setSvpSpendTxWaitingForSignatures_whenInvalidEntry_shouldThrowIllegalArgumentException(Hash spendTxCreationHash, BtcTransaction svpSpendTx) {
            // Arrange
            Map.Entry<Hash, BtcTransaction> invalidSvpSpendTxWaitingForSignatures =
                new AbstractMap.SimpleEntry<>(spendTxCreationHash, svpSpendTx);

            // Act
            assertThrows(
                IllegalArgumentException.class,
                () -> bridgeStorageProvider.setSvpSpendTxWaitingForSignatures(invalidSvpSpendTxWaitingForSignatures)
            );

            bridgeStorageProvider.save();

            // Assert
            byte[] actualSvpSpendTxWaitingForSignatures = host.getStorage(SVP_SPEND_TX_WAITING_FOR_SIGNATURES.getKey());
            assertNull(actualSvpSpendTxWaitingForSignatures);
        }

        @Test
        void saveSvpSpendTxWaitingForSignatures_shouldSaveInStorage() {
            // Arrange
            bridgeStorageProvider.setSvpSpendTxWaitingForSignatures(svpSpendTxWaitingForSignatures);

            // Act
            bridgeStorageProvider.save();

            // Assert
            byte[] svpSpendTxWaitingForSignaturesSerialized =
                BridgeSerializationUtils.serializeRskTxWaitingForSignatures(svpSpendTxWaitingForSignatures);
            byte[] actualSvpSpendTxWaitingForSignaturesSerialized =
                host.getStorage(SVP_SPEND_TX_WAITING_FOR_SIGNATURES.getKey());
            assertArrayEquals(svpSpendTxWaitingForSignaturesSerialized, actualSvpSpendTxWaitingForSignaturesSerialized);
        }

        @Test
        void saveSvpSpendTxWaitingForSignatures_whenResettingToNull_shouldSaveNullInStorage() {
            // Initially setting a valid entry in storage
            bridgeStorageProvider.setSvpSpendTxWaitingForSignatures(svpSpendTxWaitingForSignatures);
            bridgeStorageProvider.save();

            // Act
            bridgeStorageProvider.clearSvpSpendTxWaitingForSignatures();
            bridgeStorageProvider.save();

            // Assert
            byte[] actualSvpSpendTxWaitingForSignatures = host.getStorage(SVP_SPEND_TX_WAITING_FOR_SIGNATURES.getKey());
            assertNull(actualSvpSpendTxWaitingForSignatures);
        }

        @Test
        void getSvpSpendTxWaitingForSignatures_whenThereIsNoSvpSpendTxWaitingForSignaturesSaved_shouldReturnEmpty() {
            Optional<Map.Entry<Hash, BtcTransaction>> actualSvpSpendTxWaitingForSignatures =
                bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();
            assertEquals(Optional.empty(), actualSvpSpendTxWaitingForSignatures);
        }

        @Test
        void getSvpSpendTxWaitingForSignatures_whenEntrySetButNotSavedToStorage_shouldReturnTheSetEntry() {
            // Arrange
            bridgeStorageProvider.setSvpSpendTxWaitingForSignatures(svpSpendTxWaitingForSignatures);

            // Act
            Optional<Map.Entry<Hash, BtcTransaction>> actualSvpSpendTxWaitingForSignatures =
                bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();

            // Assert
            assertTrue(actualSvpSpendTxWaitingForSignatures.isPresent());
            assertEquals(svpSpendTxWaitingForSignatures, actualSvpSpendTxWaitingForSignatures.get());
        }

        @Test
        void getSvpSpendTxWaitingForSignatures_whenDifferentEntryIsInStorageAndAnotherIsSetButNotSaved_shouldReturnTheSetEntry() {
            // Arrange
            Hash anotherSvpSpendTxCreationHash = RskTestUtils.createHash(2);
            BtcTransaction anotherSvpSpendTx = new BtcTransaction(mainnetBtcParams);
            Map.Entry<Hash, BtcTransaction> anotherSvpSpendTxWaitingForSignatures =
              new AbstractMap.SimpleEntry<>(anotherSvpSpendTxCreationHash, anotherSvpSpendTx);
            host.putStorage(
                SVP_SPEND_TX_WAITING_FOR_SIGNATURES.getKey(),
                BridgeSerializationUtils.serializeRskTxWaitingForSignatures(anotherSvpSpendTxWaitingForSignatures));
            bridgeStorageProvider.setSvpSpendTxWaitingForSignatures(svpSpendTxWaitingForSignatures);

            // Act
            Optional<Map.Entry<Hash, BtcTransaction>> actualSvpSpendTxWaitingForSignatures =
                bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();

            // Assert
            assertTrue(actualSvpSpendTxWaitingForSignatures.isPresent());
            assertEquals(svpSpendTxWaitingForSignatures, actualSvpSpendTxWaitingForSignatures.get());
        }

        @Test
        void getSvpSpendTxWaitingForSignatures_whenDifferentEntryIsInStorageAndEntrySetToNullButNotSaved_shouldReturnEmpty() {
            // Arrange
            host.putStorage(
                SVP_SPEND_TX_WAITING_FOR_SIGNATURES.getKey(),
                BridgeSerializationUtils.serializeRskTxWaitingForSignatures(svpSpendTxWaitingForSignatures));
            bridgeStorageProvider.clearSvpSpendTxWaitingForSignatures();

            // Act
            Optional<Map.Entry<Hash, BtcTransaction>> actualSvpSpendTxWaitingForSignatures =
                bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();

            // Assert
            assertEquals(Optional.empty(), actualSvpSpendTxWaitingForSignatures);
        }

        @Test
        void getSvpSpendTxWaitingForSignatures_whenEntrySetAndSaved_shouldReturnTheEntry() {
            // Arrange
            bridgeStorageProvider.setSvpSpendTxWaitingForSignatures(svpSpendTxWaitingForSignatures);
            bridgeStorageProvider.save();

            // Act
            Optional<Map.Entry<Hash, BtcTransaction>> actualSvpSpendTxWaitingForSignatures =
                bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();

            // Assert
            assertTrue(actualSvpSpendTxWaitingForSignatures.isPresent());
            assertEquals(svpSpendTxWaitingForSignatures, actualSvpSpendTxWaitingForSignatures.get());
        }

        @Test
        void getSvpSpendTxWaitingForSignatures_whenEntryDirectlySavedInStorage_shouldReturnTheEntry() {
            // Arrange
            host.putStorage(
                SVP_SPEND_TX_WAITING_FOR_SIGNATURES.getKey(),
                BridgeSerializationUtils.serializeRskTxWaitingForSignatures(svpSpendTxWaitingForSignatures));

            // Act
            Optional<Map.Entry<Hash, BtcTransaction>> actualSvpSpendTxWaitingForSignatures =
                bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();

            // Assert
            assertTrue(actualSvpSpendTxWaitingForSignatures.isPresent());
            assertEquals(svpSpendTxWaitingForSignatures, actualSvpSpendTxWaitingForSignatures.get());
        }

        @Test
        void getSvpSpendTxWaitingForSignatures_whenSetToNull_shouldReturnEmpty() {
            // Arrange
            bridgeStorageProvider.clearSvpSpendTxWaitingForSignatures();

            // Act
            Optional<Map.Entry<Hash, BtcTransaction>> actualSvpSpendTxWaitingForSignatures =
                bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();

            // Assert
            assertEquals(Optional.empty(), actualSvpSpendTxWaitingForSignatures);
        }

        @Test
        void getSvpSpendTxWaitingForSignatures_whenNullEntryIsSetAndSaved_shouldReturnEmpty() {
            // Arrange
            bridgeStorageProvider.clearSvpSpendTxWaitingForSignatures();
            bridgeStorageProvider.save();

            // Act
            Optional<Map.Entry<Hash, BtcTransaction>> actualSvpSpendTxWaitingForSignatures =
                bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();

            // Assert
            assertEquals(Optional.empty(), actualSvpSpendTxWaitingForSignatures);
        }

        @Test
        void getSvpSpendTxWaitingForSignatures_whenEntryIsNullInStorage_shouldReturnEmpty() {
            // Arrange
            host.putStorage(SVP_SPEND_TX_WAITING_FOR_SIGNATURES.getKey(), null);

            // Act
            Optional<Map.Entry<Hash, BtcTransaction>> actualSvpSpendTxWaitingForSignatures =
                bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();

            // Assert
            assertEquals(Optional.empty(), actualSvpSpendTxWaitingForSignatures);
        }

        @Test
        void getSvpSpendTxWaitingForSignatures_whenEntryIsCached_shouldReturnTheCachedEntry() {
            // Arrange
            // Manually saving a entry in storage to then cache it
            host.putStorage(
                SVP_SPEND_TX_WAITING_FOR_SIGNATURES.getKey(),
                BridgeSerializationUtils.serializeRskTxWaitingForSignatures(svpSpendTxWaitingForSignatures));

            // Calling method, so it retrieves the entry from storage and caches it
            bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();

            // Setting a different entry in storage to make sure that when calling
            // the method again it returns the cached one, not this one
            Hash anotherSvpSpendTxCreationHash = RskTestUtils.createHash(2);
            BtcTransaction anotherSvpSpendTx = new BtcTransaction(mainnetBtcParams);
            Map.Entry<Hash, BtcTransaction> anotherSvpSpendTxWaitingForSignatures =
              new AbstractMap.SimpleEntry<>(anotherSvpSpendTxCreationHash, anotherSvpSpendTx);
            host.putStorage(
                SVP_SPEND_TX_WAITING_FOR_SIGNATURES.getKey(),
                BridgeSerializationUtils.serializeRskTxWaitingForSignatures(anotherSvpSpendTxWaitingForSignatures)
            );

            // Act
            Optional<Map.Entry<Hash, BtcTransaction>> actualSvpSpendTxWaitingForSignatures =
                bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();

            // Assert
            assertTrue(actualSvpSpendTxWaitingForSignatures.isPresent());
            assertEquals(svpSpendTxWaitingForSignatures, actualSvpSpendTxWaitingForSignatures.get());
        }

        @Test
        void clearSvpSpendTxWaitingForSignatures() {
            // Arrange
            bridgeStorageProvider.setSvpSpendTxWaitingForSignatures(svpSpendTxWaitingForSignatures);

            // Ensure it is set
            Optional<Map.Entry<Hash, BtcTransaction>> actualSvpSpendTxWaitingForSignatures = bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();
            assertTrue(actualSvpSpendTxWaitingForSignatures.isPresent());
            assertEquals(svpSpendTxWaitingForSignatures, actualSvpSpendTxWaitingForSignatures.get());

            // Act
            bridgeStorageProvider.clearSvpSpendTxWaitingForSignatures();

            // Assert
            actualSvpSpendTxWaitingForSignatures = bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();
            assertTrue(actualSvpSpendTxWaitingForSignatures.isEmpty());
        }

        @Test
        void clearSvpSpendTxWaitingForSignatures_whenValueIsCached_shouldClearTheCachedValue() {
            // Arrange
            // Manually saving a value in storage to then cache it
            host.putStorage(
                SVP_SPEND_TX_WAITING_FOR_SIGNATURES.getKey(),
                BridgeSerializationUtils.serializeRskTxWaitingForSignatures(svpSpendTxWaitingForSignatures)
            );

            // Calling method, so it retrieves the value from storage and caches it
            Optional<Map.Entry<Hash, BtcTransaction>> actualSvpSpendTxWaitingForSignatures = bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();
            assertTrue(actualSvpSpendTxWaitingForSignatures.isPresent());
            assertEquals(svpSpendTxWaitingForSignatures, actualSvpSpendTxWaitingForSignatures.get());

            // Act
            bridgeStorageProvider.clearSvpSpendTxWaitingForSignatures();

            // Assert
            actualSvpSpendTxWaitingForSignatures = bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();
            assertTrue(actualSvpSpendTxWaitingForSignatures.isEmpty());
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("clear svp values tests")
    class ClearSvpValuesTests {
        private BridgeStorageProvider bridgeStorageProvider;

        @BeforeEach
        void setup() {
            InMemoryBridgeHost host = new InMemoryBridgeHost();
            bridgeStorageProvider = createBridgeStorageProvider(host, mainnetBtcParams);
        }

        @Test
        void clearSvpValues_whenFundTxHashUnsigned_shouldClearValue() {
            // arrange
            Sha256Hash svpFundTxHashUnsigned = BitcoinTestUtils.createHash(1);
            bridgeStorageProvider.setSvpFundTxHashUnsigned(svpFundTxHashUnsigned);

            // act
            bridgeStorageProvider.clearSvpValues();

            // assert
            assertNoSVPValues();
        }

        @Test
        void clearSvpValues_whenFundTxSigned_shouldClearValue() {
            // arrange
            BtcTransaction svpFundTxSigned = new BtcTransaction(mainnetBtcParams);
            bridgeStorageProvider.setSvpFundTxSigned(svpFundTxSigned);

            // act
            bridgeStorageProvider.clearSvpValues();

            // assert
            assertNoSVPValues();
        }

        @Test
        void clearSvpValues_whenSpendTxWFS_shouldClearSpendTxValues() {
            // arrange
            Hash svpSpendTxCreationHash = RskTestUtils.createHash(1);
            BtcTransaction svpSpendTx = new BtcTransaction(mainnetBtcParams);
            Map.Entry<Hash, BtcTransaction> svpSpendTxWFS = new AbstractMap.SimpleEntry<>(svpSpendTxCreationHash, svpSpendTx);
            bridgeStorageProvider.setSvpSpendTxWaitingForSignatures(svpSpendTxWFS);

            // act
            bridgeStorageProvider.clearSvpValues();

            // assert
            assertNoSVPValues();
        }

        @Test
        void clearSvpValues_whenSpendTxHashUnsigned_shouldClearValue() {
            // arrange
            Sha256Hash svpSpendTxCreationHash = BitcoinTestUtils.createHash(1);
            bridgeStorageProvider.setSvpSpendTxHashUnsigned(svpSpendTxCreationHash);

            // act
            bridgeStorageProvider.clearSvpValues();

            // assert
            assertNoSVPValues();
        }

        private void assertNoSVPValues() {
            assertFalse(bridgeStorageProvider.getSvpFundTxHashUnsigned().isPresent());
            assertFalse(bridgeStorageProvider.getSvpFundTxSigned().isPresent());
            assertFalse(bridgeStorageProvider.getSvpSpendTxWaitingForSignatures().isPresent());
            assertFalse(bridgeStorageProvider.getSvpSpendTxHashUnsigned().isPresent());
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("save, set and get releases outpoints tests")
    class ReleasesOutpointsValues {
        private static final Sha256Hash releaseTxHash1 = BitcoinTestUtils.createHash(1);
        private static final List<Coin> outpointsValues1 = Arrays.asList(
            Coin.valueOf(12345), Coin.SATOSHI, Coin.COIN
        );
        private static final Sha256Hash releaseTxHash2 = BitcoinTestUtils.createHash(2);
        private static final List<Coin> outpointsValues2 = Arrays.asList(
            Coin.valueOf(123456), Coin.COIN, Coin.SATOSHI
        );

        private BridgeStorageProvider bridgeStorageProvider;
        private InMemoryBridgeHost host;

        @BeforeEach
        void setup() {
            host = new InMemoryBridgeHost();
            bridgeStorageProvider = createBridgeStorageProvider(host, mainnetBtcParams);
        }

        @Test
        void outpointsValues_immutable() {
            // arrange
            List<Coin> outpointsValues = new ArrayList<>(
                Arrays.asList(Coin.MILLICOIN, Coin.SATOSHI, Coin.COIN)
            );
            bridgeStorageProvider.setReleaseOutpointsValues(releaseTxHash1, outpointsValues);
            Optional<List<Coin>> actualReleaseOutpointsValuesBeforeModifyingOpt = bridgeStorageProvider.getReleaseOutpointsValues(releaseTxHash1);
            assertTrue(actualReleaseOutpointsValuesBeforeModifyingOpt.isPresent());
            List<Coin> actualReleaseOutpointsValuesBeforeModifying = List.copyOf(actualReleaseOutpointsValuesBeforeModifyingOpt.get());

            // Act
            outpointsValues.add(Coin.FIFTY_COINS);

            // assert
            Optional<List<Coin>> actualReleaseOutpointsValues = bridgeStorageProvider.getReleaseOutpointsValues(releaseTxHash1);
            assertTrue(actualReleaseOutpointsValues.isPresent());
            assertEquals(actualReleaseOutpointsValuesBeforeModifying, actualReleaseOutpointsValues.get());
        }

        private static Stream<Arguments> invalidReleaseOutpointsEntryArgs() {
            return Stream.of(
                Arguments.of(null, null),
                Arguments.of(releaseTxHash1, null),
                Arguments.of(releaseTxHash1, new ArrayList<>()),
                Arguments.of(null, outpointsValues1)
            );
        }

        @ParameterizedTest
        @MethodSource("invalidReleaseOutpointsEntryArgs")
        void setAndSaveReleaseOutpointsValues_invalidEntry_shouldThrowIllegalArgumentExceptionAndNotSaveInStorage(
            Sha256Hash releaseTxHash,
            List<Coin> outpointsValues
        ) {
            // Act & assert
            assertThrows(
                IllegalArgumentException.class,
                () -> bridgeStorageProvider.setReleaseOutpointsValues(releaseTxHash, outpointsValues)
            );

            bridgeStorageProvider.save();
            byte[] actualReleaseOutpointsValues = host.getStorage(getStorageKeyForReleaseOutpointsValues(releaseTxHash1));
            assertNull(actualReleaseOutpointsValues);
        }

        @Test
        void setReleaseOutpointsValues_whenEntryAlreadySaved_shouldThrowIllegalArgumentException() {
            // arrange
            host.putStorage(
                getStorageKeyForReleaseOutpointsValues(releaseTxHash1),
                BridgeSerializationUtils.serializeOutpointsValues(outpointsValues1)
            );

            // Act & assert
            assertThrows(
                IllegalArgumentException.class,
                () -> bridgeStorageProvider.setReleaseOutpointsValues(releaseTxHash1, outpointsValues1)
            );
        }

        @Test
        void setAndSaveReleaseOutpointsValues_forTwoDifferentEntries_shouldSaveBothInStorage() {
            // Arrange
            bridgeStorageProvider.setReleaseOutpointsValues(releaseTxHash1, outpointsValues1);
            bridgeStorageProvider.setReleaseOutpointsValues(releaseTxHash2, outpointsValues2);

            // Act
            bridgeStorageProvider.save();

            // Assert
            byte[] actualReleaseOutpointsValues1 = host.getStorage(getStorageKeyForReleaseOutpointsValues(releaseTxHash1));
            assertNotNull(actualReleaseOutpointsValues1);
            assertEquals(outpointsValues1, deserializeOutpointsValues(actualReleaseOutpointsValues1));

            byte[] actualReleaseOutpointsValues2 = host.getStorage(getStorageKeyForReleaseOutpointsValues(releaseTxHash2));
            assertNotNull(actualReleaseOutpointsValues2);
            assertEquals(outpointsValues2, deserializeOutpointsValues(actualReleaseOutpointsValues2));
        }

        @Test
        void setAndSaveReleaseOutpointsValues_forNewEntry_whenAnotherEntryIsInStorage_shouldHaveBothInStorage() {
            // Arrange
            // save entry in storage
            host.putStorage(
                getStorageKeyForReleaseOutpointsValues(releaseTxHash1),
                BridgeSerializationUtils.serializeOutpointsValues(outpointsValues1)
            );

            // Act
            // add new entry
            bridgeStorageProvider.setReleaseOutpointsValues(releaseTxHash2, outpointsValues2);
            bridgeStorageProvider.save();

            // Assert
            // both entries are saved
            byte[] actualReleaseOutpointsValues1 = host.getStorage(getStorageKeyForReleaseOutpointsValues(releaseTxHash1));
            assertNotNull(actualReleaseOutpointsValues1);

            byte[] actualReleaseOutpointsValues2 = host.getStorage(getStorageKeyForReleaseOutpointsValues(releaseTxHash2));
            assertNotNull(actualReleaseOutpointsValues2);
        }

        private Bytes32 getStorageKeyForReleaseOutpointsValues(Sha256Hash releaseTxHash) {
            return RELEASES_OUTPOINTS_VALUES.getCompoundKey("-", releaseTxHash.toString());
        }

        @Test
        void getReleaseOutpointsValues_whenEntryNotSetNorSaved_shouldReturnEmpty() {
            Optional<List<Coin>> actualReleaseOutpointsValues = bridgeStorageProvider.getReleaseOutpointsValues(releaseTxHash1);
            assertTrue(actualReleaseOutpointsValues.isEmpty());
        }

        @Test
        void getReleaseOutpointsValues_whenEntrySetButNotSaved_shouldReturnValues() {
            // Arrange
            bridgeStorageProvider.setReleaseOutpointsValues(releaseTxHash1, outpointsValues1);
            bridgeStorageProvider.setReleaseOutpointsValues(releaseTxHash2, outpointsValues2);

            // Act
            Optional<List<Coin>> actualReleaseOutpointsValues1 = bridgeStorageProvider.getReleaseOutpointsValues(releaseTxHash1);
            Optional<List<Coin>> actualReleaseOutpointsValues2 = bridgeStorageProvider.getReleaseOutpointsValues(releaseTxHash2);

            // Assert
            assertTrue(actualReleaseOutpointsValues1.isPresent());
            assertEquals(outpointsValues1, actualReleaseOutpointsValues1.get());

            assertTrue(actualReleaseOutpointsValues2.isPresent());
            assertEquals(outpointsValues2, actualReleaseOutpointsValues2.get());
        }

        @Test
        void getReleaseOutpointsValues_whenEntrySetAndSaved_shouldReturnValues() {
            // Arrange
            bridgeStorageProvider.setReleaseOutpointsValues(releaseTxHash1, outpointsValues1);
            bridgeStorageProvider.setReleaseOutpointsValues(releaseTxHash2, outpointsValues2);
            bridgeStorageProvider.save();

            // Act
            Optional<List<Coin>> actualReleaseOutpointsValues1 = bridgeStorageProvider.getReleaseOutpointsValues(releaseTxHash1);
            Optional<List<Coin>> actualReleaseOutpointsValues2 = bridgeStorageProvider.getReleaseOutpointsValues(releaseTxHash2);

            // Assert
            assertTrue(actualReleaseOutpointsValues1.isPresent());
            assertEquals(outpointsValues1, actualReleaseOutpointsValues1.get());

            assertTrue(actualReleaseOutpointsValues2.isPresent());
            assertEquals(outpointsValues2, actualReleaseOutpointsValues2.get());
        }

        @Test
        void getReleaseOutpointsValues_whenEntriesSavedInStorage_shouldReturnValues() {
            // Arrange
            host.putStorage(
                getStorageKeyForReleaseOutpointsValues(releaseTxHash1),
                BridgeSerializationUtils.serializeOutpointsValues(outpointsValues1)
            );
            host.putStorage(
                getStorageKeyForReleaseOutpointsValues(releaseTxHash2),
                BridgeSerializationUtils.serializeOutpointsValues(outpointsValues2)
            );

            // Act
            Optional<List<Coin>> savedReleaseOutpointsValues1 = bridgeStorageProvider.getReleaseOutpointsValues(releaseTxHash1);
            assertTrue(savedReleaseOutpointsValues1.isPresent());
            assertEquals(outpointsValues1, savedReleaseOutpointsValues1.get());

            Optional<List<Coin>> savedReleaseOutpointsValues2 = bridgeStorageProvider.getReleaseOutpointsValues(releaseTxHash2);
            assertTrue(savedReleaseOutpointsValues2.isPresent());
            assertEquals(outpointsValues2, savedReleaseOutpointsValues2.get());
        }
    }

    @Test
    void getReleaseRequestQueue() {
        // Every entry on this chain carries its RSK transaction hash, so the stored entry has one too
        ReleaseRequestQueue.Entry oldEntry = new ReleaseRequestQueue.Entry(
            Address.fromBase58(regtestBtcParams, "mmWJhA74Pd6peL39V3AmtGHdGdJ4PyeXvL"),
            Coin.COIN,
            PegTestUtils.createHash3(1)
        );

        ReleaseRequestQueue.Entry newEntry = new ReleaseRequestQueue.Entry(
            Address.fromBase58(regtestBtcParams, "mseEsMLuzaEdGbyAv9c9VRL9qGcb49qnxB"),
            Coin.COIN,
            PegTestUtils.createHash3(0)
        );

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        host.putStorage(
            RELEASE_REQUEST_QUEUE_WITH_TXHASH.getKey(),
            BridgeSerializationUtils.serializeReleaseRequestQueueWithTxHash(new ReleaseRequestQueue(new ArrayList<>(Collections.singletonList(oldEntry))))
        );

        BridgeStorageProvider storageProvider = createBridgeStorageProvider(host, testnetBtcParams);

        ReleaseRequestQueue releaseRequestQueue = storageProvider.getReleaseRequestQueue();

        releaseRequestQueue.add(
            Address.fromBase58(regtestBtcParams, "mseEsMLuzaEdGbyAv9c9VRL9qGcb49qnxB"),
            Coin.COIN,
            PegTestUtils.createHash3(0)
        );

        ReleaseRequestQueue result = storageProvider.getReleaseRequestQueue();

        assertEquals(2, result.getEntries().size());
        assertEquals(result.getEntries().get(0), oldEntry);
        assertEquals(result.getEntries().get(1), newEntry);
    }

    @Test
    void saveReleaseRequestQueue() {
        ReleaseRequestQueue.Entry newEntry = new ReleaseRequestQueue.Entry(
            Address.fromBase58(regtestBtcParams, "mseEsMLuzaEdGbyAv9c9VRL9qGcb49qnxB"),
            Coin.COIN,
            PegTestUtils.createHash3(0)
        );

        ReleaseRequestQueue.Entry oldEntry = new ReleaseRequestQueue.Entry(
            Address.fromBase58(regtestBtcParams, "mseEsMLuzaEdGbyAv9c9VRL9qGcb49qnxB"),
            Coin.COIN,
            PegTestUtils.createHash3(1)
        );

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        host.putStorage(
            RELEASE_REQUEST_QUEUE_WITH_TXHASH.getKey(),
            BridgeSerializationUtils.serializeReleaseRequestQueueWithTxHash(new ReleaseRequestQueue(new ArrayList<>(Collections.singletonList(oldEntry))))
        );

        BridgeStorageProvider storageProvider = createBridgeStorageProvider(host, testnetBtcParams);
        ReleaseRequestQueue releaseRequestQueue = storageProvider.getReleaseRequestQueue();

        releaseRequestQueue.add(Address.fromBase58(regtestBtcParams, "mseEsMLuzaEdGbyAv9c9VRL9qGcb49qnxB"),
            Coin.COIN,
            PegTestUtils.createHash3(0)
        );

        storageProvider.saveReleaseRequestQueue();

        List<ReleaseRequestQueue.Entry> entries = BridgeSerializationUtils.deserializeReleaseRequestQueue(
            host.getStorage(RELEASE_REQUEST_QUEUE_WITH_TXHASH.getKey()),
            testnetBtcParams,
            true
        );
        assertEquals(Arrays.asList(oldEntry, newEntry), entries);
        assertEquals(2, storageProvider.getReleaseRequestQueue().getEntries().size());
        // The queue without transaction hashes has no key on this chain
        assertEquals(1, host.storedEntries());
    }

    @Test
    void getPegoutsWaitingForConfirmations() {
        // Every entry on this chain carries its RSK transaction hash, so the stored entry has one too
        Set<PegoutsWaitingForConfirmations.Entry> oldEntriesSet = new HashSet<>(Collections.singletonList(
            new PegoutsWaitingForConfirmations.Entry(createTransaction(), 1L, PegTestUtils.createHash3(1))
        ));

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        host.putStorage(
            PEGOUTS_WAITING_FOR_CONFIRMATIONS_WITH_TXHASH_KEY.getKey(),
            BridgeSerializationUtils.serializePegoutsWaitingForConfirmationsWithTxHash(new PegoutsWaitingForConfirmations(oldEntriesSet))
        );

        BridgeStorageProvider storageProvider = createBridgeStorageProvider(host, testnetBtcParams);

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = storageProvider.getPegoutsWaitingForConfirmations();

        pegoutsWaitingForConfirmations.add(
            createTransaction(),
            1L,
            PegTestUtils.createHash3(0)
        );

        PegoutsWaitingForConfirmations result = storageProvider.getPegoutsWaitingForConfirmations();

        assertEquals(2, result.getEntries().size());
        assertTrue(result.getEntries().containsAll(oldEntriesSet));
    }

    @Test
    void savePegoutsWaitingForConfirmations() {
        PegoutsWaitingForConfirmations.Entry oldEntry = new PegoutsWaitingForConfirmations.Entry(createTransaction(), 1L, PegTestUtils.createHash3(1));
        PegoutsWaitingForConfirmations.Entry newEntry = new PegoutsWaitingForConfirmations.Entry(createTransaction(), 1L, PegTestUtils.createHash3(0));

        InMemoryBridgeHost host = new InMemoryBridgeHost();
        host.putStorage(
            PEGOUTS_WAITING_FOR_CONFIRMATIONS_WITH_TXHASH_KEY.getKey(),
            BridgeSerializationUtils.serializePegoutsWaitingForConfirmationsWithTxHash(new PegoutsWaitingForConfirmations(new HashSet<>(Collections.singletonList(oldEntry))))
        );

        BridgeStorageProvider storageProvider = createBridgeStorageProvider(host, testnetBtcParams);
        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = storageProvider.getPegoutsWaitingForConfirmations();

        pegoutsWaitingForConfirmations.add(
            newEntry.getBtcTransaction(),
            newEntry.getPegoutCreationRskBlockNumber(),
            newEntry.getPegoutCreationRskTxHash()
        );

        storageProvider.savePegoutsWaitingForConfirmations();

        Set<PegoutsWaitingForConfirmations.Entry> entries = BridgeSerializationUtils.deserializePegoutsWaitingForConfirmations(
            host.getStorage(PEGOUTS_WAITING_FOR_CONFIRMATIONS_WITH_TXHASH_KEY.getKey()),
            testnetBtcParams,
            true
        ).getEntries();
        assertEquals(new HashSet<>(Arrays.asList(oldEntry, newEntry)), entries);
        assertEquals(2, storageProvider.getPegoutsWaitingForConfirmations().getEntries().size());
        // The set without transaction hashes has no key on this chain
        assertEquals(1, host.storedEntries());
    }

    @Test
    void getReleaseTransaction() {
        BtcTransaction tx1 = createTransaction();
        BtcTransaction tx2 = createTransaction();
        BtcTransaction tx3 = createTransaction();

        InMemoryBridgeHost host = new InMemoryBridgeHost();

        BridgeStorageProvider provider0 = createBridgeStorageProvider(host, testnetBtcParams);

        provider0.getPegoutsWaitingForConfirmations().add(tx1, 1L, PegTestUtils.createHash3(0));
        provider0.getPegoutsWaitingForConfirmations().add(tx2, 2L, PegTestUtils.createHash3(1));
        provider0.getPegoutsWaitingForConfirmations().add(tx3, 3L, PegTestUtils.createHash3(2));

        provider0.save();

        //Reusing same storage configuration as the height doesn't affect storage configurations for releases.
        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        assertEquals(3, provider.getPegoutsWaitingForConfirmations().getEntries().size());
        assertEquals(0, provider.getPegoutsWaitingForSignatures().size());
    }

    @Test
    void getHeightIfBtcTxhashIsAlreadyProcessed_readsStorageOnce() {
        BridgeHost host = mock(BridgeHost.class);

        Sha256Hash hash = Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000001");

        when(host.getStorage(
            BTC_TX_HASH_AP.getCompoundKey("-", hash.toString())
        )).thenReturn(BridgeSerializationUtils.serializeLong(2L));

        BridgeStorageProvider provider0 = createBridgeStorageProvider(host, testnetBtcParams);

        // Get hash which is stored in its own key
        Optional<Long> result = provider0.getHeightIfBtcTxhashIsAlreadyProcessed(hash);
        assertTrue(result.isPresent());
        assertEquals(Long.valueOf(2), result.get());

        verify(host, times(1)).getStorage(BTC_TX_HASH_AP.getCompoundKey("-", hash.toString()));

        // Get hash again
        result = provider0.getHeightIfBtcTxhashIsAlreadyProcessed(hash);
        assertTrue(result.isPresent());
        assertEquals(Long.valueOf(2), result.get());

        // No more accesses to storage, as the value is in cache
        verify(host, times(1)).getStorage(BTC_TX_HASH_AP.getCompoundKey("-", hash.toString()));
    }

    @Test
    void setHeightBtcTxhashAlreadyProcessed_doesNotReadStorage() {
        BridgeHost host = mock(BridgeHost.class);

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        BridgeStorageProvider provider0 = createBridgeStorageProvider(host, testnetBtcParams);

        provider0.setHeightBtcTxhashAlreadyProcessed(hash, 1L);

        // The storage is never accessed as the new storage keeps the values in cache until save
        verify(host, never()).getStorage(any());

        Optional<Long> result = provider0.getHeightIfBtcTxhashIsAlreadyProcessed(hash);
        assertTrue(result.isPresent());
        assertEquals(Long.valueOf(1), result.get());
    }

    @Test
    void saveHeightBtcTxHashAlreadyProcessed() {
        BridgeHost host = mock(BridgeHost.class);

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        BridgeStorageProvider provider0 = createBridgeStorageProvider(host, testnetBtcParams);

        provider0.setHeightBtcTxhashAlreadyProcessed(hash, 1L);

        provider0.saveHeightBtcTxHashAlreadyProcessed();

        // The storage is never read as the new storage keeps the values in cache until save
        verify(host, never()).getStorage(any());
        verify(host, times(1)).putStorage(
            BTC_TX_HASH_AP.getCompoundKey("-", hash.toString()),
            BridgeSerializationUtils.serializeLong(1L)
        );

        Optional<Long> result = provider0.getHeightIfBtcTxhashIsAlreadyProcessed(hash);
        assertTrue(result.isPresent());
        assertEquals(Long.valueOf(1), result.get());
    }

    @Test
    void getCoinBaseInformation() {
        BridgeHost host = mock(BridgeHost.class);

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(Sha256Hash.ZERO_HASH);
        when(host.getStorage(StorageKeys.compound("coinbaseInformation-" + hash)))
            .thenReturn(BridgeSerializationUtils.serializeCoinbaseInformation(coinbaseInformation));

        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        CoinbaseInformation result = provider.getCoinbaseInformation(hash);
        assertEquals(coinbaseInformation.getWitnessMerkleRoot(),result.getWitnessMerkleRoot());
    }

    @Test
    void setCoinBaseInformation() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        assertNull(provider.getCoinbaseInformation(hash));

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(Sha256Hash.ZERO_HASH);
        provider.setCoinbaseInformation(hash, coinbaseInformation);

        assertEquals(coinbaseInformation, provider.getCoinbaseInformation(hash));
    }

    @Test
    void saveCoinBaseInformation() {
        BridgeHost host = mock(BridgeHost.class);

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        assertNull(provider.getCoinbaseInformation(hash));

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(Sha256Hash.ZERO_HASH);
        provider.setCoinbaseInformation(hash, coinbaseInformation);

        assertEquals(coinbaseInformation, provider.getCoinbaseInformation(hash));

        provider.save();

        verify(host, times(1)).putStorage(
            StorageKeys.compound("coinbaseInformation-" + hash),
            BridgeSerializationUtils.serializeCoinbaseInformation(coinbaseInformation)
        );
    }

    @Test
    void getBtcBestBlockHashByHeight_hashNotFound() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        int blockHeight = 100;

        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        Optional<Sha256Hash> hashOptional = provider.getBtcBestBlockHashByHeight(blockHeight);

        assertFalse(hashOptional.isPresent());
    }

    @Test
    void getBtcBestBlockHashByHeight() {
        Sha256Hash blockHash = BitcoinTestUtils.createHash(2);
        byte[] serializedHash = BridgeSerializationUtils.serializeSha256Hash(blockHash);

        BridgeHost host = mock(BridgeHost.class);
        when(host.getStorage(any())).thenReturn(serializedHash);

        int blockHeight = 100;
        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        Optional<Sha256Hash> hashOptional = provider.getBtcBestBlockHashByHeight(blockHeight);

        assertTrue(hashOptional.isPresent());
        assertEquals(blockHash, hashOptional.get());
    }

    @Test
    void saveBtcBlocksIndex() {
        int blockHeight = 100;
        Bytes32 storageKey = StorageKeys.compound("btcBlockHeight-" + blockHeight);

        Sha256Hash blockHash = BitcoinTestUtils.createHash(2);
        byte[] serializedHash = BridgeSerializationUtils.serializeSha256Hash(blockHash);

        BridgeHost host = mock(BridgeHost.class);

        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        provider.setBtcBestBlockHashByHeight(blockHeight, blockHash);
        provider.save();

        verify(host, times(1)).putStorage(
            storageKey,
            serializedHash
        );
    }

    @Test
    void getReceiveHeadersLastTimestamp() {
        BridgeHost host = mock(BridgeHost.class);

        long actualTimeStamp = System.currentTimeMillis();
        byte[] encodedTimeStamp = BridgeSerializationUtils.serializeLong(actualTimeStamp);
        when(host.getStorage(RECEIVE_HEADERS_TIMESTAMP.getKey()))
            .thenReturn(encodedTimeStamp);

        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        Optional<Long> result = provider.getReceiveHeadersLastTimestamp();

        assertTrue(result.isPresent());
        assertEquals(actualTimeStamp, (long) result.get());
    }

    @Test
    void getReceiveHeadersLastTimestamp_not_in_repository() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();

        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        assertFalse(provider.getReceiveHeadersLastTimestamp().isPresent());
    }

    @Test
    void saveReceiveHeadersLastTimestamp() {
        BridgeHost host = mock(BridgeHost.class);

        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        long timeInMillis = System.currentTimeMillis();
        provider.setReceiveHeadersLastTimestamp(timeInMillis);

        provider.save();
        verify(host, times(1)).putStorage(
            RECEIVE_HEADERS_TIMESTAMP.getKey(),
            BridgeSerializationUtils.serializeLong(timeInMillis)
        );
    }

    @Test
    void saveReceiveHeadersLastTimestamp_not_set() {
        BridgeHost host = mock(BridgeHost.class);

        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        provider.save();
        verify(host, never()).putStorage(
            eq(RECEIVE_HEADERS_TIMESTAMP.getKey()),
            any(byte[].class)
        );
    }

    @Test
    void getNextPegoutHeight() {
        BridgeHost host = mock(BridgeHost.class);

        when(host.getStorage(NEXT_PEGOUT_HEIGHT_KEY.getKey())).thenReturn(new byte[] { 1 });

        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        assertEquals(Optional.of(1L), provider.getNextPegoutHeight());

        verify(host, atLeastOnce()).getStorage(NEXT_PEGOUT_HEIGHT_KEY.getKey());
    }

    @Test
    void setNextPegoutHeightAndGetNextPegoutHeight() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();

        BridgeStorageProvider provider1 = createBridgeStorageProvider(host, testnetBtcParams);

        provider1.setNextPegoutHeight(1L);
        provider1.saveNextPegoutHeight();

        BridgeStorageProvider provider2 = createBridgeStorageProvider(host, testnetBtcParams);

        assertEquals(Optional.of(1L), provider2.getNextPegoutHeight());
    }

    @Test
    void saveNextPegoutHeight() {
        BridgeHost host = mock(BridgeHost.class);

        BridgeStorageProvider provider = createBridgeStorageProvider(host, testnetBtcParams);

        provider.setNextPegoutHeight(10L);
        provider.saveNextPegoutHeight();

        verify(host, times(1)).putStorage(
            NEXT_PEGOUT_HEIGHT_KEY.getKey(),
            BridgeSerializationUtils.serializeLong(10L)
        );
    }

    @Test
    void getReleaseRequestQueueSize_when_releaseRequestQueue_is_null() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();

        BridgeStorageProvider storageProvider = createBridgeStorageProvider(host, testnetBtcParams);

        assertEquals(0, storageProvider.getReleaseRequestQueueSize());
    }

    @Test
    void getReleaseRequestQueueSize_when_releaseRequestQueue_is_not_null() {
        InMemoryBridgeHost host = new InMemoryBridgeHost();

        BridgeStorageProvider storageProvider = createBridgeStorageProvider(host, testnetBtcParams);

        ReleaseRequestQueue releaseRequestQueue = storageProvider.getReleaseRequestQueue();

        releaseRequestQueue.add(Address.fromBase58(regtestBtcParams, "mseEsMLuzaEdGbyAv9c9VRL9qGcb49qnxB"),
            Coin.COIN,
            PegTestUtils.createHash3(0));

        releaseRequestQueue.add(Address.fromBase58(regtestBtcParams, "mmWJhA74Pd6peL39V3AmtGHdGdJ4PyeXvL"),
            Coin.COIN,
            PegTestUtils.createHash3(1));

        assertEquals(2, storageProvider.getReleaseRequestQueueSize());
    }

    private BtcTransaction createTransaction() {
        BtcTransaction tx = new BtcTransaction(testnetBtcParams);
        tx.addInput(
            BitcoinTestUtils.createHash(1),
            transactionOffset++,
            ScriptBuilder.createInputScript(new TransactionSignature(BigInteger.ONE, BigInteger.TEN))
        );

        return tx;
    }

    private static BridgeStorageProvider createBridgeStorageProvider(BridgeHost host, NetworkParameters networkParameters) {
        return new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), networkParameters);
    }
}
