/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
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

import static co.rsk.peg.BridgeStorageIndexKey.RELEASE_REQUEST_QUEUE_WITH_TXHASH;
import static co.rsk.peg.BridgeSupport.BTC_TRANSACTION_CONFIRMATION_INCONSISTENT_BLOCK_ERROR_CODE;
import static co.rsk.peg.BridgeSupportTestUtil.*;
import static co.rsk.peg.PegTestUtils.createUTXO;
import static co.rsk.peg.bitcoin.BitcoinTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.bitcoinj.store.BlockStoreException;
import co.rsk.bitcoinj.wallet.Wallet;
import co.rsk.peg.bitcoin.*;
import co.rsk.peg.btcLockSender.BtcLockSender;
import co.rsk.peg.btcLockSender.BtcLockSender.TxSenderAddressType;
import co.rsk.peg.btcLockSender.BtcLockSenderProvider;
import co.rsk.peg.constants.*;
import co.rsk.peg.federation.*;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.feeperkb.*;
import co.rsk.peg.lockingcap.*;
import co.rsk.peg.lockingcap.constants.LockingCapConstants;
import co.rsk.peg.lockingcap.constants.LockingCapMainNetConstants;
import co.rsk.peg.pegin.RejectedPeginReason;
import co.rsk.peg.pegininstructions.*;
import co.rsk.peg.host.BridgeHost;
import co.rsk.peg.host.CallContext;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.storage.*;
import co.rsk.peg.utils.*;
import co.rsk.peg.exception.VMException;
import co.rsk.peg.vote.ABICallSpec;
import co.rsk.test.builders.BridgeSupportBuilder;
import co.rsk.test.builders.FederationSupportBuilder;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import org.bouncycastle.util.encoders.Hex;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class BridgeSupportTest {
    private static final BtcECKey senderKey = BitcoinTestUtils.getBtcEcKeyFromSeed("sender");
    private static final Wei LIMIT_MONETARY_BASE = Wei.of(new BigInteger("21000000000000000000000000"));

    private static Federation activeFederation = P2shErpFederationBuilder.builder().build();

    private final BridgeConstants bridgeConstantsRegtest = new BridgeRegTestConstants();
    private final NetworkParameters btcRegTestParams = bridgeConstantsRegtest.getBtcParams();
    private final FederationConstants federationConstantsRegtest = bridgeConstantsRegtest.getFederationConstants();

    private final BridgeConstants bridgeMainNetConstants = BridgeMainNetConstants.getInstance();
    private final NetworkParameters btcMainnetParams = bridgeMainNetConstants.getBtcParams();
    private final FederationConstants federationConstantsMainnet = bridgeMainNetConstants.getFederationConstants();
    private final LockingCapConstants lockingCapMainnetConstants = bridgeMainNetConstants.getLockingCapConstants();

    private final BridgeSupportBuilder bridgeSupportBuilder = BridgeSupportBuilder.builder();
    private final FederationSupportBuilder federationSupportBuilder = FederationSupportBuilder.builder();
    // Any height works: the pegout tx index is always consulted.
    private final int pegoutTxIndexActivationHeight = 4_320;

    private InMemoryBridgeHost host;
    private CallContext tx;
    private BridgeStorageProvider bridgeStorageProvider;
    private LockingCapSupport lockingCapSupport;
    private StorageAccessor bridgeStorageAccessor;
    private FederationStorageProvider federationStorageProvider;
    private FederationSupport federationSupport;
    private FeePerKbSupport feePerKbSupport;
    private PartialMerkleTree pmtWithTransactions;
    private BtcBlockStoreWithCache.Factory btcBlockStoreFactory;
    private BridgeSupport bridgeSupport;

    @BeforeEach
    void setUpOnEachTest() {
        host = new InMemoryBridgeHost();
        tx = PegTestUtils.callFrom(PublicKeys.addressOf(senderKey), PegTestUtils.createHash3(99));

        bridgeStorageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
        federationStorageProvider = new FederationStorageProviderImpl(bridgeStorageAccessor);
        LockingCapStorageProvider lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);

        feePerKbSupport = mock(FeePerKbSupport.class);
        Coin feePerKb = Coin.valueOf(1000L);
        when(feePerKbSupport.getFeePerKb()).thenReturn(feePerKb);
        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsMainnet)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();
        lockingCapSupport = new LockingCapSupportImpl(
            lockingCapStorageProvider,
            lockingCapMainnetConstants
        );

        bridgeStorageProvider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), btcMainnetParams);
        host.balance(BridgeAddresses.BRIDGE, LIMIT_MONETARY_BASE);
    }

    @Test
    void getFeePerKb() {
        Coin feePerKb = Coin.valueOf(10_000L);
        when(feePerKbSupport.getFeePerKb()).thenReturn(feePerKb);

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        Coin result = bridgeSupport.getFeePerKb();

        assertEquals(feePerKb, result);
    }

    @Test
    void voteFeePerKbChange_success() {
        when(feePerKbSupport.voteFeePerKbChange(any(), any())).thenReturn(1);

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        CallContext tx = PegTestUtils.callWithHash(Hash.ZERO);
        Coin feePerKbVote = Coin.CENT;
        int result = bridgeSupport.voteFeePerKbChange(tx, feePerKbVote);

        assertEquals(FeePerKbResponseCode.SUCCESSFUL_VOTE.getCode(), result);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("federation support tests")
    class FederationSupportTests {
        FederationSupport federationSupport;
        BridgeConstants bridgeMainnetConstants = BridgeMainNetConstants.getInstance();
        BridgeSupportBuilder bridgeSupportBuilder = BridgeSupportBuilder.builder();
        BridgeSupport bridgeSupport;

        Federation federation = P2shErpFederationBuilder.builder().build();

        @BeforeEach
        void setUp() {
            federationSupport = mock(FederationSupportImpl.class);

            bridgeSupport = bridgeSupportBuilder
                .withBridgeConstants(bridgeMainnetConstants)
                .withFederationSupport(federationSupport)
                .build();
        }

        @Test
        void getActiveFederation() {
            when(federationSupport.getActiveFederation()).thenReturn(federation);
            assertEquals(federation, bridgeSupport.getActiveFederation());
        }

        @Test
        void getActiveFederationRedeemScript() {
            Optional<Script> redeemScript = Optional.ofNullable(federation.getRedeemScript());

            when(federationSupport.getActiveFederationRedeemScript()).thenReturn(redeemScript);
            assertEquals(redeemScript, bridgeSupport.getActiveFederationRedeemScript());
        }

        @Test
        void getActiveFederationAddress() {
            Address address = federation.getAddress();

            when(federationSupport.getActiveFederationAddress()).thenReturn(address);
            assertEquals(address, bridgeSupport.getActiveFederationAddress());
        }

        @Test
        void getActiveFederationSize() {
            int size = federation.getSize();

            when(federationSupport.getActiveFederationSize()).thenReturn(size);
            assertEquals(size, bridgeSupport.getActiveFederationSize());
        }

        @Test
        void getActiveFederationThreshold() {
            int threshold = federation.getNumberOfSignaturesRequired();

            when(federationSupport.getActiveFederationThreshold()).thenReturn(threshold);
            assertEquals(threshold, bridgeSupport.getActiveFederationThreshold());
        }

        @Test
        void getActiveFederationCreationTime() {
            Instant creationTime = federation.getCreationTime();

            when(federationSupport.getActiveFederationCreationTime()).thenReturn(creationTime);
            assertEquals(creationTime, bridgeSupport.getActiveFederationCreationTime());
        }

        @Test
        void getActiveFederationCreationBlockNumber() {
            long creationBlockNumber = federation.getCreationBlockNumber();

            when(federationSupport.getActiveFederationCreationBlockNumber()).thenReturn(creationBlockNumber);
            assertEquals(creationBlockNumber, bridgeSupport.getActiveFederationCreationBlockNumber());
        }

        @Test
        void getActiveFederationCreationBlockHeight() {
            long creationBlockHeight = 100L;

            when(federationSupport.getActiveFederationCreationBlockHeight()).thenReturn(creationBlockHeight);
            assertEquals(creationBlockHeight, bridgeSupport.getActiveFederationCreationBlockHeight());
        }

        @Test
        void getActiveFederatorPublicKeyOfType() {
            FederationMember member = federation.getMembers().get(0);
            BtcECKey btcKey = member.getBtcPublicKey();
            BtcECKey rskKey = member.getRskPublicKey();
            BtcECKey mstKey = member.getMstPublicKey();

            when(federationSupport.getActiveFederatorPublicKeyOfType(0, FederationMember.KeyType.BTC)).thenReturn(btcKey.getPubKey());
            assertArrayEquals(btcKey.getPubKey(), bridgeSupport.getActiveFederatorPublicKeyOfType(0, FederationMember.KeyType.BTC));

            when(federationSupport.getActiveFederatorPublicKeyOfType(0, FederationMember.KeyType.RSK)).thenReturn(rskKey.getPubKey());
            assertArrayEquals(rskKey.getPubKey(), bridgeSupport.getActiveFederatorPublicKeyOfType(0, FederationMember.KeyType.RSK));

            when(federationSupport.getActiveFederatorPublicKeyOfType(0, FederationMember.KeyType.MST)).thenReturn(mstKey.getPubKey());
            assertArrayEquals(mstKey.getPubKey(), bridgeSupport.getActiveFederatorPublicKeyOfType(0, FederationMember.KeyType.MST));
        }

        @Test
        void getRetiringFederation() {
            when(federationSupport.getRetiringFederation()).thenReturn(federation);
            assertEquals(federation, bridgeSupport.getRetiringFederation());
        }

        @Test
        void getRetiringFederationAddress() {
            Address address = federation.getAddress();

            when(federationSupport.getRetiringFederationAddress()).thenReturn(address);
            assertEquals(address, bridgeSupport.getRetiringFederationAddress());
        }

        @Test
        void getRetiringFederationSize() {
            int size = federation.getSize();

            when(federationSupport.getRetiringFederationSize()).thenReturn(size);
            assertEquals(size, bridgeSupport.getRetiringFederationSize());
        }

        @Test
        void getRetiringFederationThreshold() {
            int threshold = federation.getNumberOfSignaturesRequired();

            when(federationSupport.getRetiringFederationThreshold()).thenReturn(threshold);
            assertEquals(threshold, bridgeSupport.getRetiringFederationThreshold());
        }

        @Test
        void getRetiringFederationCreationTime() {
            Instant creationTime = federation.getCreationTime();

            when(federationSupport.getRetiringFederationCreationTime()).thenReturn(creationTime);
            assertEquals(creationTime, bridgeSupport.getRetiringFederationCreationTime());
        }

        @Test
        void getRetiringFederationCreationBlockNumber() {
            long creationBlockNumber = federation.getCreationBlockNumber();

            when(federationSupport.getRetiringFederationCreationBlockNumber()).thenReturn(creationBlockNumber);
            assertEquals(creationBlockNumber, bridgeSupport.getRetiringFederationCreationBlockNumber());
        }

        @Test
        void getRetiringFederatorPublicKeyOfType() {
            FederationMember member = federation.getMembers().get(0);
            BtcECKey btcKey = member.getBtcPublicKey();
            BtcECKey rskKey = member.getRskPublicKey();
            BtcECKey mstKey = member.getMstPublicKey();

            when(federationSupport.getRetiringFederatorPublicKeyOfType(0, FederationMember.KeyType.BTC)).thenReturn(btcKey.getPubKey());
            assertArrayEquals(btcKey.getPubKey(), bridgeSupport.getRetiringFederatorPublicKeyOfType(0, FederationMember.KeyType.BTC));

            when(federationSupport.getRetiringFederatorPublicKeyOfType(0, FederationMember.KeyType.RSK)).thenReturn(rskKey.getPubKey());
            assertArrayEquals(rskKey.getPubKey(), bridgeSupport.getRetiringFederatorPublicKeyOfType(0, FederationMember.KeyType.RSK));

            when(federationSupport.getRetiringFederatorPublicKeyOfType(0, FederationMember.KeyType.MST)).thenReturn(mstKey.getPubKey());
            assertArrayEquals(mstKey.getPubKey(), bridgeSupport.getRetiringFederatorPublicKeyOfType(0, FederationMember.KeyType.MST));
        }

        @Test
        void getPendingFederationHash() {
            PendingFederation pendingFederation = PendingFederationBuilder.builder().build();
            Hash hash = pendingFederation.getHash();

            when(federationSupport.getPendingFederationHash()).thenReturn(hash);
            assertEquals(hash, bridgeSupport.getPendingFederationHash());
        }

        @Test
        void getPendingFederationSize() {
            int size = federation.getSize();

            when(federationSupport.getPendingFederationSize()).thenReturn(size);
            assertEquals(size, bridgeSupport.getPendingFederationSize());
        }

        @Test
        void getPendingFederatorPublicKeyOfType() {
            FederationMember member = federation.getMembers().get(0);
            BtcECKey btcKey = member.getBtcPublicKey();
            BtcECKey rskKey = member.getRskPublicKey();
            BtcECKey mstKey = member.getMstPublicKey();

            when(federationSupport.getPendingFederatorPublicKeyOfType(0, FederationMember.KeyType.BTC)).thenReturn(btcKey.getPubKey());
            assertArrayEquals(btcKey.getPubKey(), bridgeSupport.getPendingFederatorPublicKeyOfType(0, FederationMember.KeyType.BTC));

            when(federationSupport.getPendingFederatorPublicKeyOfType(0, FederationMember.KeyType.RSK)).thenReturn(rskKey.getPubKey());
            assertArrayEquals(rskKey.getPubKey(), bridgeSupport.getPendingFederatorPublicKeyOfType(0, FederationMember.KeyType.RSK));

            when(federationSupport.getPendingFederatorPublicKeyOfType(0, FederationMember.KeyType.MST)).thenReturn(mstKey.getPubKey());
            assertArrayEquals(mstKey.getPubKey(), bridgeSupport.getPendingFederatorPublicKeyOfType(0, FederationMember.KeyType.MST));
        }

        @Test
        void getProposedFederation_whenFederationSupportReturnsProposedFederation_shouldReturnProposedFed() {
            when(federationSupport.getProposedFederation()).thenReturn(Optional.of(federation));

            Optional<Federation> proposedFed = bridgeSupport.getProposedFederation();
            assertTrue(proposedFed.isPresent());
            assertEquals(federation, proposedFed.get());
        }

        @Test
        void getProposedFederation_whenNoProposedFederation_shouldReturnEmpty() {
            // Act
            var actualProposedFederation = bridgeSupport.getProposedFederation();

            // Assert
            assertFalse(actualProposedFederation.isPresent());
        }

        @Test
        void getProposedFederationAddress_whenNoProposedFederationAddress_shouldReturnEmpty() {
            // Act
            var actualProposedFederationAddress = bridgeSupport.getProposedFederationAddress();

            // Assert
            assertFalse(actualProposedFederationAddress.isPresent());
        }

        @Test
        void getProposedFederationAddress_whenFederationSupportReturnsAddress_shouldReturnAddress() {
            // Arrange
            var expectedAddress = federation.getAddress();
            when(federationSupport.getProposedFederationAddress()).thenReturn(Optional.of(expectedAddress));

            // Act
            var actualProposedFederationAddress = bridgeSupport.getProposedFederationAddress();

            // Assert
            assertTrue(actualProposedFederationAddress.isPresent());
            assertEquals(expectedAddress, actualProposedFederationAddress.get());
        }

        @ParameterizedTest
        @EnumSource(FederationMember.KeyType.class)
        void getProposedFederatorPublicKeyOfType_whenBridgeSupportReturnsEmpty_shouldReturnEmpty(FederationMember.KeyType keyType) {
            // Arrange
            var index = 1;

            // Act
            var actualProposedFederatorPublicKey = bridgeSupport.getProposedFederatorPublicKeyOfType(index, keyType);

            // Assert
            assertFalse(actualProposedFederatorPublicKey.isPresent());
        }

        @ParameterizedTest
        @EnumSource(FederationMember.KeyType.class)
        void getProposedFederatorPublicKeyOfType_whenProposedFederationExists_shouldReturnExpectedPublicKey(FederationMember.KeyType keyType) {
            var index = 0;
            var member = federation.getMembers().get(index);

            // Set up public keys based on the keyType
            byte[] expectedPublicKey;
            switch (keyType) {
                case BTC:
                    expectedPublicKey = member.getBtcPublicKey().getPubKey();
                    when(federationSupport.getProposedFederatorPublicKeyOfType(index, FederationMember.KeyType.BTC))
                        .thenReturn(Optional.of(expectedPublicKey));
                    break;
                case RSK:
                    expectedPublicKey = member.getRskPublicKey().getPubKey();
                    when(federationSupport.getProposedFederatorPublicKeyOfType(index, FederationMember.KeyType.RSK))
                        .thenReturn(Optional.of(expectedPublicKey));
                    break;
                case MST:
                    expectedPublicKey = member.getMstPublicKey().getPubKey();
                    when(federationSupport.getProposedFederatorPublicKeyOfType(index, FederationMember.KeyType.MST))
                        .thenReturn(Optional.of(expectedPublicKey));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown KeyType");
            }

            Optional<byte[]> key = bridgeSupport.getProposedFederatorPublicKeyOfType(index, keyType);
            assertTrue(key.isPresent());
            assertArrayEquals(expectedPublicKey, key.get());
        }

        @Test
        void getProposedFederationSize_whenBridgeSupportReturnsEmpty_shouldReturnEmpty() {
            // Act
            var actualProposedFederationSize = bridgeSupport.getProposedFederationSize();

            // Assert
            assertFalse(actualProposedFederationSize.isPresent());
        }

        @Test
        void getProposedFederationSize_whenProposedFederationExists_shouldReturnSize() {
            // Arrange
            var expectedSize = federation.getSize();
            when(federationSupport.getProposedFederationSize()).thenReturn(Optional.of(expectedSize));

            // Act
            var actualProposedFederationSize = bridgeSupport.getProposedFederationSize();

            // Assert
            assertTrue(actualProposedFederationSize.isPresent());
            assertEquals(expectedSize, actualProposedFederationSize.get());
        }

        @Test
        void getProposedFederationCreationTime_whenBridgeSupportReturnsEmpty_shouldReturnEmpty() {
            // Act
            var actualProposedFederationCreationTime = bridgeSupport.getProposedFederationCreationTime();

            // Assert
            assertFalse(actualProposedFederationCreationTime.isPresent());
        }

        @Test
        void getProposedFederationCreationTime_whenProposedFederationExists_shouldReturnCreationTime() {
            // Arrange
            var expectedCreationTime = federation.getCreationTime();
            when(federationSupport.getProposedFederationCreationTime()).thenReturn(Optional.of(expectedCreationTime));

            // Act
            var actualProposedFederationCreationTime = bridgeSupport.getProposedFederationCreationTime();

            // Assert
            assertTrue(actualProposedFederationCreationTime.isPresent());
            assertEquals(expectedCreationTime, actualProposedFederationCreationTime.get());
        }

        @Test
        void getProposedFederationCreationBlockNumber_whenBridgeSupportReturnsEmpty_shouldReturnEmpty() {
            // Act
            var actualProposedFederationCreationBlockNumber = bridgeSupport.getProposedFederationCreationBlockNumber();

            // Assert
            assertFalse(actualProposedFederationCreationBlockNumber.isPresent());
        }

        @Test
        void getProposedFederationCreationBlockNumber_whenProposedFederationExists_shouldReturnCreationBlockNumber() {
            // Arrange
            var expectedCreationBlockNumber = federation.getCreationBlockNumber();
            when(federationSupport.getProposedFederationCreationBlockNumber()).thenReturn(Optional.of(expectedCreationBlockNumber));

            // Act
            var actualProposedFederationCreationBlockNumber = bridgeSupport.getProposedFederationCreationBlockNumber();

            // Assert
            assertTrue(actualProposedFederationCreationBlockNumber.isPresent());
            assertEquals(expectedCreationBlockNumber, actualProposedFederationCreationBlockNumber.get());
        }

        @Test
        void getProposedFederatorPublicKeyOfType_whenIndexOutOfBoundsForMemberList_shouldThrowException() {
            // Arrange
            var index = 1;
            var keyType = FederationMember.KeyType.BTC;
            when(federationSupport.getProposedFederatorPublicKeyOfType(index, keyType))
                .thenThrow(new IndexOutOfBoundsException());

            // Act & Assert
            assertThrows(IndexOutOfBoundsException.class,
                () -> bridgeSupport.getProposedFederatorPublicKeyOfType(index, keyType));
        }

        @Test
        void voteFederationChange() {
            CallContext tx = PegTestUtils.callWithHash(Hash.ZERO);
            ABICallSpec callSpec = mock(ABICallSpec.class);
            int result = 1;

            when(federationSupport.voteFederationChange(any(), any(), any())).thenReturn(result);
            assertEquals(result, bridgeSupport.voteFederationChange(tx, callSpec));
        }

        @Test
        void updateFederationCreationBlockHeights_callsFederationSupportUpdateFederationCreationBlockHeights() {
            bridgeSupport.updateFederationCreationBlockHeights();
            verify(federationSupport).updateFederationCreationBlockHeights();
        }

        @Test
        void save_callsFederationSupportSave() {
            bridgeSupport.save();
            verify(federationSupport).save();
        }
    }

    @Nested
    @Tag("LockingCap")
    class LockingCapTest {

        private LockingCapSupport lockingCapSupport;
        private BridgeSupport bridgeSupport;
        private final LockingCapConstants constants = LockingCapMainNetConstants.getInstance();

        @BeforeEach
        void setUp() {
            lockingCapSupport = mock(LockingCapSupportImpl.class);
            bridgeSupport = bridgeSupportBuilder
                .withLockingCapSupport(lockingCapSupport)
                .build();
        }

        @Test
        void getLockingCap_whenNoValueExistsInStorage_shouldReturnInitialValue() {
            // Arrange
            Optional<Coin> expectedLockingCap = Optional.of(constants.getInitialValue());
            when(lockingCapSupport.getLockingCap()).thenReturn(expectedLockingCap);

            // Act
            Optional<Coin> actualLockingCap = Optional.of(bridgeSupport.getLockingCap());

            // Assert
            assertEquals(expectedLockingCap, actualLockingCap);
        }

        @Test
        void getLockingCap_whenLockingCapIsEmpty_shouldReturnNull() {
            // Arrange
            when(lockingCapSupport.getLockingCap()).thenReturn(Optional.empty());

            // Act
            Coin actualLockingCap = bridgeSupport.getLockingCap();

            // Assert
            assertNull(actualLockingCap);
        }

        @Test
        void increaseLockingCap() throws LockingCapIllegalArgumentException {
            // Arrange
            Coin newLockingCap = constants.getInitialValue().add(Coin.SATOSHI);
            CallContext tx = PegTestUtils.callFrom(LockingCapCaller.FIRST_AUTHORIZED.getRskAddress());
            when(lockingCapSupport.increaseLockingCap(tx, newLockingCap)).thenReturn(true);
            when(lockingCapSupport.getLockingCap()).thenReturn(Optional.of(newLockingCap));

            // Act
            boolean actualResult = bridgeSupport.increaseLockingCap(tx, newLockingCap);

            // Assert
            assertTrue(actualResult);
            assertEquals(newLockingCap, bridgeSupport.getLockingCap());
        }
    }

    @Test
    void isBtcTxHashAlreadyProcessed() throws IOException {
        BridgeConstants bridgeConstants = new BridgeRegTestConstants();

        Sha256Hash hash1 = Sha256Hash.ZERO_HASH;
        Sha256Hash hash2 = Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000001");

        BridgeStorageProvider bridgeStorageProviderMock = mock(BridgeStorageProvider.class);
        when(bridgeStorageProviderMock.getHeightIfBtcTxhashIsAlreadyProcessed(hash1)).thenReturn(Optional.of(1L));

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstants)
            .withProvider(bridgeStorageProviderMock)
            .build();

        assertTrue(bridgeSupport.isBtcTxHashAlreadyProcessed(hash1));
        assertFalse(bridgeSupport.isBtcTxHashAlreadyProcessed(hash2));
    }

    @Test
    void getBtcTxHashProcessedHeight() throws IOException {
        BridgeConstants bridgeConstants = new BridgeRegTestConstants();

        Sha256Hash hash1 = Sha256Hash.ZERO_HASH;
        Sha256Hash hash2 = Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000001");

        BridgeStorageProvider bridgeStorageProviderMock = mock(BridgeStorageProvider.class);
        when(bridgeStorageProviderMock.getHeightIfBtcTxhashIsAlreadyProcessed(hash1)).thenReturn(Optional.of(1L));

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstants)
            .withProvider(bridgeStorageProviderMock)
            .build();

        assertEquals(Long.valueOf(1), bridgeSupport.getBtcTxHashProcessedHeight(hash1));
        assertEquals(Long.valueOf(-1), bridgeSupport.getBtcTxHashProcessedHeight(hash2));
    }

    @Test
    void eventLoggerLogPeginRejectionEvents() throws Exception {

        BridgeEventLogger mockedEventLogger = mock(BridgeEventLogger.class);
        Federation genesisFederation = FederationTestUtils.getGenesisFederation(federationConstantsRegtest);

        BridgeStorageProvider mockBridgeStorageProvider = mock(BridgeStorageProvider.class);
        when(mockBridgeStorageProvider.getHeightIfBtcTxhashIsAlreadyProcessed(any(Sha256Hash.class))).thenReturn(Optional.empty());

        FederationStorageProvider federationStorageProviderMock = mock(FederationStorageProvider.class);
        when(federationStorageProviderMock.getNewFederation(any())).thenReturn(genesisFederation);

        BtcBlockStoreWithCache.Factory btcBlockStoreFactory = mock(BtcBlockStoreWithCache.Factory.class);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        when(btcBlockStoreFactory.newInstance(any(BridgeHost.class), any(), any())).thenReturn(btcBlockStore);

        // Create transaction
        Coin lockValue = Coin.COIN;
        BtcTransaction tx = new BtcTransaction(bridgeConstantsRegtest.getBtcParams());
        tx.addOutput(lockValue, federationStorageProviderMock.getNewFederation(any()).getAddress());
        BtcECKey srcKey = new BtcECKey();
        tx.addInput(BitcoinTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, srcKey));

        // Create header and PMT
        byte[] bits = new byte[1];
        bits[0] = 0x3f;
        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(bridgeConstantsRegtest.getBtcParams(), bits, hashes, 1);
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(new ArrayList<>());
        co.rsk.bitcoinj.core.BtcBlock btcBlock = new co.rsk.bitcoinj.core.BtcBlock(
            bridgeConstantsRegtest.getBtcParams(),
            1,
            BitcoinTestUtils.createHash(1),
            merkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        int height = 1;

        mockChainOfStoredBlocks(
            btcBlockStore,
            btcBlock,
            height + bridgeConstantsRegtest.getBtc2RskMinimumAcceptableConfirmations(),
            height
        );

        BtcLockSenderProvider btcLockSenderProvider = mock(BtcLockSenderProvider.class);
        when(btcLockSenderProvider.tryGetBtcLockSender(any(BtcTransaction.class))).thenReturn(Optional.empty());

        PeginInstructionsProvider peginInstructionsProvider = mock(PeginInstructionsProvider.class);
        when(peginInstructionsProvider.buildPeginInstructions(any(BtcTransaction.class))).thenReturn(Optional.empty());

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProviderMock)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(mockBridgeStorageProvider)
            .withEventLogger(mockedEventLogger)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(new PeginInstructionsProvider())
            .withHost(host)
            .withBtcBlockStoreFactory(btcBlockStoreFactory)
            .withFederationSupport(federationSupport)
            .build();

        bridgeSupport.registerBtcTransaction(
            PegTestUtils.callWithHash(Hash.ZERO),
            tx.bitcoinSerialize(),
            height,
            pmt.bitcoinSerialize()
        );

        verify(mockedEventLogger, atLeastOnce()).logRejectedPegin(any(BtcTransaction.class), any(RejectedPeginReason.class));
        verify(mockedEventLogger, atLeastOnce()).logNonRefundablePegin(any(BtcTransaction.class), any(
            NonRefundablePeginReason.class));
    }

    @Test
    void eventLoggerLogPeginBtc() throws Exception {

        BridgeEventLogger mockedEventLogger = mock(BridgeEventLogger.class);
        Federation genesisFederation = FederationTestUtils.getGenesisFederation(federationConstantsRegtest);

        BridgeStorageProvider mockBridgeStorageProvider = mock(BridgeStorageProvider.class);
        when(mockBridgeStorageProvider.getHeightIfBtcTxhashIsAlreadyProcessed(any(Sha256Hash.class))).thenReturn(Optional.empty());

        FederationStorageProvider federationStorageProviderMock = mock(FederationStorageProvider.class);
        when(federationStorageProviderMock.getNewFederation(any())).thenReturn(genesisFederation);

        BtcBlockStoreWithCache.Factory btcBlockStoreFactory = mock(BtcBlockStoreWithCache.Factory.class);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        when(btcBlockStoreFactory.newInstance(any(BridgeHost.class), any(), any())).thenReturn(btcBlockStore);

        // Create transaction
        Coin lockValue = Coin.FIFTY_COINS.multiply(10);
        BtcTransaction tx = new BtcTransaction(bridgeConstantsRegtest.getBtcParams());
        tx.addOutput(lockValue, federationStorageProviderMock.getNewFederation(any()).getAddress());
        BtcECKey srcKey = new BtcECKey();
        tx.addInput(BitcoinTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, srcKey));

        // Get the tx sender public key
        byte[] data = tx.getInput(0).getScriptSig().getChunks().get(1).data;
        BtcECKey senderBtcKey = BtcECKey.fromPublicOnly(data);

        Address address = senderBtcKey.toAddress(bridgeMainNetConstants.getBtcParams());

        // Create header and PMT
        byte[] bits = new byte[1];
        bits[0] = 0x3f;
        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(bridgeMainNetConstants.getBtcParams(), bits, hashes, 1);
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(new ArrayList<>());
        co.rsk.bitcoinj.core.BtcBlock btcBlock =
            new co.rsk.bitcoinj.core.BtcBlock(bridgeMainNetConstants.getBtcParams(), 1, BitcoinTestUtils.createHash(1), merkleRoot,
                1, 1, 1, new ArrayList<>());

        int height = 1;

        mockChainOfStoredBlocks(btcBlockStore, btcBlock,
            height + bridgeMainNetConstants.getBtc2RskMinimumAcceptableConfirmations(), height);

        feePerKbSupport = new FeePerKbSupportImpl(bridgeMainNetConstants.getFeePerKbConstants(), mock(FeePerKbStorageProvider.class));
        when(mockBridgeStorageProvider.getPegoutsWaitingForConfirmations()).thenReturn(mock(PegoutsWaitingForConfirmations.class));
        CallContext rskTx = PegTestUtils.callWithHash(Hash.ZERO);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProviderMock)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeMainNetConstants)
            .withProvider(mockBridgeStorageProvider)
            .withEventLogger(mockedEventLogger)
            .withBtcLockSenderProvider(new BtcLockSenderProvider())
            .withPeginInstructionsProvider(new PeginInstructionsProvider())
            .withHost(host)
            .withBtcBlockStoreFactory(btcBlockStoreFactory)
            .withFeePerKbSupport(feePerKbSupport)
            .withFederationSupport(federationSupport)
            .build();

        bridgeSupport.registerBtcTransaction(rskTx, tx.bitcoinSerialize(), height, pmt.bitcoinSerialize());

        verify(mockedEventLogger, atLeastOnce()).logPeginBtc(any(org.hyperledger.besu.datatypes.Address.class), any(BtcTransaction.class), any(Coin.class), anyInt());
    }

    @Test
    void registerBtcTransaction_sending_segwit_tx_twice_locks_just_once() throws BlockStoreException, IOException, BridgeIllegalArgumentException {

        BtcTransaction txWithWitness = new BtcTransaction(btcRegTestParams);

        // P2SH-P2WPKH tx
        BtcECKey srcKey1 = getBtcEcKeyFromSeed("p2shp2wpkh_sender");
        byte[] redeemScript = merge(new byte[]{0x00, 0x14}, srcKey1.getPubKeyHash());

        Script witnessScript = new ScriptBuilder()
            .data(redeemScript)
            .build();
        txWithWitness.addInput(BitcoinTestUtils.createHash(1), 0, witnessScript);

        TransactionWitness txWit = new TransactionWitness(2);
        txWit.setPush(0, new byte[72]); // push for signatures
        txWit.setPush(1, srcKey1.getPubKey());
        txWithWitness.setWitness(0, txWit);

        List<BtcECKey> fedKeys = Arrays.asList(
            BtcECKey.fromPrivate(Hex.decode("fa01")),
            BtcECKey.fromPrivate(Hex.decode("fa02"))
        );

        FederationArgs federationArgs = new FederationArgs(FederationTestUtils.getFederationMembersWithBtcKeys(fedKeys),
            Instant.ofEpochMilli(1000L),
            0L,
            btcRegTestParams
        );
        Federation fed = FederationFactory.buildStandardMultiSigFederation(
            federationArgs
        );

        txWithWitness.addOutput(Coin.COIN.multiply(5), fed.getAddress());

        // Create the pmt without witness and calculate the block merkle root
        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        PartialMerkleTree pmtWithoutWitness = new PartialMerkleTree(btcRegTestParams, bits,
            Collections.singletonList(txWithWitness.getHash()), 1);
        Sha256Hash merkleRoot = pmtWithoutWitness.getTxnHashAndMerkleRoot(new ArrayList<>());

        PartialMerkleTree pmtWithWitness = new PartialMerkleTree(btcRegTestParams, bits,
            Collections.singletonList(txWithWitness.getHash(true)), 1);

        Sha256Hash witnessMerkleRoot = pmtWithWitness.getTxnHashAndMerkleRoot(new ArrayList<>());

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            merkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        FederationStorageProvider federationStorageProviderMock = mock(FederationStorageProvider.class);

        when(federationStorageProviderMock.getNewFederation(any())).thenReturn(fed);
        when(provider.getCoinbaseInformation(registerHeader.getHash())).thenReturn(new CoinbaseInformation(witnessMerkleRoot));
        LockingCapSupport lockingCapSupportMock = mock(LockingCapSupport.class);
        when(lockingCapSupportMock.getLockingCap()).thenReturn(Optional.of(Coin.FIFTY_COINS));
        // mock an actual store for the processed txs
        HashMap<Sha256Hash, Long> processedTxs = new HashMap<>();
        doAnswer(a -> {
            processedTxs.put(a.getArgument(0), a.getArgument(1));
            return null;
        }).when(provider).setHeightBtcTxhashAlreadyProcessed(any(), anyLong());
        doAnswer(a -> Optional.ofNullable(processedTxs.get(a.getArgument(0))))
            .when(provider).getHeightIfBtcTxhashIsAlreadyProcessed(any());

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        host.blockNumber(666L);

        feePerKbSupport = new FeePerKbSupportImpl(
            bridgeConstantsRegtest.getFeePerKbConstants(),
            mock(FeePerKbStorageProvider.class)
        );
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(mock(PegoutsWaitingForConfirmations.class));

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProviderMock)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .withBtcLockSenderProvider(new BtcLockSenderProvider())
            .withPeginInstructionsProvider(new PeginInstructionsProvider())
            .withHost(host)
            .withBtcBlockStoreFactory(mockFactory)
            .withFeePerKbSupport(feePerKbSupport)
            .withFederationSupport(federationSupport)
            .withLockingCapSupport(lockingCapSupport)
            .build();

        int height = 30;
        mockChainOfStoredBlocks(btcBlockStore, registerHeader, 35, height);

        // Tx is locked
        bridgeSupport.registerBtcTransaction(PegTestUtils.callWithHash(Hash.ZERO), txWithWitness.bitcoinSerialize(), height, pmtWithWitness.bitcoinSerialize());
        verify(provider, never()).setHeightBtcTxhashAlreadyProcessed(txWithWitness.getHash(true), host.blockNumber());
        verify(provider, times(1)).setHeightBtcTxhashAlreadyProcessed(txWithWitness.getHash(false), host.blockNumber());

        BtcTransaction txWithoutWitness = new BtcTransaction(btcRegTestParams, txWithWitness.bitcoinSerialize());
        txWithoutWitness.setWitness(0, null);
        assertFalse(txWithoutWitness.hasWitness());

        // Tx is NOT locked again!
        bridgeSupport.registerBtcTransaction(PegTestUtils.callWithHash(Hash.ZERO), txWithoutWitness.bitcoinSerialize(), height, pmtWithoutWitness.bitcoinSerialize());
        verify(provider, times(1)).setHeightBtcTxhashAlreadyProcessed(txWithoutWitness.getHash(), host.blockNumber());

        assertNotEquals(txWithWitness.getHash(true), txWithoutWitness.getHash());
    }

    @Test
    void callProcessFundsMigration_is_migrating() throws IOException {

        BridgeEventLogger bridgeEventLogger = mock(BridgeEventLogger.class);

        Federation oldFederation = FederationTestUtils.getGenesisFederation(federationConstantsRegtest);

        FederationArgs newFederationArgs = new FederationArgs(
            FederationTestUtils.getFederationMembers(1),
            Instant.EPOCH,
            5L,
            btcMainnetParams
        );
        Federation newFederation = FederationFactory.buildStandardMultiSigFederation(newFederationArgs);

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        FederationStorageProvider federationStorageProviderMock = mock(FederationStorageProvider.class);

        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.MILLICOIN);
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Collections.emptyList()));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(Collections.emptySet()));
        when(federationStorageProviderMock.getOldFederation(any())).thenReturn(oldFederation);
        when(federationStorageProviderMock.getNewFederation(any())).thenReturn(newFederation);

        long blockInMigrationAge = getBlockHeightInFundsMigrationAge(newFederation.getCreationBlockNumber());
        host.blockNumber(blockInMigrationAge);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProviderMock)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeMainNetConstants)
            .withProvider(provider)
            .withEventLogger(bridgeEventLogger)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        List<UTXO> sufficientUTXOsForMigration1 = new ArrayList<>();
        sufficientUTXOsForMigration1.add(createUTXO(Coin.COIN, oldFederation.getAddress()));
        when(federationStorageProviderMock.getOldFederationBtcUTXOs()).thenReturn(sufficientUTXOsForMigration1);

        bridgeSupport.updateCollections(tx);

        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntriesWithoutHash().size());
        assertEquals(1, provider.getPegoutsWaitingForConfirmations().getEntriesWithHash().size());
        PegoutsWaitingForConfirmations.Entry entry = (PegoutsWaitingForConfirmations.Entry) provider.getPegoutsWaitingForConfirmations().getEntriesWithHash().toArray()[0];
        // Should have been logged with the migrated UTXO
        verify(bridgeEventLogger, times(1)).logReleaseBtcRequested(
            tx.getHash().getBytes().toArrayUnsafe(),
            entry.getBtcTransaction(),
            Coin.COIN
        );
    }

    @Test
    void callProcessFundsMigration_is_migrated() throws IOException {

        BridgeEventLogger bridgeEventLogger = mock(BridgeEventLogger.class);

        Federation oldFederation = FederationTestUtils.getGenesisFederation(federationConstantsRegtest);

        FederationArgs newFederationArgs = new FederationArgs(
            FederationTestUtils.getFederationMembers(1),
            Instant.EPOCH,
            5L,
            btcMainnetParams
        );
        Federation newFederation = FederationFactory.buildStandardMultiSigFederation(newFederationArgs);

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        FederationStorageProvider federationStorageProviderMock = mock(FederationStorageProvider.class);

        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.MILLICOIN);
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Collections.emptyList()));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(Collections.emptySet()));
        when(federationStorageProviderMock.getOldFederation(any())).thenReturn(oldFederation);
        when(federationStorageProviderMock.getNewFederation(any())).thenReturn(newFederation);

        long blockInMigrationAge = getBlockHeightInFundsMigrationAge(newFederation.getCreationBlockNumber());
        host.blockNumber(blockInMigrationAge);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProviderMock)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeMainNetConstants)
            .withProvider(provider)
            .withEventLogger(bridgeEventLogger)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        List<UTXO> sufficientUTXOsForMigration1 = new ArrayList<>();
        sufficientUTXOsForMigration1.add(createUTXO(Coin.COIN, oldFederation.getAddress()));
        when(federationStorageProviderMock.getOldFederationBtcUTXOs())
            .thenReturn(sufficientUTXOsForMigration1);

        bridgeSupport.updateCollections(tx);

        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntriesWithoutHash().size());
        assertEquals(1, provider.getPegoutsWaitingForConfirmations().getEntriesWithHash().size());
        PegoutsWaitingForConfirmations.Entry entry = (PegoutsWaitingForConfirmations.Entry) provider.getPegoutsWaitingForConfirmations().getEntriesWithHash().toArray()[0];
        // Should have been logged with the migrated UTXO
        verify(bridgeEventLogger, times(1)).logReleaseBtcRequested(
            tx.getHash().getBytes().toArrayUnsafe(),
            entry.getBtcTransaction(),
            Coin.COIN
        );
    }

    @Test
    void updateFederationCreationBlockHeights() throws IOException {

        BridgeEventLogger bridgeEventLogger = mock(BridgeEventLogger.class);

        Federation oldFederation = FederationTestUtils.getGenesisFederation(federationConstantsRegtest);

        FederationArgs newFederationArgs = new FederationArgs(FederationTestUtils.getFederationMembers(1),
            Instant.EPOCH,
            5L,
            btcMainnetParams
        );
        Federation newFederation = FederationFactory.buildStandardMultiSigFederation(newFederationArgs);

        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.MILLICOIN);

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Collections.emptyList()));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(Collections.emptySet()));

        FederationStorageProvider federationStorageProviderMock = mock(FederationStorageProvider.class);
        when(federationStorageProviderMock.getOldFederation(any())).thenReturn(oldFederation);
        when(federationStorageProviderMock.getNewFederation(any())).thenReturn(newFederation);

        long blockInMigrationAge = getBlockHeightInFundsMigrationAge(newFederation.getCreationBlockNumber());
        host.blockNumber(blockInMigrationAge);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProviderMock)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeMainNetConstants)
            .withProvider(provider)
            .withEventLogger(bridgeEventLogger)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        List<UTXO> sufficientUTXOsForMigration1 = new ArrayList<>();
        sufficientUTXOsForMigration1.add(createUTXO(Coin.COIN, oldFederation.getAddress()));
        when(federationStorageProviderMock.getOldFederationBtcUTXOs())
            .thenReturn(sufficientUTXOsForMigration1);

        when(federationStorageProviderMock.getNextFederationCreationBlockHeight()).thenReturn(Optional.empty());

        bridgeSupport.updateCollections(tx);

        verify(federationStorageProviderMock, times(1)).getNextFederationCreationBlockHeight();
        verify(federationStorageProviderMock, never()).setActiveFederationCreationBlockHeight(any(Long.class));
        verify(federationStorageProviderMock, never()).clearNextFederationCreationBlockHeight();

        when(federationStorageProviderMock.getNextFederationCreationBlockHeight()).thenReturn(Optional.of(1L));

        bridgeSupport.updateCollections(tx);

        verify(federationStorageProviderMock, times(2)).getNextFederationCreationBlockHeight();
        verify(federationStorageProviderMock, times(1)).setActiveFederationCreationBlockHeight(1L);
        verify(federationStorageProviderMock, times(1)).clearNextFederationCreationBlockHeight();
    }

    @Test
    void rskTxWaitingForSignature_emitNewPegoutConfirmedEvent() throws IOException {

        BridgeConstants spiedBridgeConstants = spy(new BridgeRegTestConstants());
        doReturn(1).when(spiedBridgeConstants).getRsk2BtcMinimumAcceptableConfirmations();

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        BtcTransaction btcTx = mock(BtcTransaction.class);
        Set<PegoutsWaitingForConfirmations.Entry> pegoutsWaitingForConfirmations = new HashSet<>();
        long rskBlockNumber = 1L;
        pegoutsWaitingForConfirmations.add(new PegoutsWaitingForConfirmations.Entry(btcTx, rskBlockNumber, PegTestUtils.createHash3(1)));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(pegoutsWaitingForConfirmations));
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Collections.emptyList()));
        when(provider.getPegoutsWaitingForSignatures()).thenReturn(new TreeMap<>());

        host.blockNumber(2L);

        BridgeEventLogger eventLogger = mock(BridgeEventLogger.class);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(spiedBridgeConstants)
            .withProvider(provider)
            .withHost(host)
            .withEventLogger(eventLogger)
            .withFederationSupport(federationSupport)
            .build();
        bridgeSupport.updateCollections(tx);

        verify(eventLogger, times(1)).logPegoutConfirmed(btcTx.getHash(), rskBlockNumber);

    }

    @Test
    void rskTxWaitingForSignature_noTxWithEnoughConfirmation_pegoutConfirmedEventNotEmitted() throws IOException {

        BridgeConstants spiedBridgeConstants = spy(new BridgeRegTestConstants());
        doReturn(1).when(spiedBridgeConstants).getRsk2BtcMinimumAcceptableConfirmations();

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        BtcTransaction btcTx = mock(BtcTransaction.class);
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(new HashSet<>()));
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Collections.emptyList()));
        when(provider.getPegoutsWaitingForSignatures()).thenReturn(new TreeMap<>());

        host.blockNumber(2L);

        BridgeEventLogger eventLogger = mock(BridgeEventLogger.class);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(spiedBridgeConstants)
            .withProvider(provider)
            .withHost(host)
            .withEventLogger(eventLogger)
            .withFederationSupport(federationSupport)
            .build();
        bridgeSupport.updateCollections(tx);

        verify(eventLogger, times(0)).logPegoutConfirmed(btcTx.getHash(), 1L);
    }

    @Test
    void rskTxWaitingForSignature_uses_release_transaction_rstTxHash() throws IOException {

        BridgeConstants spiedBridgeConstants = spy(new BridgeRegTestConstants());
        doReturn(1).when(spiedBridgeConstants).getRsk2BtcMinimumAcceptableConfirmations();

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        BtcTransaction btcTx = mock(BtcTransaction.class);
        Set<PegoutsWaitingForConfirmations.Entry> pegoutsWaitingForConfirmations = new HashSet<>();
        Hash rskTxHash = Hash.ZERO;
        pegoutsWaitingForConfirmations.add(new PegoutsWaitingForConfirmations.Entry(btcTx, 1L, rskTxHash)); // HAS rsk tx hash
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(pegoutsWaitingForConfirmations));
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Collections.emptyList()));
        when(provider.getPegoutsWaitingForSignatures()).thenReturn(new TreeMap<>());

        host.blockNumber(2L);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(spiedBridgeConstants)
            .withProvider(provider)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .build();
        bridgeSupport.updateCollections(tx);

        assertEquals(btcTx, provider.getPegoutsWaitingForSignatures().get(rskTxHash));
        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());
    }

    private static Stream<BridgeConstants> provideBridgeConstants() {
        return Stream.of(new BridgeRegTestConstants(), BridgeTestNetConstants.getInstance(), BridgeMainNetConstants.getInstance());
    }

    @Test
    void rskTxWaitingForSignature_fail_adding_an_already_existing_key() throws IOException {
        // Arrange
        BridgeConstants bridgeConstants = new BridgeRegTestConstants();

        // Set state to make concur a pegout migration tx and pegout batch creation on the same updateCollection
        Federation oldFederation = FederationTestUtils.getGenesisFederation(federationConstantsRegtest);
        FederationArgs newFederationArgs = new FederationArgs(FederationTestUtils.getFederationMembers(1),
            Instant.EPOCH,
            5L,
            bridgeConstants.getBtcParams()
        );
        Federation newFederation = FederationFactory.buildStandardMultiSigFederation(
            newFederationArgs
        );

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        FederationStorageProvider federationStorageProviderMock = mock(FederationStorageProvider.class);

        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.MILLICOIN);
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(PegTestUtils.createReleaseRequestQueueEntries(3)));

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = new PegoutsWaitingForConfirmations(new HashSet<>());
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(pegoutsWaitingForConfirmations);

        SortedMap<Hash, BtcTransaction> pegoutWaitingForSignatures = new TreeMap<>();
        when(provider.getPegoutsWaitingForSignatures()).thenReturn(pegoutWaitingForSignatures);

        // Federation change on going
        when(federationStorageProviderMock.getOldFederation(any()))
            .thenReturn(oldFederation);
        when(federationStorageProviderMock.getNewFederation(any()))
            .thenReturn(newFederation);

        // Utxos to migrate
        List<UTXO> utxos = BitcoinTestUtils.createUTXOs(10, oldFederation.getAddress());
        when(federationStorageProviderMock.getOldFederationBtcUTXOs())
            .thenReturn(utxos);

        List<UTXO> utxosNew = BitcoinTestUtils.createUTXOs(10, newFederation.getAddress());
        when(federationStorageProviderMock.getNewFederationBtcUTXOs())
            .thenReturn(utxosNew);

        // Advance blockchain to migration phase
        host.blockNumber(180);

        federationSupport = federationSupportBuilder
            .withFederationConstants(bridgeConstants.getFederationConstants())
            .withFederationStorageProvider(federationStorageProviderMock)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstants)
            .withProvider(provider)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .build();
        bridgeSupport.updateCollections(tx);

        // Assert two transactions are added to pegoutsWaitingForConfirmations, one pegout batch and one migration tx
        assertEquals(2, pegoutsWaitingForConfirmations.getEntries().size());

        // Get new fed wallet to identify the migration tx
        Wallet newFedWallet = BridgeUtils.getFederationNoSpendWallet(new Context(bridgeConstants.getBtcParams()), newFederation);

        PegoutsWaitingForConfirmations.Entry migrationTx = null;
        PegoutsWaitingForConfirmations.Entry pegoutBatchTx = null;

        // If all outputs are sent to the active fed then it's the migration tx; if not, it's the peg-out batch
        for (PegoutsWaitingForConfirmations.Entry entry : pegoutsWaitingForConfirmations.getEntries()) {
            List<TransactionOutput> walletOutputs = entry.getBtcTransaction().getWalletOutputs(newFedWallet);
            if (walletOutputs.size() == entry.getBtcTransaction().getOutputs().size()) {
                migrationTx = entry;
            } else {
                pegoutBatchTx = entry;
            }
        }

        // Assert the two added pegouts have the same pegoutCreationRskTxHash
        assertEquals(migrationTx.getPegoutCreationRskTxHash(), pegoutBatchTx.getPegoutCreationRskTxHash());

        // Assert no pegouts were moved to pegoutsWaitingForSignatures
        assertEquals(0, pegoutWaitingForSignatures.size());

        // Advance blockchain to the height where both pegouts have enough confirmations to be moved to waitingForSignature
        host.blockNumber(184);
        bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstants)
            .withProvider(provider)
            .withHost(host)
            .withFeePerKbSupport(feePerKbSupport)
            .build();
        bridgeSupport.updateCollections(tx);

        // Get the transaction that was confirmed and the one that stayed unconfirmed
        PegoutsWaitingForConfirmations.Entry unconfirmedEntry = pegoutsWaitingForConfirmations.getEntries().iterator().next();
        BtcTransaction firstTransactionConfirmed = pegoutWaitingForSignatures.get(pegoutWaitingForSignatures.firstKey());

        // Since only one pegout per updateCollection call is move to pegoutsWaitingForSignatures
        // assert one of the two pegout get moved to pegoutsWaitingForSignatures and one is left on pegoutsWaitingForConfirmation
        assertEquals(1, pegoutsWaitingForConfirmations.getEntries().size());
        assertEquals(1, pegoutWaitingForSignatures.size());

        /*
            Advance blockchain one block so the bridge now will attempt to move the pegoutBatchTx to pegoutsWaitingForSignatures
            but will fail due to there's already an entry using that pegoutCreationRskTx as key. It's not allowed to
            overwrite pegoutsWaitingForSignatures entries.
         */
        host.blockNumber(185);
        BridgeSupport bridgeSupportForFailingTx = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstants)
            .withProvider(provider)
            .withHost(host)
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        CallContext throwsExceptionTx = PegTestUtils.callFrom(PublicKeys.addressOf(senderKey));

        assertThrows(IllegalStateException.class, () -> bridgeSupportForFailingTx.updateCollections(throwsExceptionTx));

        // assert both collections still without any change
        assertEquals(1, pegoutsWaitingForConfirmations.getEntries().size());
        assertTrue(pegoutsWaitingForConfirmations.getEntries().contains(unconfirmedEntry));
        assertEquals(1, pegoutWaitingForSignatures.size());
        assertEquals(firstTransactionConfirmed, pegoutWaitingForSignatures.get(pegoutWaitingForSignatures.firstKey()));

        // Now we remove the confirmed transaction from pegoutsWaitingForSignatures pretending it was signed,
        // to assert that in the next updateCollections call the unconfirmed transaction will be confirmed
        pegoutWaitingForSignatures.remove(pegoutWaitingForSignatures.firstKey());

        host.blockNumber(186);
        bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstants)
            .withProvider(provider)
            .withHost(host)
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        bridgeSupport.updateCollections(tx);

        assertEquals(0, pegoutsWaitingForConfirmations.getEntries().size());
        assertEquals(1, pegoutWaitingForSignatures.size());
        assertEquals(unconfirmedEntry.getBtcTransaction(), pegoutWaitingForSignatures.get(pegoutWaitingForSignatures.firstKey()));
    }

    @ParameterizedTest
    @MethodSource("provideBridgeConstants")
    void rskTxWaitingForSignature_uses_pegoutCreation_rskTxHash(BridgeConstants bridgeConstants) throws IOException {

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        BtcTransaction btcTx = mock(BtcTransaction.class);
        Set<PegoutsWaitingForConfirmations.Entry> pegoutsWaitingForConfirmations = new HashSet<>();
        Hash pegoutCreationRskTxHash = Hash.ZERO;
        pegoutsWaitingForConfirmations.add(new PegoutsWaitingForConfirmations.Entry(btcTx, 1L, pegoutCreationRskTxHash));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(pegoutsWaitingForConfirmations));
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Collections.emptyList()));
        when(provider.getPegoutsWaitingForSignatures()).thenReturn(new TreeMap<>());

        host.blockNumber(2L + bridgeConstants.getRsk2BtcMinimumAcceptableConfirmations());

        federationSupport = federationSupportBuilder
            .withFederationConstants(bridgeConstants.getFederationConstants())
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstants)
            .withProvider(provider)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .build();
        bridgeSupport.updateCollections(tx);

        assertNull(provider.getPegoutsWaitingForSignatures().get(tx.getHash()));
        assertEquals(btcTx, provider.getPegoutsWaitingForSignatures().get(pegoutCreationRskTxHash));
        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());
    }

    @Test()
    void rskTxWaitingForSignature_override_entry_attempt() throws IOException {

        BridgeConstants spiedBridgeConstants = spy(new BridgeRegTestConstants());
        doReturn(1).when(spiedBridgeConstants).getRsk2BtcMinimumAcceptableConfirmations();

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        BtcTransaction btcTx = mock(BtcTransaction.class);
        Set<PegoutsWaitingForConfirmations.Entry> pegoutsWaitingForConfirmations = new HashSet<>();
        Hash pegoutCreationRskTxHash = Hash.ZERO;
        pegoutsWaitingForConfirmations.add(new PegoutsWaitingForConfirmations.Entry(btcTx, 1L, pegoutCreationRskTxHash));
        when(provider.getPegoutsWaitingForConfirmations()).thenReturn(new PegoutsWaitingForConfirmations(pegoutsWaitingForConfirmations));
        when(provider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(Collections.emptyList()));

        TreeMap<Hash, BtcTransaction> txsWaitingForSignatures = new TreeMap<>();

        txsWaitingForSignatures.put(pegoutCreationRskTxHash, btcTx);
        when(provider.getPegoutsWaitingForSignatures()).thenReturn(txsWaitingForSignatures);

        host.blockNumber(2L);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(spiedBridgeConstants)
            .withProvider(provider)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .build();

        assertThrows(IllegalStateException.class, () -> bridgeSupport.updateCollections(tx));
    }

    @Test
    void when_registerBtcTransaction_sender_not_recognized_lock() throws Exception {
        // Assert

        Federation federation1 = PegTestUtils.createSimpleActiveFederation(bridgeConstantsRegtest);

        host.balance(BridgeAddresses.BRIDGE, LIMIT_MONETARY_BASE);

        host.blockNumber(10L);

        BtcECKey srcKey1 = new BtcECKey();
        org.hyperledger.besu.datatypes.Address rskAddress = PublicKeys.addressOf(srcKey1);
        org.hyperledger.besu.datatypes.Address rskDestinationAddress = org.hyperledger.besu.datatypes.Address.ZERO;
        Coin amountToLock = Coin.COIN.multiply(5);

        // First transaction goes only to the first federation
        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        tx1.addOutput(amountToLock, federation1.getAddress());
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, srcKey1));

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams());
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(host);
        federationStorageProvider.setNewFederation(federation1);

        BtcLockSenderProvider btcLockSenderProvider = mock(BtcLockSenderProvider.class);
        when(btcLockSenderProvider.tryGetBtcLockSender(any())).thenReturn(Optional.empty());

        PeginInstructionsProvider peginInstructionsProvider = getPeginInstructionsProviderForVersion1(rskDestinationAddress, Optional.empty());

        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(host, bridgeConstantsRegtest, provider)).thenReturn(btcBlockStore);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .withHost(host)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(peginInstructionsProvider)
            .withBtcBlockStoreFactory(mockFactory)
            .withFederationSupport(federationSupport)
            .build();

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            merkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        int height = 30;
        mockChainOfStoredBlocks(btcBlockStore, registerHeader, 35, height);

        // Act
        bridgeSupport.registerBtcTransaction(PegTestUtils.callWithHash(Hash.ZERO), tx1.bitcoinSerialize(), height, pmt.bitcoinSerialize());

        // Assert
        Wei totalAmountExpectedToHaveBeenLocked = Weis.fromSatoshis(amountToLock);

        assertEquals(Wei.ZERO, host.balanceOf(rskAddress));
        assertEquals(totalAmountExpectedToHaveBeenLocked, host.balanceOf(rskDestinationAddress));
        assertEquals(LIMIT_MONETARY_BASE.subtract(totalAmountExpectedToHaveBeenLocked), host.balanceOf(BridgeAddresses.BRIDGE));
        assertEquals(1, federationStorageProvider.getNewFederationBtcUTXOs().size());
        assertEquals(amountToLock, federationStorageProvider.getNewFederationBtcUTXOs().get(0).getValue());
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());
        assertTrue(provider.getPegoutsWaitingForSignatures().isEmpty());
        assertTrue(provider.getHeightIfBtcTxhashIsAlreadyProcessed(tx1.getHash()).isPresent());
    }

    @Test
    void when_registerBtcTransaction_usesLegacyType_lock_and_no_refund() throws Exception {

        Federation federation1 = PegTestUtils.createSimpleActiveFederation(bridgeConstantsRegtest);

        host.balance(BridgeAddresses.BRIDGE, LIMIT_MONETARY_BASE);

        host.blockNumber(10L);

        BtcECKey srcKey1 = new BtcECKey();
        Address btcAddress = srcKey1.toAddress(btcRegTestParams);
        org.hyperledger.besu.datatypes.Address rskAddress = PublicKeys.addressOf(srcKey1);

        //First transaction goes only to the first federation
        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        tx1.addOutput(Coin.COIN.multiply(5), federation1.getAddress());
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, srcKey1));

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams());
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(host);
        federationStorageProvider.setNewFederation(federation1);

        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(host, bridgeConstantsRegtest, provider)).thenReturn(btcBlockStore);

        BtcLockSenderProvider btcLockSenderProvider = getBtcLockSenderProvider(TxSenderAddressType.P2PKH, btcAddress, rskAddress);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .withHost(host)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(new PeginInstructionsProvider())
            .withBtcBlockStoreFactory(mockFactory)
            .withFederationSupport(federationSupport)
            .build();

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            merkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        int height = 30;
        mockChainOfStoredBlocks(btcBlockStore, registerHeader, 35, height);

        bridgeSupport.registerBtcTransaction(PegTestUtils.callWithHash(Hash.ZERO), tx1.bitcoinSerialize(), height, pmt.bitcoinSerialize());

        Wei totalAmountExpectedToHaveBeenLocked = Weis.fromSatoshis(Coin.valueOf(5, 0));

        assertEquals(totalAmountExpectedToHaveBeenLocked, host.balanceOf(rskAddress));
        assertEquals(LIMIT_MONETARY_BASE.subtract(totalAmountExpectedToHaveBeenLocked), host.balanceOf(BridgeAddresses.BRIDGE));
        assertEquals(1, federationStorageProvider.getNewFederationBtcUTXOs().size());
        assertEquals(Coin.COIN.multiply(5), federationStorageProvider.getNewFederationBtcUTXOs().get(0).getValue());
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());
        assertTrue(provider.getPegoutsWaitingForSignatures().isEmpty());
        assertTrue(provider.getHeightIfBtcTxhashIsAlreadyProcessed(tx1.getHash()).isPresent());
    }

    @Test
    void when_registerBtcTransaction_usesSegCompatibilityType_lock_and_no_refund() throws Exception {

        Federation federation1 = PegTestUtils.createSimpleActiveFederation(bridgeConstantsRegtest);

        host.balance(BridgeAddresses.BRIDGE, LIMIT_MONETARY_BASE);

        host.blockNumber(10L);

        // First transaction goes only to the first federation
        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        BtcECKey srcKey1 = new BtcECKey();
        Address btcAddress = srcKey1.toAddress(btcRegTestParams);
        org.hyperledger.besu.datatypes.Address rskAddress = PublicKeys.addressOf(srcKey1);
        Coin amountToLock = Coin.COIN.multiply(5);

        tx1.addOutput(amountToLock, federation1.getAddress());
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, srcKey1));

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams());
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(host);
        federationStorageProvider.setNewFederation(federation1);

        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(host, bridgeConstantsRegtest, provider)).thenReturn(btcBlockStore);

        BtcLockSenderProvider btcLockSenderProvider = getBtcLockSenderProvider(TxSenderAddressType.P2SHP2WPKH, btcAddress, rskAddress);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProvider)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .withHost(host)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(new PeginInstructionsProvider())
            .withBtcBlockStoreFactory(mockFactory)
            .withFederationSupport(federationSupport)
            .build();

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            merkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        int height = 30;
        mockChainOfStoredBlocks(btcBlockStore, registerHeader, 35, height);

        bridgeSupport.registerBtcTransaction(
            PegTestUtils.callWithHash(Hash.ZERO),
            tx1.bitcoinSerialize(),
            height,
            pmt.bitcoinSerialize()
        );

        Wei totalAmountExpectedToHaveBeenLocked = Weis.fromSatoshis(Coin.valueOf(5, 0));

        assertEquals(totalAmountExpectedToHaveBeenLocked, host.balanceOf(rskAddress));
        assertEquals(LIMIT_MONETARY_BASE.subtract(totalAmountExpectedToHaveBeenLocked), host.balanceOf(BridgeAddresses.BRIDGE));
        assertEquals(1, federationStorageProvider.getNewFederationBtcUTXOs().size());
        assertEquals(amountToLock, federationStorageProvider.getNewFederationBtcUTXOs().get(0).getValue());
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());
        assertTrue(provider.getPegoutsWaitingForSignatures().isEmpty());
        assertTrue(provider.getHeightIfBtcTxhashIsAlreadyProcessed(tx1.getHash()).isPresent());
    }

    @Test
    void when_registerBtcTransaction_usesMultisigType_no_lock_and_refund() throws Exception {

        Federation federation1 = PegTestUtils.createSimpleActiveFederation(bridgeConstantsRegtest);

        host.balance(BridgeAddresses.BRIDGE, LIMIT_MONETARY_BASE);

        host.blockNumber(10L);

        BtcECKey srcKey1 = new BtcECKey();
        Address btcAddress = srcKey1.toAddress(btcRegTestParams);
        Coin amountToLock = Coin.COIN.multiply(5);

        // First transaction goes only to the first federation
        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        tx1.addOutput(amountToLock, federation1.getAddress());
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, srcKey1));

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams());
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(host);
        federationStorageProvider.setNewFederation(federation1);

        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(host, bridgeConstantsRegtest, provider)).thenReturn(btcBlockStore);

        BtcLockSenderProvider btcLockSenderProvider = getBtcLockSenderProvider(TxSenderAddressType.P2SHMULTISIG, btcAddress, null);

        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.MILLICOIN);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .withHost(host)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(new PeginInstructionsProvider())
            .withBtcBlockStoreFactory(mockFactory)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            merkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        int height = 30;
        mockChainOfStoredBlocks(btcBlockStore, registerHeader, 35, height);

        bridgeSupport.registerBtcTransaction(PegTestUtils.callWithHash(Hash.ZERO), tx1.bitcoinSerialize(), height, pmt.bitcoinSerialize());

        assertEquals(LIMIT_MONETARY_BASE, host.balanceOf(BridgeAddresses.BRIDGE));
        assertEquals(0, federationStorageProvider.getNewFederationBtcUTXOs().size());
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(1, provider.getPegoutsWaitingForConfirmations().getEntries().size());

        List<BtcTransaction> pegoutBtcTxs = provider.getPegoutsWaitingForConfirmations().getEntries()
            .stream()
            .map(PegoutsWaitingForConfirmations.Entry::getBtcTransaction)
            .toList();

        // First release tx should correspond to the 5 BTC lock tx
        BtcTransaction pegoutBtcTx = pegoutBtcTxs.get(0);
        assertEquals(1, pegoutBtcTx.getOutputs().size());
        assertTrue(amountToLock.subtract(pegoutBtcTx.getOutput(0).getValue()).compareTo(Coin.MILLICOIN) <= 0);
        assertEquals(btcAddress, pegoutBtcTx.getOutput(0).getScriptPubKey().getToAddress(btcRegTestParams));
        assertEquals(1, pegoutBtcTx.getInputs().size());
        assertEquals(tx1.getHash(), pegoutBtcTx.getInput(0).getOutpoint().getHash());
        assertEquals(0, pegoutBtcTx.getInput(0).getOutpoint().getIndex());
        assertTrue(provider.getPegoutsWaitingForSignatures().isEmpty());
        assertTrue(provider.getHeightIfBtcTxhashIsAlreadyProcessed(tx1.getHash()).isPresent());
    }

    @Test
    void when_registerBtcTransaction_usesMultisigWithWitnessType_no_lock_and_refund() throws Exception {

        Federation federation1 = PegTestUtils.createSimpleActiveFederation(bridgeConstantsRegtest);

        host.balance(BridgeAddresses.BRIDGE, LIMIT_MONETARY_BASE);

        host.blockNumber(10L);

        BtcECKey srcKey1 = new BtcECKey();
        Address btcAddress = srcKey1.toAddress(btcRegTestParams);
        Coin amountToLock = Coin.COIN.multiply(5);

        // First transaction goes only to the first federation
        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        tx1.addOutput(amountToLock, federation1.getAddress());
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, srcKey1));

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams());
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(host);
        federationStorageProvider.setNewFederation(federation1);

        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(host, bridgeConstantsRegtest, provider)).thenReturn(btcBlockStore);

        BtcLockSenderProvider btcLockSenderProvider = getBtcLockSenderProvider(
            TxSenderAddressType.P2SHP2WSH,
            btcAddress,
            null
        );

        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.MILLICOIN);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .withHost(host)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(new PeginInstructionsProvider())
            .withBtcBlockStoreFactory(mockFactory)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            merkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        int height = 30;
        mockChainOfStoredBlocks(btcBlockStore, registerHeader, 35, height);

        bridgeSupport.registerBtcTransaction(
            PegTestUtils.callWithHash(Hash.ZERO),
            tx1.bitcoinSerialize(),
            height,
            pmt.bitcoinSerialize()
        );

        assertEquals(LIMIT_MONETARY_BASE, host.balanceOf(BridgeAddresses.BRIDGE));
        assertEquals(0, federationStorageProvider.getNewFederationBtcUTXOs().size());
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(1, provider.getPegoutsWaitingForConfirmations().getEntries().size());

        List<BtcTransaction> pegoutBtcTxs = provider.getPegoutsWaitingForConfirmations().getEntries()
            .stream()
            .map(PegoutsWaitingForConfirmations.Entry::getBtcTransaction)
            .toList();

        // First release tx should correspond to the 5 BTC lock tx
        BtcTransaction pegoutBtcTx = pegoutBtcTxs.get(0);
        assertEquals(1, pegoutBtcTx.getOutputs().size());
        assertTrue(amountToLock.subtract(pegoutBtcTx.getOutput(0).getValue()).compareTo(Coin.MILLICOIN) <= 0);
        assertEquals(btcAddress, pegoutBtcTx.getOutput(0).getScriptPubKey().getToAddress(btcRegTestParams));
        assertEquals(1, pegoutBtcTx.getInputs().size());
        assertEquals(tx1.getHash(), pegoutBtcTx.getInput(0).getOutpoint().getHash());
        assertEquals(0, pegoutBtcTx.getInput(0).getOutpoint().getIndex());
        assertTrue(provider.getPegoutsWaitingForSignatures().isEmpty());
        assertTrue(provider.getHeightIfBtcTxhashIsAlreadyProcessed(tx1.getHash()).isPresent());
    }

    @Test
    void registerBtcTransaction_accepts_lock_tx_with_witness() throws BlockStoreException, IOException, BridgeIllegalArgumentException {

        Federation federation1 = PegTestUtils.createSimpleActiveFederation(bridgeConstantsRegtest);

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        BtcECKey srcKey1 = new BtcECKey();
        Address btcAddress = srcKey1.toAddress(btcRegTestParams);
        org.hyperledger.besu.datatypes.Address rskAddress = PublicKeys.addressOf(srcKey1);

        Coin amountToLock = Coin.COIN.multiply(10);

        tx1.addOutput(amountToLock, federation1.getAddress());
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, new Script(new byte[]{}));
        TransactionWitness txWit = new TransactionWitness(1);
        txWit.setPush(0, new byte[]{});
        tx1.setWitness(0, txWit);

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmtWithoutWitness = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash blockMerkleRoot = pmtWithoutWitness.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            blockMerkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        List<Sha256Hash> hashes2 = new ArrayList<>();
        hashes2.add(tx1.getHash(true));
        PartialMerkleTree pmtWithWitness = new PartialMerkleTree(btcRegTestParams, bits, hashes2, 1);
        List<Sha256Hash> hashlist2 = new ArrayList<>();
        Sha256Hash witnessMerkleRoot = pmtWithWitness.getTxnHashAndMerkleRoot(hashlist2);

        int height = 50;
        StoredBlock block = new StoredBlock(registerHeader, new BigInteger("0"), height);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        co.rsk.bitcoinj.core.BtcBlock headBlock = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(2),
            Sha256Hash.of(new byte[]{1}),
            1,
            1,
            1,
            new ArrayList<>()
        );

        StoredBlock chainHead = new StoredBlock(headBlock, new BigInteger("0"), 132);
        when(btcBlockStore.getChainHead()).thenReturn(chainHead);

        when(btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight())).thenReturn(block);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);

        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams());
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(host);
        federationStorageProvider.setNewFederation(federation1);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProvider)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .withHost(host)
            .withBtcLockSenderProvider(getBtcLockSenderProvider(TxSenderAddressType.P2SHP2WPKH, btcAddress, rskAddress))
            .withPeginInstructionsProvider(new PeginInstructionsProvider())
            .withBtcBlockStoreFactory(mockFactory)
            .withFederationSupport(federationSupport)
            .build();

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(witnessMerkleRoot);
        provider.setCoinbaseInformation(registerHeader.getHash(), coinbaseInformation);

        bridgeSupport.registerBtcTransaction(PegTestUtils.callWithHash(Hash.ZERO), tx1.bitcoinSerialize(), height, pmtWithWitness.bitcoinSerialize());

        Wei totalAmountExpectedToHaveBeenLocked = Weis.fromSatoshis(amountToLock);

        assertEquals(totalAmountExpectedToHaveBeenLocked, host.balanceOf(rskAddress));
        assertEquals(1, federationStorageProvider.getNewFederationBtcUTXOs().size());
        assertEquals(amountToLock, federationStorageProvider.getNewFederationBtcUTXOs().get(0).getValue());
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());
        assertTrue(provider.getPegoutsWaitingForSignatures().isEmpty());
        assertTrue(provider.getHeightIfBtcTxhashIsAlreadyProcessed(tx1.getHash(false)).isPresent());
    }

    @Test
    void registerBtcTransaction_rejects_tx_with_witness_and_unregistered_coinbase() throws BlockStoreException, IOException, BridgeIllegalArgumentException {

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        BtcECKey srcKey1 = new BtcECKey();
        Address btcAddress = srcKey1.toAddress(btcRegTestParams);
        org.hyperledger.besu.datatypes.Address rskAddress = PublicKeys.addressOf(srcKey1);

        Coin amountToLock = Coin.COIN.multiply(10);

        tx1.addOutput(amountToLock, Address.fromBase58((new BridgeRegTestConstants()).getBtcParams(), "mvbnrCX3bg1cDRUu8pkecrvP6vQkSLDSou"));
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, new Script(new byte[]{}));
        TransactionWitness txWit = new TransactionWitness(1);
        txWit.setPush(0, new byte[]{});
        tx1.setWitness(0, txWit);

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmtWithoutWitness = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash blockMerkleRoot = pmtWithoutWitness.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            blockMerkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        List<Sha256Hash> hashes2 = new ArrayList<>();
        hashes2.add(tx1.getHash(true));
        PartialMerkleTree pmtWithWitness = new PartialMerkleTree(btcRegTestParams, bits, hashes2, 1);

        int height = 50;
        StoredBlock block = new StoredBlock(registerHeader, new BigInteger("0"), height);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        co.rsk.bitcoinj.core.BtcBlock headBlock = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(2),
            Sha256Hash.of(new byte[]{1}),
            1,
            1,
            1,
            new ArrayList<>()
        );

        StoredBlock chainHead = new StoredBlock(headBlock, new BigInteger("0"), 132);
        when(btcBlockStore.getChainHead()).thenReturn(chainHead);

        when(btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight())).thenReturn(block);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);

        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams()));

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            getBtcLockSenderProvider(TxSenderAddressType.P2SHP2WPKH, btcAddress, rskAddress),
            new PeginInstructionsProvider(),
            mockFactory
        );

        bridgeSupport.registerBtcTransaction(PegTestUtils.callWithHash(Hash.ZERO), tx1.bitcoinSerialize(), height, pmtWithWitness.bitcoinSerialize());
        verify(provider, never()).setHeightBtcTxhashAlreadyProcessed(tx1.getHash(true), height);
        verify(provider, never()).setHeightBtcTxhashAlreadyProcessed(any(Sha256Hash.class), anyLong());
    }

    @Test
    void registerBtcTransaction_rejects_tx_with_witness_and_unqual_witness_root() throws BlockStoreException, IOException, BridgeIllegalArgumentException {

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        BtcECKey srcKey1 = new BtcECKey();
        Address btcAddress = srcKey1.toAddress(btcRegTestParams);
        org.hyperledger.besu.datatypes.Address rskAddress = PublicKeys.addressOf(srcKey1);

        Coin amountToLock = Coin.COIN.multiply(10);

        tx1.addOutput(amountToLock, Address.fromBase58(btcRegTestParams, "mvbnrCX3bg1cDRUu8pkecrvP6vQkSLDSou"));
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, new Script(new byte[]{}));
        TransactionWitness txWit = new TransactionWitness(1);
        txWit.setPush(0, new byte[]{});
        tx1.setWitness(0, txWit);

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmtWithoutWitness = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash blockMerkleRoot = pmtWithoutWitness.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            blockMerkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        List<Sha256Hash> hashes2 = new ArrayList<>();
        hashes2.add(tx1.getHash(true));
        PartialMerkleTree pmtWithWitness = new PartialMerkleTree(btcRegTestParams, bits, hashes2, 1);

        int height = 50;
        StoredBlock block = new StoredBlock(registerHeader, new BigInteger("0"), height);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        co.rsk.bitcoinj.core.BtcBlock headBlock = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(2),
            Sha256Hash.of(new byte[]{1}),
            1,
            1,
            1,
            new ArrayList<>()
        );

        StoredBlock chainHead = new StoredBlock(headBlock, new BigInteger("0"), 132);
        when(btcBlockStore.getChainHead()).thenReturn(chainHead);

        when(btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight())).thenReturn(block);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);

        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams())
        );

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            getBtcLockSenderProvider(TxSenderAddressType.P2SHP2WPKH, btcAddress, rskAddress),
            new PeginInstructionsProvider(),
            mockFactory
        );

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(Sha256Hash.ZERO_HASH);
        provider.setCoinbaseInformation(registerHeader.getHash(), coinbaseInformation);

        bridgeSupport.registerBtcTransaction(PegTestUtils.callWithHash(Hash.ZERO), tx1.bitcoinSerialize(), height, pmtWithWitness.bitcoinSerialize());
        verify(provider, never()).setHeightBtcTxhashAlreadyProcessed(tx1.getHash(true), height);
        verify(provider, never()).setHeightBtcTxhashAlreadyProcessed(any(Sha256Hash.class), anyLong());
    }

    @Test
    void registerBtcTransaction_rejects_tx_without_witness_unequal_roots() throws BlockStoreException, IOException, BridgeIllegalArgumentException {

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        BtcECKey srcKey1 = new BtcECKey();
        Address btcAddress = srcKey1.toAddress(btcRegTestParams);
        org.hyperledger.besu.datatypes.Address rskAddress = PublicKeys.addressOf(srcKey1);

        Coin amountToLock = Coin.COIN.multiply(10);

        tx1.addOutput(amountToLock, Address.fromBase58(btcRegTestParams, "mvbnrCX3bg1cDRUu8pkecrvP6vQkSLDSou"));
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, new Script(new byte[]{}));

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmtWithoutWitness = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash blockMerkleRoot = pmtWithoutWitness.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            blockMerkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        int height = 50;
        StoredBlock block = new StoredBlock(registerHeader, new BigInteger("0"), height);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        co.rsk.bitcoinj.core.BtcBlock headBlock = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(2),
            Sha256Hash.of(new byte[]{1}),
            1,
            1,
            1,
            new ArrayList<>()
        );

        StoredBlock chainHead = new StoredBlock(headBlock, new BigInteger("0"), 132);
        when(btcBlockStore.getChainHead()).thenReturn(chainHead);

        when(btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight())).thenReturn(block);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);

        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams())
        );

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .withHost(host)
            .withBtcLockSenderProvider(getBtcLockSenderProvider(TxSenderAddressType.P2PKH, btcAddress, rskAddress))
            .withPeginInstructionsProvider(new PeginInstructionsProvider())
            .withBtcBlockStoreFactory(mockFactory)
            .withFederationSupport(federationSupport)
            .build();

        bridgeSupport.registerBtcTransaction(PegTestUtils.callWithHash(Hash.ZERO), tx1.bitcoinSerialize(), height, pmtWithoutWitness.bitcoinSerialize());
        verify(provider, never()).setHeightBtcTxhashAlreadyProcessed(tx1.getHash(), height);
        verify(provider, never()).setHeightBtcTxhashAlreadyProcessed(any(Sha256Hash.class), anyLong());
    }

    @Test
    void registerBtcTransaction_accepts_lock_tx_without_witness() throws BlockStoreException, IOException, BridgeIllegalArgumentException {

        Federation federation1 = PegTestUtils.createSimpleActiveFederation(bridgeConstantsRegtest);

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        BtcECKey srcKey1 = new BtcECKey();
        Address btcAddress = srcKey1.toAddress(btcRegTestParams);
        org.hyperledger.besu.datatypes.Address rskAddress = PublicKeys.addressOf(srcKey1);

        Coin amountToLock = Coin.COIN.multiply(10);

        tx1.addOutput(amountToLock, federation1.getAddress());
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, new Script(new byte[]{}));

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmtWithoutWitness = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash blockMerkleRoot = pmtWithoutWitness.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            blockMerkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        int height = 50;
        StoredBlock block = new StoredBlock(registerHeader, new BigInteger("0"), height);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        co.rsk.bitcoinj.core.BtcBlock headBlock = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(2),
            Sha256Hash.of(new byte[]{1}),
            1,
            1,
            1,
            new ArrayList<>()
        );

        StoredBlock chainHead = new StoredBlock(headBlock, new BigInteger("0"), 132);
        when(btcBlockStore.getChainHead()).thenReturn(chainHead);

        when(btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight())).thenReturn(block);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);

        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams());
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(host);
        federationStorageProvider.setNewFederation(federation1);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProvider)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .withHost(host)
            .withBtcLockSenderProvider(getBtcLockSenderProvider(TxSenderAddressType.P2PKH, btcAddress, rskAddress))
            .withPeginInstructionsProvider(new PeginInstructionsProvider())
            .withBtcBlockStoreFactory(mockFactory)
            .withFederationSupport(federationSupport)
            .build();

        bridgeSupport.registerBtcTransaction(PegTestUtils.callWithHash(Hash.ZERO), tx1.bitcoinSerialize(), height, pmtWithoutWitness.bitcoinSerialize());

        Wei totalAmountExpectedToHaveBeenLocked = Weis.fromSatoshis(amountToLock);

        assertEquals(totalAmountExpectedToHaveBeenLocked, host.balanceOf(rskAddress));
        assertEquals(1, federationStorageProvider.getNewFederationBtcUTXOs().size());
        assertEquals(amountToLock, federationStorageProvider.getNewFederationBtcUTXOs().get(0).getValue());
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());
        assertTrue(provider.getPegoutsWaitingForSignatures().isEmpty());
        assertTrue(provider.getHeightIfBtcTxhashIsAlreadyProcessed(tx1.getHash(true)).isPresent());
    }

    @Test
    void registerBtcTransaction_accepts_lock_tx_version1()
        throws BlockStoreException, IOException, PeginInstructionsException, BridgeIllegalArgumentException {
        // Arrange

        Federation federation1 = PegTestUtils.createSimpleActiveFederation(bridgeConstantsRegtest);
        host.balance(BridgeAddresses.BRIDGE, LIMIT_MONETARY_BASE);

        BtcECKey srcKey1 = new BtcECKey();
        Address btcAddressFromBtcLockSender = srcKey1.toAddress(btcRegTestParams);
        org.hyperledger.besu.datatypes.Address rskDerivedAddress = PublicKeys.addressOf(srcKey1);
        org.hyperledger.besu.datatypes.Address rskDestinationAddress = org.hyperledger.besu.datatypes.Address.ZERO;

        Coin amountToLock = Coin.COIN.multiply(10);

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        tx1.addOutput(amountToLock, federation1.getAddress());
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, new Script(new byte[]{}));

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmtWithoutWitness = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash blockMerkleRoot = pmtWithoutWitness.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            blockMerkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        int height = 50;
        StoredBlock block = new StoredBlock(registerHeader, new BigInteger("0"), height);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        co.rsk.bitcoinj.core.BtcBlock headBlock = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(2),
            Sha256Hash.of(new byte[]{1}),
            1,
            1,
            1,
            new ArrayList<>()
        );

        StoredBlock chainHead = new StoredBlock(headBlock, new BigInteger("0"), 132);
        when(btcBlockStore.getChainHead()).thenReturn(chainHead);
        when(btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight())).thenReturn(block);

        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams());
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(host);
        federationStorageProvider.setNewFederation(federation1);

        BtcLockSenderProvider btcLockSenderProvider = getBtcLockSenderProvider(
            TxSenderAddressType.P2PKH,
            btcAddressFromBtcLockSender,
            rskDerivedAddress
        );
        PeginInstructionsProvider peginInstructionsProvider = getPeginInstructionsProviderForVersion1(
            rskDestinationAddress,
            Optional.empty()
        );

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProvider)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .withHost(host)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(peginInstructionsProvider)
            .withBtcBlockStoreFactory(mockFactory)
            .withFederationSupport(federationSupport)
            .build();

        // Act
        bridgeSupport.registerBtcTransaction(PegTestUtils.callWithHash(Hash.ZERO), tx1.bitcoinSerialize(), height, pmtWithoutWitness.bitcoinSerialize());

        // Assert
        Wei totalAmountExpectedToHaveBeenLocked = Weis.fromSatoshis(amountToLock);

        assertEquals(Wei.ZERO, host.balanceOf(rskDerivedAddress));
        assertEquals(totalAmountExpectedToHaveBeenLocked, host.balanceOf(rskDestinationAddress));
        assertEquals(1, federationStorageProvider.getNewFederationBtcUTXOs().size());
        assertEquals(amountToLock, federationStorageProvider.getNewFederationBtcUTXOs().get(0).getValue());
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(0, provider.getPegoutsWaitingForConfirmations().getEntries().size());
        assertTrue(provider.getPegoutsWaitingForSignatures().isEmpty());
        assertTrue(provider.getHeightIfBtcTxhashIsAlreadyProcessed(tx1.getHash(true)).isPresent());
    }

    @Test
    void when_registerBtcTransaction_invalidPeginProtocolVersion_no_lock_and_refund()
        throws BlockStoreException, IOException, PeginInstructionsException, BridgeIllegalArgumentException {
        // Arrange

        Federation federation1 = PegTestUtils.createSimpleActiveFederation(bridgeConstantsRegtest);
        host.balance(BridgeAddresses.BRIDGE, LIMIT_MONETARY_BASE);

        BtcECKey srcKey1 = new BtcECKey();
        Address btcAddressFromBtcLockSender = srcKey1.toAddress(btcRegTestParams);
        org.hyperledger.besu.datatypes.Address rskAddress = PublicKeys.addressOf(srcKey1);
        org.hyperledger.besu.datatypes.Address rskDestinationAddress = org.hyperledger.besu.datatypes.Address.ZERO;

        Coin amountToLock = Coin.COIN.multiply(10);

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        tx1.addOutput(amountToLock, federation1.getAddress());
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, new Script(new byte[]{}));

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmtWithoutWitness = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash blockMerkleRoot = pmtWithoutWitness.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            blockMerkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        int height = 50;
        StoredBlock block = new StoredBlock(registerHeader, new BigInteger("0"), height);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        co.rsk.bitcoinj.core.BtcBlock headBlock = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(2),
            Sha256Hash.of(new byte[]{1}),
            1,
            1,
            1,
            new ArrayList<>()
        );

        StoredBlock chainHead = new StoredBlock(headBlock, new BigInteger("0"), 132);
        when(btcBlockStore.getChainHead()).thenReturn(chainHead);
        when(btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight())).thenReturn(block);

        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams());

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(host);
        federationStorageProvider.setNewFederation(federation1);

        BtcLockSenderProvider btcLockSenderProvider = getBtcLockSenderProvider(
            TxSenderAddressType.P2PKH,
            btcAddressFromBtcLockSender,
            rskAddress
        );

        PeginInstructions peginInstructions = mock(PeginInstructions.class);
        when(peginInstructions.getProtocolVersion()).thenReturn(99);
        when(peginInstructions.getRskDestinationAddress()).thenReturn(rskDestinationAddress);
        PeginInstructionsProvider peginInstructionsProvider = mock(PeginInstructionsProvider.class);
        when(peginInstructionsProvider.buildPeginInstructions(any())).thenReturn(Optional.of(peginInstructions));

        feePerKbSupport = mock(FeePerKbSupport.class);
        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.MILLICOIN);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProvider)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .withHost(host)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(peginInstructionsProvider)
            .withBtcBlockStoreFactory(mockFactory)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        // Act
        bridgeSupport.registerBtcTransaction(
            PegTestUtils.callWithHash(Hash.ZERO),
            tx1.bitcoinSerialize(),
            height,
            pmtWithoutWitness.bitcoinSerialize()
        );

        // Assert
        assertEquals(LIMIT_MONETARY_BASE, host.balanceOf(BridgeAddresses.BRIDGE));
        assertEquals(0, federationStorageProvider.getNewFederationBtcUTXOs().size());
        assertEquals(0, provider.getReleaseRequestQueue().getEntries().size());
        assertEquals(1, provider.getPegoutsWaitingForConfirmations().getEntries().size());

        List<BtcTransaction> pegoutBtcTxs = provider.getPegoutsWaitingForConfirmations().getEntries()
            .stream()
            .map(PegoutsWaitingForConfirmations.Entry::getBtcTransaction)
            .toList();

        // First release tx should correspond to the 5 BTC lock tx
        BtcTransaction pegoutBtcTx = pegoutBtcTxs.get(0);
        assertEquals(1, pegoutBtcTx.getOutputs().size());
        assertTrue(amountToLock.subtract(pegoutBtcTx.getOutput(0).getValue()).compareTo(Coin.MILLICOIN) <= 0);
        assertEquals(btcAddressFromBtcLockSender, pegoutBtcTx.getOutput(0).getScriptPubKey().getToAddress(btcRegTestParams));
        assertEquals(1, pegoutBtcTx.getInputs().size());
        assertEquals(tx1.getHash(), pegoutBtcTx.getInput(0).getOutpoint().getHash());
        assertEquals(0, pegoutBtcTx.getInput(0).getOutpoint().getIndex());
        assertTrue(provider.getPegoutsWaitingForSignatures().isEmpty());
        assertTrue(provider.getHeightIfBtcTxhashIsAlreadyProcessed(tx1.getHash()).isPresent());
    }

    @Test
    void isBlockMerkleRootValid_equal_merkle_roots() {

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            mock(BridgeStorageProvider.class),
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mock(BtcBlockStoreWithCache.Factory.class)
        );

        Sha256Hash merkleRoot = BitcoinTestUtils.createHash(1);
        BtcBlock btcBlock = mock(BtcBlock.class);
        when(btcBlock.getMerkleRoot()).thenReturn(merkleRoot);
        assertTrue(bridgeSupport.isBlockMerkleRootValid(merkleRoot, btcBlock));
    }

    @Test
    void isBlockMerkleRootValid_coinbase_information_null() {

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);

        when(provider.getCoinbaseInformation(Sha256Hash.ZERO_HASH)).thenReturn(null);

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mock(BtcBlockStoreWithCache.Factory.class)
        );

        BtcBlock btcBlock = mock(BtcBlock.class);
        when(btcBlock.getMerkleRoot()).thenReturn(Sha256Hash.ZERO_HASH);
        when(btcBlock.getHash()).thenReturn(Sha256Hash.ZERO_HASH);

        assertFalse(bridgeSupport.isBlockMerkleRootValid(BitcoinTestUtils.createHash(1), btcBlock));
    }

    @Test
    void isBlockMerkleRootValid_coinbase_information_not_null_and_unequal_mroots() {

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(BitcoinTestUtils.createHash(1));

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        when(provider.getCoinbaseInformation(Sha256Hash.ZERO_HASH)).thenReturn(coinbaseInformation);

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mock(BtcBlockStoreWithCache.Factory.class)
        );

        BtcBlock btcBlock = mock(BtcBlock.class);
        when(btcBlock.getMerkleRoot()).thenReturn(Sha256Hash.ZERO_HASH);
        when(btcBlock.getHash()).thenReturn(Sha256Hash.ZERO_HASH);

        assertFalse(bridgeSupport.isBlockMerkleRootValid(BitcoinTestUtils.createHash(2), btcBlock));
    }

    @Test
    void isBlockMerkleRootValid_coinbase_information_not_null_and_equal_mroots() {

        Sha256Hash merkleRoot = BitcoinTestUtils.createHash(1);
        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(merkleRoot);

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        when(provider.getCoinbaseInformation(Sha256Hash.ZERO_HASH)).thenReturn(coinbaseInformation);

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mock(BtcBlockStoreWithCache.Factory.class)
        );

        BtcBlock btcBlock = mock(BtcBlock.class);
        when(btcBlock.getMerkleRoot()).thenReturn(Sha256Hash.ZERO_HASH);
        when(btcBlock.getHash()).thenReturn(Sha256Hash.ZERO_HASH);

        assertTrue(bridgeSupport.isBlockMerkleRootValid(merkleRoot, btcBlock));
    }

    @Test
    void getBtcTransactionConfirmations_accepts_tx_with_witness() throws Exception {

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        tx1.addOutput(Coin.COIN.multiply(10), Address.fromBase58(btcRegTestParams, "mvbnrCX3bg1cDRUu8pkecrvP6vQkSLDSou"));
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, new Script(new byte[]{}));
        TransactionWitness txWit = new TransactionWitness(1);
        txWit.setPush(0, new byte[]{});
        tx1.setWitness(0, txWit);

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash blockMerkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            blockMerkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        List<Sha256Hash> hashes2 = new ArrayList<>();
        hashes2.add(tx1.getHash(true));
        PartialMerkleTree pmt2 = new PartialMerkleTree(btcRegTestParams, bits, hashes2, 1);
        List<Sha256Hash> hashlist2 = new ArrayList<>();
        Sha256Hash witnessMerkleRoot = pmt2.getTxnHashAndMerkleRoot(hashlist2);

        int height = 50;
        StoredBlock block = new StoredBlock(registerHeader, new BigInteger("0"), height);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        when(btcBlockStore.get(registerHeader.getHash())).thenReturn(block);

        StoredBlock chainHead = new StoredBlock(registerHeader, new BigInteger("0"), 132);
        when(btcBlockStore.getChainHead()).thenReturn(chainHead);

        when(btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight())).thenReturn(block);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams()));

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mockFactory
        );

        MerkleBranch merkleBranch = mock(MerkleBranch.class);
        when(merkleBranch.reduceFrom(tx1.getHash(true))).thenReturn(witnessMerkleRoot);

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(witnessMerkleRoot);
        provider.setCoinbaseInformation(registerHeader.getHash(), coinbaseInformation);

        int confirmations = bridgeSupport.getBtcTransactionConfirmations(tx1.getHash(true), registerHeader.getHash(), merkleBranch);
        assertEquals(chainHead.getHeight() - block.getHeight() + 1, confirmations);
    }

    @Test
    void getBtcTransactionConfirmations_unregistered_coinbase() throws Exception {

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        tx1.addOutput(Coin.COIN.multiply(10), Address.fromBase58(btcRegTestParams, "mvbnrCX3bg1cDRUu8pkecrvP6vQkSLDSou"));
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, new Script(new byte[]{}));
        TransactionWitness txWit = new TransactionWitness(1);
        txWit.setPush(0, new byte[]{});
        tx1.setWitness(0, txWit);

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash blockMerkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            blockMerkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        List<Sha256Hash> hashes2 = new ArrayList<>();
        hashes2.add(tx1.getHash(true));
        PartialMerkleTree pmt2 = new PartialMerkleTree(btcRegTestParams, bits, hashes2, 1);
        List<Sha256Hash> hashlist2 = new ArrayList<>();
        Sha256Hash witnessMerkleRoot = pmt2.getTxnHashAndMerkleRoot(hashlist2);

        int height = 50;
        StoredBlock block = new StoredBlock(registerHeader, new BigInteger("0"), height);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        when(btcBlockStore.get(registerHeader.getHash())).thenReturn(block);

        StoredBlock chainHead = new StoredBlock(registerHeader, new BigInteger("0"), 132);
        when(btcBlockStore.getChainHead()).thenReturn(chainHead);
        when(btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight())).thenReturn(block);

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams())
        );

        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mockFactory
        );

        MerkleBranch merkleBranch = mock(MerkleBranch.class);
        when(merkleBranch.reduceFrom(tx1.getHash(true))).thenReturn(witnessMerkleRoot);

        int confirmations = bridgeSupport.getBtcTransactionConfirmations(tx1.getHash(true), registerHeader.getHash(), merkleBranch);
        assertEquals(-5, confirmations);
    }

    @Test
    void getBtcTransactionConfirmations_registered_coinbase_unequal_witnessroot() throws Exception {

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);
        tx1.addOutput(Coin.COIN.multiply(10), Address.fromBase58(btcRegTestParams, "mvbnrCX3bg1cDRUu8pkecrvP6vQkSLDSou"));
        tx1.addInput(BitcoinTestUtils.createHash(1), 0, new Script(new byte[]{}));
        TransactionWitness txWit = new TransactionWitness(1);
        txWit.setPush(0, new byte[]{});
        tx1.setWitness(0, txWit);

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash blockMerkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            blockMerkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        List<Sha256Hash> hashes2 = new ArrayList<>();
        hashes2.add(tx1.getHash(true));
        PartialMerkleTree pmt2 = new PartialMerkleTree(btcRegTestParams, bits, hashes2, 1);
        List<Sha256Hash> hashlist2 = new ArrayList<>();
        Sha256Hash witnessMerkleRoot = pmt2.getTxnHashAndMerkleRoot(hashlist2);

        int height = 50;
        StoredBlock block = new StoredBlock(registerHeader, new BigInteger("0"), height);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        when(btcBlockStore.get(registerHeader.getHash())).thenReturn(block);

        StoredBlock chainHead = new StoredBlock(registerHeader, new BigInteger("0"), 132);
        when(btcBlockStore.getChainHead()).thenReturn(chainHead);

        when(btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight())).thenReturn(block);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams()));

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mockFactory
        );

        MerkleBranch merkleBranch = mock(MerkleBranch.class);
        when(merkleBranch.reduceFrom(tx1.getHash(true))).thenReturn(witnessMerkleRoot);

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(Sha256Hash.ZERO_HASH);
        provider.setCoinbaseInformation(registerHeader.getHash(), coinbaseInformation);

        int confirmations = bridgeSupport.getBtcTransactionConfirmations(tx1.getHash(true), registerHeader.getHash(), merkleBranch);
        assertEquals(-5, confirmations);
    }

    @Test
    void getBtcTransactionConfirmations_tx_without_witness_unequal_roots() throws Exception {

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            Sha256Hash.ZERO_HASH,
            1,
            1,
            1,
            new ArrayList<>()
        );

        int height = 50;
        StoredBlock block = new StoredBlock(registerHeader, new BigInteger("0"), height);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        when(btcBlockStore.get(registerHeader.getHash())).thenReturn(block);

        StoredBlock chainHead = new StoredBlock(registerHeader, new BigInteger("0"), 132);
        when(btcBlockStore.getChainHead()).thenReturn(chainHead);

        when(btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight())).thenReturn(block);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams())
        );

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mockFactory
        );

        MerkleBranch merkleBranch = mock(MerkleBranch.class);
        when(merkleBranch.reduceFrom(tx1.getHash())).thenReturn(BitcoinTestUtils.createHash(5));

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(Sha256Hash.ZERO_HASH);
        doReturn(coinbaseInformation).when(provider).getCoinbaseInformation(registerHeader.getHash());

        int confirmations = bridgeSupport.getBtcTransactionConfirmations(tx1.getHash(), registerHeader.getHash(), merkleBranch);
        assertEquals(-5, confirmations);
    }

    @Test
    void getBtcTransactionConfirmations_accepts_tx_without_witness() throws Exception {

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams);

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash blockMerkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            blockMerkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        int height = 50;
        StoredBlock block = new StoredBlock(registerHeader, new BigInteger("0"), height);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        when(btcBlockStore.get(registerHeader.getHash())).thenReturn(block);

        StoredBlock chainHead = new StoredBlock(registerHeader, new BigInteger("0"), 132);
        when(btcBlockStore.getChainHead()).thenReturn(chainHead);

        when(btcBlockStore.getStoredBlockAtMainChainHeight(block.getHeight())).thenReturn(block);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams())
        );

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mockFactory
        );

        MerkleBranch merkleBranch = mock(MerkleBranch.class);
        when(merkleBranch.reduceFrom(tx1.getHash())).thenReturn(blockMerkleRoot);

        BtcBlock btcBlock = mock(BtcBlock.class);
        when(btcBlock.getMerkleRoot()).thenReturn(blockMerkleRoot);

        int confirmations = bridgeSupport.getBtcTransactionConfirmations(tx1.getHash(), registerHeader.getHash(), merkleBranch);
        assertEquals(chainHead.getHeight() - block.getHeight() + 1, confirmations);
    }

    @Test
    void when_RegisterBtcCoinbaseTransaction_wrong_witnessReservedValue_noSent() throws BlockStoreException, AddressFormatException {

        byte[] rawTx = Hex.decode("020000000001010000000000000000000000000000000000000000000000000000000000000000fff" +
            "fffff0502cc000101ffffffff029c070395000000002321036d6b5bc8c0e902f296b5bdf3dfd4b6f095d8d0987818a557e1766e" +
            "a25c664524ac0000000000000000266a24aa21a9edfeb3b9170ae765cc6586edd67229eaa8bc19f9674d64cb10ee8a205f4ccf0" +
            "bc60120000000000000000000000000000000000000000000000000000000000000000000000000");

        BtcTransaction txWithoutWitness = new BtcTransaction(btcRegTestParams, rawTx);

        byte[] witnessReservedValue = new byte[10];

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(host, bridgeConstantsRegtest, provider)).thenReturn(btcBlockStore);

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mockFactory
        );

        byte[] bits = new byte[1];
        bits[0] = 0x01;
        List<Sha256Hash> hashes = new ArrayList<>();
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);

        //Leaving no confirmation blocks
        int height = 5;
        mockChainOfStoredBlocks(btcBlockStore, mock(BtcBlock.class), 5, height);
        when(btcBlockStore.getFromCache(mock(Sha256Hash.class))).thenReturn(new StoredBlock(mock(BtcBlock.class), BigInteger.ZERO, 0));

        assertThrows(BridgeIllegalArgumentException.class, () -> bridgeSupport.registerBtcCoinbaseTransaction(
            txWithoutWitness.bitcoinSerialize(),
            mock(Sha256Hash.class),
            pmt.bitcoinSerialize(),
            mock(Sha256Hash.class),
            witnessReservedValue
        ));

        verify(mock(BridgeStorageProvider.class), never()).setCoinbaseInformation(any(Sha256Hash.class), any(CoinbaseInformation.class));
    }

    @Test
    void when_RegisterBtcCoinbaseTransaction_MerkleTreeWrongFormat_noSent() throws BlockStoreException, AddressFormatException {

        byte[] rawTx = Hex.decode("020000000001010000000000000000000000000000000000000000000000000000000000000000fff" +
            "fffff0502cc000101ffffffff029c070395000000002321036d6b5bc8c0e902f296b5bdf3dfd4b6f095d8d0987818a557e1766e" +
            "a25c664524ac0000000000000000266a24aa21a9edfeb3b9170ae765cc6586edd67229eaa8bc19f9674d64cb10ee8a205f4ccf0" +
            "bc60120000000000000000000000000000000000000000000000000000000000000000000000000");

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams, rawTx);
        BtcTransaction txWithoutWitness = new BtcTransaction(btcRegTestParams, rawTx);

        byte[] witnessReservedValue = tx1.getWitness(0).getPush(0);

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(host, bridgeConstantsRegtest, provider)).thenReturn(btcBlockStore);

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mockFactory
        );

        //Leaving no confirmation blocks
        int height = 5;
        mockChainOfStoredBlocks(btcBlockStore, mock(BtcBlock.class), 5, height);
        when(btcBlockStore.getFromCache(mock(Sha256Hash.class))).thenReturn(new StoredBlock(mock(BtcBlock.class), BigInteger.ZERO, 0));

        assertThrows(BridgeIllegalArgumentException.class, () -> bridgeSupport.registerBtcCoinbaseTransaction(
            txWithoutWitness.bitcoinSerialize(),
            mock(Sha256Hash.class),
            new byte[]{6, 6, 6},
            mock(Sha256Hash.class),
            witnessReservedValue
        ));

        verify(mock(BridgeStorageProvider.class), never()).setCoinbaseInformation(any(Sha256Hash.class), any(CoinbaseInformation.class));
    }

    @Test
    void when_RegisterBtcCoinbaseTransaction_HashNotInPmt_noSent() throws BlockStoreException, AddressFormatException, VMException {

        byte[] rawTx = Hex.decode("020000000001010000000000000000000000000000000000000000000000000000000000000000fff" +
            "fffff0502cc000101ffffffff029c070395000000002321036d6b5bc8c0e902f296b5bdf3dfd4b6f095d8d0987818a557e1766e" +
            "a25c664524ac0000000000000000266a24aa21a9edfeb3b9170ae765cc6586edd67229eaa8bc19f9674d64cb10ee8a205f4ccf0" +
            "bc60120000000000000000000000000000000000000000000000000000000000000000000000000");

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams, rawTx);
        BtcTransaction txWithoutWitness = new BtcTransaction(btcRegTestParams, rawTx);

        Sha256Hash secondHashTx = Sha256Hash.wrap(Hex.decode("e3d0840a0825fb7d880e5cb8306745352920a8c7e8a30fac882b275e26c6bb65"));
        byte[] witnessReservedValue = tx1.getWitness(0).getPush(0);

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(host, bridgeConstantsRegtest, provider)).thenReturn(btcBlockStore);

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mockFactory
        );

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(secondHashTx);
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            merkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        //Leaving no confirmation blocks
        int height = 5;
        mockChainOfStoredBlocks(btcBlockStore, registerHeader, 5, height);
        when(btcBlockStore.getFromCache(registerHeader.getHash())).thenReturn(new StoredBlock(registerHeader, BigInteger.ZERO, 0));

        bridgeSupport.registerBtcCoinbaseTransaction(txWithoutWitness.bitcoinSerialize(), mock(Sha256Hash.class), pmt.bitcoinSerialize(), mock(Sha256Hash.class), witnessReservedValue);
        verify(mock(BridgeStorageProvider.class), never()).setCoinbaseInformation(any(Sha256Hash.class), any(CoinbaseInformation.class));
    }

    @Test
    void when_RegisterBtcCoinbaseTransaction_notVerify_noSent() throws BlockStoreException, AddressFormatException {

        BtcTransaction tx = new BtcTransaction(btcRegTestParams);

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(host, bridgeConstantsRegtest, provider)).thenReturn(btcBlockStore);

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mockFactory
        );

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            merkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        //Leaving no confirmation blocks
        int height = 5;
        mockChainOfStoredBlocks(btcBlockStore, registerHeader, 5, height);
        Sha256Hash hash = registerHeader.getHash();
        when(btcBlockStore.get(hash)).thenReturn(new StoredBlock(registerHeader, BigInteger.ZERO, 0));

        byte[] btcTxSerialized = tx.bitcoinSerialize();
        byte[] pmtSerialized = pmt.bitcoinSerialize();
        byte[] bytes = Sha256Hash.ZERO_HASH.getBytes();
        assertThrows(VerificationException.class, () -> bridgeSupport.registerBtcCoinbaseTransaction(
            btcTxSerialized,
            hash,
            pmtSerialized,
            mock(Sha256Hash.class),
            bytes
        ));

        verify(mock(BridgeStorageProvider.class), never()).setCoinbaseInformation(any(Sha256Hash.class), any(CoinbaseInformation.class));
    }

    @Test
    void when_RegisterBtcCoinbaseTransaction_not_equal_merkle_root_noSent() throws BlockStoreException, AddressFormatException, VMException {

        byte[] rawTx = Hex.decode("020000000001010000000000000000000000000000000000000000000000000000000000000000fff" +
            "fffff0502cc000101ffffffff029c070395000000002321036d6b5bc8c0e902f296b5bdf3dfd4b6f095d8d0987818a557e1766e" +
            "a25c664524ac0000000000000000266a24aa21a9edfeb3b9170ae765cc6586edd67229eaa8bc19f9674d64cb10ee8a205f4ccf0" +
            "bc60120000000000000000000000000000000000000000000000000000000000000000000000000");

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams, rawTx);
        BtcTransaction txWithoutWitness = new BtcTransaction(btcRegTestParams, rawTx);
        txWithoutWitness.setWitness(0, null);

        byte[] witnessReservedValue = tx1.getWitness(0).getPush(0);

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(host, bridgeConstantsRegtest, provider)).thenReturn(btcBlockStore);

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mockFactory
        );

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(txWithoutWitness.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            Sha256Hash.ZERO_HASH,
            1,
            1,
            1,
            new ArrayList<>()
        );

        //Leaving no confirmation blocks
        int height = 5;
        mockChainOfStoredBlocks(btcBlockStore, registerHeader, 5, height);

        BtcBlock btcBlock = mock(BtcBlock.class);
        StoredBlock storedBlock = mock(StoredBlock.class);
        when(btcBlock.getMerkleRoot()).thenReturn(Sha256Hash.ZERO_HASH);
        when(storedBlock.getHeader()).thenReturn(btcBlock);
        when(btcBlockStore.get(registerHeader.getHash())).thenReturn(storedBlock);

        bridgeSupport.registerBtcCoinbaseTransaction(
            txWithoutWitness.bitcoinSerialize(),
            registerHeader.getHash(),
            pmt.bitcoinSerialize(),
            mock(Sha256Hash.class),
            witnessReservedValue
        );
        verify(mock(BridgeStorageProvider.class), never()).setCoinbaseInformation(any(Sha256Hash.class), any(CoinbaseInformation.class));
    }

    @Test
    void when_RegisterBtcCoinbaseTransaction_null_stored_block_noSent() throws BlockStoreException, AddressFormatException {

        byte[] rawTx = Hex.decode("020000000001010000000000000000000000000000000000000000000000000000000000000000fff" +
            "fffff0502cc000101ffffffff029c070395000000002321036d6b5bc8c0e902f296b5bdf3dfd4b6f095d8d0987818a557e1766e" +
            "a25c664524ac0000000000000000266a24aa21a9edfeb3b9170ae765cc6586edd67229eaa8bc19f9674d64cb10ee8a205f4ccf0" +
            "bc60120000000000000000000000000000000000000000000000000000000000000000000000000");

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams, rawTx);
        BtcTransaction txWithoutWitness = new BtcTransaction(btcRegTestParams, rawTx);

        Sha256Hash secondHashTx = Sha256Hash.wrap(Hex.decode("e3d0840a0825fb7d880e5cb8306745352920a8c7e8a30fac882b275e26c6bb65"));

        txWithoutWitness.setWitness(0, null);
        byte[] witnessReservedValue = tx1.getWitness(0).getPush(0);

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(host, bridgeConstantsRegtest, provider)).thenReturn(btcBlockStore);

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mockFactory
        );

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        hashes.add(secondHashTx);
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 2);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            merkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        //Leaving no confirmation blocks
        int height = 5;
        mockChainOfStoredBlocks(btcBlockStore, registerHeader, 5, height);

        when(btcBlockStore.getFromCache(registerHeader.getHash())).thenReturn(null);

        assertThrows(BridgeIllegalArgumentException.class, () -> bridgeSupport.registerBtcCoinbaseTransaction(
            txWithoutWitness.bitcoinSerialize(),
            mock(Sha256Hash.class),
            pmt.bitcoinSerialize(),
            mock(Sha256Hash.class),
            witnessReservedValue
        ));

        verify(mock(BridgeStorageProvider.class), never()).setCoinbaseInformation(any(Sha256Hash.class), any(CoinbaseInformation.class));
    }

    @Test
    void registerBtcCoinbaseTransaction() throws BlockStoreException, AddressFormatException, VMException {

        byte[] rawTx = Hex.decode("020000000001010000000000000000000000000000000000000000000000000000000000000000fff" +
            "fffff0502cc000101ffffffff029c070395000000002321036d6b5bc8c0e902f296b5bdf3dfd4b6f095d8d0987818a557e1766e" +
            "a25c664524ac0000000000000000266a24aa21a9edfeb3b9170ae765cc6586edd67229eaa8bc19f9674d64cb10ee8a205f4ccf0" +
            "bc60120000000000000000000000000000000000000000000000000000000000000000000000000");

        BtcTransaction tx1 = new BtcTransaction(btcRegTestParams, rawTx);
        BtcTransaction txWithoutWitness = new BtcTransaction(btcRegTestParams, rawTx);

        Sha256Hash secondHashTx = Sha256Hash.wrap(Hex.decode("e3d0840a0825fb7d880e5cb8306745352920a8c7e8a30fac882b275e26c6bb65"));
        Sha256Hash mRoot = MerkleTreeUtils.combineLeftRight(tx1.getHash(), secondHashTx);

        txWithoutWitness.setWitness(0, null);
        byte[] witnessReservedValue = tx1.getWitness(0).getPush(0);
        Sha256Hash witnessRoot = MerkleTreeUtils.combineLeftRight(Sha256Hash.ZERO_HASH, secondHashTx);
        byte[] witnessRootBytes = witnessRoot.getReversedBytes();
        byte[] wc = tx1.getOutputs().stream().filter(t -> t.getValue().getValue() == 0).toList().get(0).getScriptPubKey().getChunks().get(1).data;
        wc = Arrays.copyOfRange(wc, 4, 36);
        Sha256Hash witCom = Sha256Hash.wrap(wc);

        assertEquals(Sha256Hash.twiceOf(witnessRootBytes, witnessReservedValue), witCom);

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(host, bridgeConstantsRegtest, provider)).thenReturn(btcBlockStore);

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mockFactory
        );

        byte[] bits = new byte[1];
        bits[0] = 0x3f;

        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx1.getHash());
        hashes.add(secondHashTx);
        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 2);
        List<Sha256Hash> hashlist = new ArrayList<>();
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(hashlist);

        //Merkle root is from the original block
        assertEquals(merkleRoot, mRoot);

        co.rsk.bitcoinj.core.BtcBlock registerHeader = new co.rsk.bitcoinj.core.BtcBlock(
            btcRegTestParams,
            1,
            BitcoinTestUtils.createHash(1),
            merkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        //Leaving no confirmation blocks
        int height = 5;
        mockChainOfStoredBlocks(btcBlockStore, registerHeader, 5, height);
        when(btcBlockStore.get(registerHeader.getHash())).thenReturn(new StoredBlock(registerHeader, BigInteger.ZERO, 0));
        bridgeSupport.registerBtcCoinbaseTransaction(
            txWithoutWitness.bitcoinSerialize(),
            registerHeader.getHash(),
            pmt.bitcoinSerialize(),
            witnessRoot,
            witnessReservedValue
        );

        ArgumentCaptor<CoinbaseInformation> argumentCaptor = ArgumentCaptor.forClass(CoinbaseInformation.class);
        verify(provider).setCoinbaseInformation(eq(registerHeader.getHash()), argumentCaptor.capture());
        assertEquals(witnessRoot, argumentCaptor.getValue().getWitnessMerkleRoot());
    }

    @Test
    void hasBtcCoinbaseTransaction() throws AddressFormatException {

        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams());

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mock(BtcBlockStoreWithCache.Factory.class)
        );

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(Sha256Hash.ZERO_HASH);
        provider.setCoinbaseInformation(Sha256Hash.ZERO_HASH, coinbaseInformation);
        assertTrue(bridgeSupport.hasBtcBlockCoinbaseTransactionInformation(Sha256Hash.ZERO_HASH));
    }

    @Test
    void hasBtcCoinbaseTransaction_fails_with_null_coinbase_information() throws AddressFormatException {

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);

        BridgeSupport bridgeSupport = getBridgeSupport(
            bridgeConstantsRegtest,
            provider,
            host,
            mock(BtcLockSenderProvider.class),
            mock(PeginInstructionsProvider.class),
            mock(BtcBlockStoreWithCache.Factory.class)
        );

        assertFalse(bridgeSupport.hasBtcBlockCoinbaseTransactionInformation(Sha256Hash.ZERO_HASH));
    }

    @Test
    void isAlreadyBtcTxHashProcessedHeight_true() throws IOException {
        BtcTransaction btcTransaction = new BtcTransaction(btcRegTestParams);
        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams());

        provider.setHeightBtcTxhashAlreadyProcessed(btcTransaction.getHash(), 1L);
        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .build();

        assertTrue(bridgeSupport.isAlreadyBtcTxHashProcessed(btcTransaction.getHash()));
    }

    @Test
    void isAlreadyBtcTxHashProcessedHeight_false() throws IOException {
        BtcTransaction btcTransaction = new BtcTransaction(btcRegTestParams);
        BridgeSupport bridgeSupport = bridgeSupportBuilder.withBridgeConstants(bridgeConstantsRegtest).build();

        assertFalse(bridgeSupport.isAlreadyBtcTxHashProcessed(btcTransaction.getHash()));
    }

    @Test
    void validationsForRegisterBtcTransaction_negative_height() throws BlockStoreException, BridgeIllegalArgumentException {
        BtcTransaction tx = new BtcTransaction(btcRegTestParams);
        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams());
        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .build();

        byte[] data = Hex.decode("ab");

        assertFalse(bridgeSupport.validationsForRegisterBtcTransaction(tx.getHash(), -1, data, data));
    }

    @Test
    void validationsForRegisterBtcTransaction_insufficient_confirmations() throws BlockStoreException, BridgeIllegalArgumentException {
        BtcTransaction tx = new BtcTransaction(btcRegTestParams);
        BtcBlockStoreWithCache.Factory btcBlockStoreFactory = new RepositoryBtcBlockStoreWithCache.Factory(bridgeConstantsRegtest.getBtcParams());
        BridgeStorageProvider provider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams());
        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .withHost(host)
            .withBtcBlockStoreFactory(btcBlockStoreFactory)
            .build();

        byte[] data = Hex.decode("ab");

        assertFalse(bridgeSupport.validationsForRegisterBtcTransaction(tx.getHash(), 100, data, data));
    }

    @Test
    void validationsForRegisterBtcTransaction_invalid_pmt() throws BlockStoreException {
        BtcTransaction btcTx = new BtcTransaction(btcRegTestParams);
        BridgeConstants bridgeConstants = mock(BridgeConstants.class);

        String pmtSerializedEncoded = "030000000279e7c0da739df8a00f12c0bff55e5438f530aa5859ff9874258cd7bad3fe709746aff89" +
            "7e6a851faa80120d6ae99db30883699ac0428fc7192d6c3fec0ca64010d";
        byte[] pmtSerialized = Hex.decode(pmtSerializedEncoded);

        int btcTxHeight = 2;

        doReturn(btcRegTestParams).when(bridgeConstants).getBtcParams();
        doReturn(0).when(bridgeConstants).getBtc2RskMinimumAcceptableConfirmations();
        StoredBlock storedBlock = mock(StoredBlock.class);
        doReturn(btcTxHeight - 1).when(storedBlock).getHeight();
        BtcBlock btcBlock = mock(BtcBlock.class);
        doReturn(Sha256Hash.of(Hex.decode("aa"))).when(btcBlock).getHash();
        doReturn(btcBlock).when(storedBlock).getHeader();
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        doReturn(storedBlock).when(btcBlockStore).getChainHead();
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstants)
            .withBtcBlockStoreFactory(mockFactory)
            .build();

        assertThrows(BridgeIllegalArgumentException.class, () -> bridgeSupport.validationsForRegisterBtcTransaction(
            btcTx.getHash(),
            btcTxHeight,
            pmtSerialized,
            btcTx.bitcoinSerialize()
        ));
    }

    @Test
    void validationsForRegisterBtcTransaction_hash_not_in_pmt() throws BlockStoreException, AddressFormatException, BridgeIllegalArgumentException {
        BtcTransaction btcTx = new BtcTransaction(btcRegTestParams);
        BridgeConstants bridgeConstants = mock(BridgeConstants.class);

        byte[] bits = new byte[1];
        bits[0] = 0x01;
        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(BitcoinTestUtils.createHash(0));

        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);

        int btcTxHeight = 2;

        doReturn(btcRegTestParams).when(bridgeConstants).getBtcParams();
        doReturn(0).when(bridgeConstants).getBtc2RskMinimumAcceptableConfirmations();
        StoredBlock storedBlock = mock(StoredBlock.class);
        doReturn(btcTxHeight - 1).when(storedBlock).getHeight();
        BtcBlock btcBlock = mock(BtcBlock.class);
        doReturn(Sha256Hash.of(Hex.decode("aa"))).when(btcBlock).getHash();
        doReturn(btcBlock).when(storedBlock).getHeader();
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        doReturn(storedBlock).when(btcBlockStore).getChainHead();
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstants)
            .withBtcBlockStoreFactory(mockFactory)
            .build();

        assertFalse(bridgeSupport.validationsForRegisterBtcTransaction(btcTx.getHash(), 0, pmt.bitcoinSerialize(), btcTx.bitcoinSerialize()));
    }

    @Test
    void validationsForRegisterBtcTransaction_exception_in_getTxnHashAndMerkleRoot()
        throws BlockStoreException, AddressFormatException {
        BtcTransaction btcTx = new BtcTransaction(btcRegTestParams);
        BridgeConstants bridgeConstants = mock(BridgeConstants.class);

        PartialMerkleTree pmt = mock(PartialMerkleTree.class);
        when(pmt.getTxnHashAndMerkleRoot(anyList())).thenReturn(Sha256Hash.ZERO_HASH).thenThrow(VerificationException.class);

        int btcTxHeight = 2;

        doReturn(btcRegTestParams).when(bridgeConstants).getBtcParams();
        doReturn(0).when(bridgeConstants).getBtc2RskMinimumAcceptableConfirmations();
        StoredBlock storedBlock = mock(StoredBlock.class);
        doReturn(btcTxHeight - 1).when(storedBlock).getHeight();
        BtcBlock btcBlock = mock(BtcBlock.class);
        doReturn(Sha256Hash.of(Hex.decode("aa"))).when(btcBlock).getHash();
        doReturn(btcBlock).when(storedBlock).getHeader();
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        doReturn(storedBlock).when(btcBlockStore).getChainHead();
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstants)
            .withBtcBlockStoreFactory(mockFactory)
            .build();

        assertThrows(BridgeIllegalArgumentException.class,
            () -> bridgeSupport.validationsForRegisterBtcTransaction(btcTx.getHash(), 0, pmt.bitcoinSerialize(), btcTx.bitcoinSerialize()));
    }

    @Test
    void validationsForRegisterBtcTransaction_tx_without_inputs() throws BlockStoreException {

        BtcTransaction btcTx = new BtcTransaction(btcRegTestParams);
        BridgeConstants bridgeConstants = mock(BridgeConstants.class);

        byte[] bits = new byte[1];
        bits[0] = 0x01;
        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(btcTx.getHash());

        PartialMerkleTree pmt = new PartialMerkleTree(btcRegTestParams, bits, hashes, 1);

        int btcTxHeight = 2;

        doReturn(btcRegTestParams).when(bridgeConstants).getBtcParams();
        doReturn(0).when(bridgeConstants).getBtc2RskMinimumAcceptableConfirmations();
        StoredBlock storedBlock = mock(StoredBlock.class);
        doReturn(btcTxHeight - 1).when(storedBlock).getHeight();
        BtcBlock btcBlock = mock(BtcBlock.class);
        doReturn(Sha256Hash.of(Hex.decode("aa"))).when(btcBlock).getHash();
        doReturn(btcBlock).when(storedBlock).getHeader();
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        doReturn(storedBlock).when(btcBlockStore).getChainHead();
        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstants)
            .withBtcBlockStoreFactory(mockFactory)
            .build();

        Sha256Hash hash = btcTx.getHash();
        byte[] pmtSerialized = pmt.bitcoinSerialize();
        byte[] decode = Hex.decode("00000000000100");
        assertThrows(VerificationException.class, () -> bridgeSupport.validationsForRegisterBtcTransaction(
            hash,
            0,
            pmtSerialized,
            decode
        ));
    }

    @Test
    void validationsForRegisterBtcTransaction_invalid_block_merkle_root() throws IOException, BlockStoreException, BridgeIllegalArgumentException {
        BridgeStorageProvider mockBridgeStorageProvider = mock(BridgeStorageProvider.class);
        when(mockBridgeStorageProvider.getHeightIfBtcTxhashIsAlreadyProcessed(any(Sha256Hash.class))).thenReturn(Optional.empty());

        BtcBlockStoreWithCache.Factory btcBlockStoreFactory = mock(BtcBlockStoreWithCache.Factory.class);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        when(btcBlockStoreFactory.newInstance(any(BridgeHost.class), any(), any())).thenReturn(btcBlockStore);

        // Create transaction
        BtcTransaction tx = new BtcTransaction(bridgeConstantsRegtest.getBtcParams());
        BtcECKey srcKey = new BtcECKey();
        tx.addInput(BitcoinTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, srcKey));

        // Create tx and PMT. Also create a btc block, that has not relation with tx and PMT.
        // The tx will be rejected because merkle block doesn't match.
        byte[] bits = new byte[1];
        bits[0] = 0x3f;
        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(bridgeConstantsRegtest.getBtcParams(), bits, hashes, 1);
        co.rsk.bitcoinj.core.BtcBlock btcBlock =
            new co.rsk.bitcoinj.core.BtcBlock(bridgeConstantsRegtest.getBtcParams(), 1, BitcoinTestUtils.createHash(1), Sha256Hash.ZERO_HASH,
                1, 1, 1, new ArrayList<>());

        int height = 1;

        mockChainOfStoredBlocks(btcBlockStore, btcBlock, height + bridgeConstantsRegtest.getBtc2RskMinimumAcceptableConfirmations(), height);

        BridgeSupport bridgeSupport = new BridgeSupport(
            bridgeConstantsRegtest,
            mockBridgeStorageProvider,
            mock(BridgeEventLogger.class),
            new BtcLockSenderProvider(),
            new PeginInstructionsProvider(),
            host,
            mock(Context.class),
            feePerKbSupport,
            mock(FederationSupport.class),
            lockingCapSupport,
            btcBlockStoreFactory
        );

        assertFalse(bridgeSupport.validationsForRegisterBtcTransaction(tx.getHash(), height, pmt.bitcoinSerialize(), tx.bitcoinSerialize()));
    }

    @Test
    void validationsForRegisterBtcTransaction_successful() throws IOException, BlockStoreException, BridgeIllegalArgumentException {
        BridgeStorageProvider mockBridgeStorageProvider = mock(BridgeStorageProvider.class);
        FederationStorageProvider federationStorageProviderMock = mock(FederationStorageProvider.class);

        Federation genesisFederation = FederationTestUtils.getGenesisFederation(federationConstantsRegtest);

        when(mockBridgeStorageProvider.getHeightIfBtcTxhashIsAlreadyProcessed(any(Sha256Hash.class))).thenReturn(Optional.empty());

        when(federationStorageProviderMock.getNewFederation(any())).thenReturn(genesisFederation);

        BtcBlockStoreWithCache.Factory btcBlockStoreFactory = mock(BtcBlockStoreWithCache.Factory.class);

        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);
        when(btcBlockStoreFactory.newInstance(any(BridgeHost.class), any(), any())).thenReturn(btcBlockStore);

        // Create transaction
        Coin lockValue = Coin.COIN;
        BtcTransaction tx = new BtcTransaction(bridgeConstantsRegtest.getBtcParams());
        tx.addOutput(lockValue, federationStorageProviderMock.getNewFederation(any()).getAddress());
        BtcECKey srcKey = new BtcECKey();
        tx.addInput(BitcoinTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, srcKey));

        // Create header and PMT. The block includes a valid merkleRoot calculated from the PMT.
        byte[] bits = new byte[1];
        bits[0] = 0x3f;
        List<Sha256Hash> hashes = new ArrayList<>();
        hashes.add(tx.getHash());
        PartialMerkleTree pmt = new PartialMerkleTree(bridgeConstantsRegtest.getBtcParams(), bits, hashes, 1);
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(new ArrayList<>());
        co.rsk.bitcoinj.core.BtcBlock btcBlock = new co.rsk.bitcoinj.core.BtcBlock(
            bridgeConstantsRegtest.getBtcParams(),
            1,
            BitcoinTestUtils.createHash(1),
            merkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );

        int height = 1;

        mockChainOfStoredBlocks(
            btcBlockStore,
            btcBlock,
            height + bridgeConstantsRegtest.getBtc2RskMinimumAcceptableConfirmations(),
            height
        );
        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(mockBridgeStorageProvider)
            .withBtcLockSenderProvider(new BtcLockSenderProvider())
            .withPeginInstructionsProvider(new PeginInstructionsProvider())
            .withFeePerKbSupport(feePerKbSupport)
            .withLockingCapSupport(lockingCapSupport)
            .withBtcBlockStoreFactory(btcBlockStoreFactory)
            .build();

        assertTrue(bridgeSupport.validationsForRegisterBtcTransaction(
            tx.getHash(),
            height,
            pmt.bitcoinSerialize(),
            tx.bitcoinSerialize()
        ));
    }

    @Test
    void receiveHeader_time_not_present_in_storage() throws IOException, BlockStoreException {
        StoredBlock storedBlock = mock(StoredBlock.class);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        BtcBlock btcBlock2 = mock(BtcBlock.class);
        when(btcBlock2.getPrevBlockHash()).thenReturn(Sha256Hash.ZERO_HASH);
        when(btcBlockStore.get(Sha256Hash.ZERO_HASH)).thenReturn(storedBlock);

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams())
        );

        BridgeSupport bridgeSupport = getBridgeSupportConfiguredToTestReceiveHeader(
            btcBlock2,
            btcBlockStore,
            provider,
            storedBlock
        );

        StoredBlock storedBlock2 = mock(StoredBlock.class);
        when(storedBlock.build(btcBlock2)).thenReturn(storedBlock2);

        bridgeSupport.receiveHeader(btcBlock2);

        verify(btcBlockStore, times(1)).put(storedBlock2);
        verify(provider, times(1)).getReceiveHeadersLastTimestamp();
        verify(provider, times(1)).setReceiveHeadersLastTimestamp(anyLong());
    }

    @Test
    void receiveHeader_time_exceed_X() throws IOException, BlockStoreException {
        StoredBlock storedBlock = mock(StoredBlock.class);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        BtcBlock btcBlock2 = mock(BtcBlock.class);
        when(btcBlock2.getPrevBlockHash()).thenReturn(Sha256Hash.ZERO_HASH);
        when(btcBlockStore.get(Sha256Hash.ZERO_HASH)).thenReturn(storedBlock);

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams())
        );

        BridgeSupport bridgeSupport = getBridgeSupportConfiguredToTestReceiveHeader(
            btcBlock2,
            btcBlockStore,
            provider,
            storedBlock
        );

        long timeStampOld = host.blockTimestamp() - (bridgeConstantsRegtest.getMinSecondsBetweenCallsToReceiveHeader() * 2L);
        doReturn(Optional.of(timeStampOld)).when(provider).getReceiveHeadersLastTimestamp();

        StoredBlock storedBlock2 = mock(StoredBlock.class);
        when(storedBlock.build(btcBlock2)).thenReturn(storedBlock2);

        bridgeSupport.receiveHeader(btcBlock2);

        verify(btcBlockStore, times(1)).put(storedBlock2);
        verify(provider, times(1)).setReceiveHeadersLastTimestamp(anyLong());
    }

    @Test
    void receiveHeader_time_less_than_X() throws IOException, BlockStoreException {
        StoredBlock storedBlock = mock(StoredBlock.class);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        BtcBlock btcBlock2 = mock(BtcBlock.class);
        when(btcBlock2.getPrevBlockHash()).thenReturn(Sha256Hash.ZERO_HASH);
        when(btcBlockStore.get(Sha256Hash.ZERO_HASH)).thenReturn(storedBlock);

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams())
        );

        BridgeSupport bridgeSupport = getBridgeSupportConfiguredToTestReceiveHeader(
            btcBlock2,
            btcBlockStore,
            provider,
            storedBlock
        );

        long timeStampOld = host.blockTimestamp() - (bridgeConstantsRegtest.getMinSecondsBetweenCallsToReceiveHeader() / 2L);
        doReturn(Optional.of(timeStampOld)).when(provider).getReceiveHeadersLastTimestamp();

        int result = bridgeSupport.receiveHeader(btcBlock2);

        StoredBlock storedBlock2 = storedBlock.build(btcBlock2);

        verify(btcBlockStore, never()).put(storedBlock2);
        verify(provider, never()).setReceiveHeadersLastTimestamp(anyLong());
        assertEquals(-1, result);
    }

    @Test
    void receiveHeader_unexpected_exception() throws IOException, BlockStoreException {
        StoredBlock storedBlock = mock(StoredBlock.class);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        BtcBlock btcBlock2 = mock(BtcBlock.class);
        when(btcBlock2.getPrevBlockHash()).thenReturn(Sha256Hash.of(new byte[]{}));

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams())
        );

        BridgeSupport bridgeSupport = getBridgeSupportConfiguredToTestReceiveHeader(
            btcBlock2,
            btcBlockStore,
            provider,
            storedBlock
        );

        int result = bridgeSupport.receiveHeader(btcBlock2);

        verify(btcBlockStore, never()).put(storedBlock);
        verify(provider, times(1)).getReceiveHeadersLastTimestamp();
        verify(provider, never()).setReceiveHeadersLastTimestamp(anyLong());
        assertEquals(-99, result);
    }

    @Test
    void receiveHeader_previous_block_not_in_storage() throws IOException, BlockStoreException {
        StoredBlock storedBlock = mock(StoredBlock.class);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        BtcBlock btcBlock2 = mock(BtcBlock.class);
        when(btcBlock2.getPrevBlockHash()).thenReturn(Sha256Hash.ZERO_HASH);
        when(btcBlockStore.get(Sha256Hash.ZERO_HASH)).thenReturn(storedBlock);

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams())
        );

        BridgeSupport bridgeSupport = getBridgeSupportConfiguredToTestReceiveHeader(
            btcBlock2,
            btcBlockStore,
            provider,
            storedBlock
        );

        when(btcBlockStore.get(any())).thenReturn(null);
        int result = bridgeSupport.receiveHeader(btcBlock2);

        StoredBlock storedBlock2 = storedBlock.build(btcBlock2);

        // Calls put when is adding the block header. (Saves his storedBlock)
        verify(btcBlockStore, never()).put(storedBlock2);
        verify(provider, never()).setReceiveHeadersLastTimestamp(anyLong());
        assertEquals(-3, result);
    }

    @Test
    void receiveHeader_block_too_old() throws IOException, BlockStoreException {
        StoredBlock storedBlock = mock(StoredBlock.class);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        BtcBlock btcBlock2 = mock(BtcBlock.class);
        when(btcBlock2.getPrevBlockHash()).thenReturn(Sha256Hash.ZERO_HASH);
        when(btcBlockStore.get(Sha256Hash.ZERO_HASH)).thenReturn(storedBlock);

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams())
        );

        BridgeSupport bridgeSupport = getBridgeSupportConfiguredToTestReceiveHeader(
            btcBlock2,
            btcBlockStore,
            provider,
            storedBlock
        );

        when(storedBlock.getHeight()).thenReturn(10, 5000, 10);

        int result = bridgeSupport.receiveHeader(btcBlock2);

        StoredBlock storedBlock2 = storedBlock.build(btcBlock2);

        verify(btcBlockStore, never()).put(storedBlock2);
        verify(provider, never()).setReceiveHeadersLastTimestamp(anyLong());
        assertEquals(-2, result);
    }

    @Test
    void receiveHeader_block_exist_in_storage() throws IOException, BlockStoreException {
        StoredBlock storedBlock = mock(StoredBlock.class);
        BtcBlockStoreWithCache btcBlockStore = mock(BtcBlockStoreWithCache.class);

        BtcBlock btcBlock = mock(BtcBlock.class);
        Sha256Hash btcBlockHash = BitcoinTestUtils.createHash(1);
        when(btcBlock.getHash()).thenReturn(btcBlockHash);
        when(btcBlockStore.get(btcBlockHash)).thenReturn(mock(StoredBlock.class));

        BridgeStorageProvider provider = spy(new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstantsRegtest.getBtcParams())
        );

        BridgeSupport bridgeSupport = getBridgeSupportConfiguredToTestReceiveHeader(
            btcBlock,
            btcBlockStore,
            provider,
            storedBlock
        );

        int result = bridgeSupport.receiveHeader(btcBlock);

        // Calls put when is adding the block header. (Saves his storedBlock)
        verify(btcBlockStore, never()).put(any(StoredBlock.class));
        verify(provider, never()).setReceiveHeadersLastTimestamp(anyLong());
        assertEquals(-4, result);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("test the methods involved are keeping testnet consensus")
    class BuildBlockThatKeepsTestnetConsensusTests {
        BridgeConstants bridgeTestnetConstants = BridgeTestNetConstants.getInstance();
        BtcBlockStoreWithCache.Factory btcBlockStoreFactory;
        BtcBlockStoreWithCache btcBlockStoreWithCache;

        BridgeSupport bridgeSupport;
        FederationSupport federationSupport = mock(FederationSupport.class);
        Sha256Hash btcBlockHash = Sha256Hash.wrap("00000000e8e7b540df01a7067e020fd7e2026bf86289def2283a35120c1af379");

        @BeforeEach
        void setUp() {
            long rskBlockNumber = 5_148_285;
            host.blockNumber(rskBlockNumber);

            BridgeStorageProvider bridgeStorageProvider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeTestnetConstants.getBtcParams());

            btcBlockStoreFactory = new RepositoryBtcBlockStoreWithCache.Factory(bridgeTestnetConstants.getBtcParams(), 100, 100);
            btcBlockStoreWithCache = btcBlockStoreFactory.newInstance(host, bridgeTestnetConstants, bridgeStorageProvider);

            when(federationSupport.getActiveFederation()).thenReturn(activeFederation);

            bridgeSupport = BridgeSupportBuilder.builder()
                .withBridgeConstants(bridgeTestnetConstants)
                .withProvider(bridgeStorageProvider)
                .withHost(host)
                .withBtcBlockStoreFactory(btcBlockStoreFactory)
                .withFederationSupport(federationSupport)
                .build();
        }

        @Test
        void getBtcTransactionConfirmations() throws BlockStoreException, IOException {
            // act
            int result = bridgeSupport.getBtcTransactionConfirmations(mock(Sha256Hash.class), btcBlockHash, mock(MerkleBranch.class)); // we dont reach the use of btcTxHash and merkleBranch in this situation

            // assert
            // checking the block is not in the storage
            assertNull(btcBlockStoreWithCache.get(btcBlockHash));
            // checking the method returns the expected code from the error thrown
            // when block from cache and block from storage dont match
            assertEquals(BTC_TRANSACTION_CONFIRMATION_INCONSISTENT_BLOCK_ERROR_CODE, result);
        }

        @Test
        void getBtcTransactionConfirmationsGetCost() throws BlockStoreException {
            // arrange
            Object[] args = new Object[4];
            args[1] = btcBlockHash.getBytes();
            args[3] = new Object[]{};

            // recreating the chainHead as real btc block 2_817_200 so blockDepth is not 0
            byte[] chainHeadRawHeader = Hex.decode("0000642d6b8df2c8ae4a20e20a10ae3b34e485701b8ae7e80ede0828040000000000000063a17c3e5063da2f8f952e496beee729932d35f62c0765baaf12f32931f01c9b49354f66ecd410190f25f402");
            BtcBlock chainHeadHeader = new BtcBlock(bridgeTestnetConstants.getBtcParams(), chainHeadRawHeader);
            int chainHeadBlockNumber = 2_817_200;
            BigInteger chainHeadChainWork = BigInteger.ONE;
            StoredBlock chainHead = new StoredBlock(chainHeadHeader, chainHeadChainWork, chainHeadBlockNumber);
            btcBlockStoreWithCache.setChainHead(chainHead);

            // act
            Long result = bridgeSupport.getBtcTransactionConfirmationsGetCost(args);

            // assert
            long BASIC_COST = 27_000;
            long STEP_COST = 315;
            long DOUBLE_HASH_COST = 144;
            int blockDepth = chainHeadBlockNumber - 2_817_125;
            int branchHashesSize = 0; // since branchHashesSize = args[3].length
            Long expectedResult = BASIC_COST + blockDepth * STEP_COST + branchHashesSize * DOUBLE_HASH_COST;

            // checking the block is not in the storage
            assertNull(btcBlockStoreWithCache.get(btcBlockHash));
            // check the result is different from BASIC_COST (result = BASIC_COST would break consensus)
            assertTrue(result > BASIC_COST);
            assertEquals(expectedResult, result);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("test chain work before and after rskip 434")
    class ChainWorkTests {
        BtcBlockStoreWithCache.Factory btcBlockStoreFactory;
        BridgeSupportBuilder bridgeSupportBuilder = BridgeSupportBuilder.builder();

        BtcBlock block849134;
        BtcBlock block849135;
        BtcBlock block849136;
        BtcBlock block849137;
        BtcBlock blockWithTooMuchWork;
        BtcBlock block849139;

        @BeforeEach
        void setUp() {
            BridgeConstants bridgeMainnetConstants = BridgeMainNetConstants.getInstance();
            btcBlockStoreFactory = new RepositoryBtcBlockStoreWithCache.Factory(bridgeMainnetConstants.getBtcParams(), 100, 100);

            String block849134Header = "0080b92c24f123130ae29e899f0cab72653722e54cdf3b30445202000000000000000000c72ead65a3b78ab637d1876c00414a77e47bcc5b52667ac1e573633563bea5a695aa7766255d031728d182a8";
            block849134 = new BtcBlock(bridgeMainnetConstants.getBtcParams(), Hex.decode(block849134Header));

            String block849135Header = "00004020bf67910b5d3996ee594848b482ee84d0e28c97a9a2d601000000000000000000865e218552bb92df36c962f5163e84a6c2542584fb36be2fa8b2a4246c73a701f1ae7766255d031791836b22";
            block849135 = new BtcBlock(bridgeMainnetConstants.getBtcParams(), Hex.decode(block849135Header));

            String block849136Header = "0000003a796f8b7a9d6ba6e13064e7c64e94570f877170262f1f0200000000000000000036b2ab17565a24a9be4626ca801cb31f91232034ba848295475f931a58dd5446e5b07766255d03173b01a491";
            block849136 = new BtcBlock(bridgeMainnetConstants.getBtcParams(), Hex.decode(block849136Header));

            String block849137Header = "00e00820925b77c9ff4d0036aa29f3238cde12e9af9d55c34ed30200000000000000000032a9fa3e12ef87a2327b55db6a16a1227bb381db8b269d90aa3a6e38cf39665f91b47766255d0317c1b1575f";
            block849137 = new BtcBlock(bridgeMainnetConstants.getBtcParams(), Hex.decode(block849137Header));

            String blockWithTooMuchWorkHeader = "006001207ca158816ffc9d45b9ecd6a49ffbf3038f3646cf13fc01000000000000000000e0182ce7cc10db785b5fb2fb4314053f5b12cd6116168797cb461aa339fc725078b87766255d0317ba5261e2";
            blockWithTooMuchWork = new BtcBlock(bridgeMainnetConstants.getBtcParams(), Hex.decode(blockWithTooMuchWorkHeader));

            String block849139Header = "00a0b625ffa2f7cbf95219fc74c3db38f84ae265784bc1417c71020000000000000000008e5b319a229376089f4a7b77c90ed90ac19a0532fc4c62426f5a5931ee7e3e8dd2c67766255d03171e91a015";
            block849139 = new BtcBlock(bridgeMainnetConstants.getBtcParams(), Hex.decode(block849139Header));
        }

        @ParameterizedTest
        @MethodSource("notMainnetArgs")
        void receiveHeader_networkNotMainnet_returnsSuccessfulAndSavesTheBlock(BridgeConstants bridgeConstants) throws BlockStoreException, IOException {
            BridgeStorageProvider bridgeStorageProvider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstants.getBtcParams());
            BridgeSupport bridgeSupport = bridgeSupportBuilder
                .withBridgeConstants(bridgeConstants)
                .withProvider(bridgeStorageProvider)
                .withHost(host)
                .withBtcBlockStoreFactory(btcBlockStoreFactory)
                .build();
            BtcBlockStoreWithCache btcBlockStoreWithCache = btcBlockStoreFactory.newInstance(host, bridgeConstants, bridgeStorageProvider);

            // Create block with too much work parent with cumulative difficulty
            BigInteger block849137ChainWork = new BigInteger("00000000000000000000000000000000000000007fffdc6f043e4a69ea179a7a", 16);
            StoredBlock block849137ToStore = new StoredBlock(block849137, block849137ChainWork, 849137);

            // save parent in storage
            btcBlockStoreWithCache.put(block849137ToStore);
            btcBlockStoreWithCache.setChainHead(block849137ToStore);
            // assert that previous block was correctly saved
            assertEquals(btcBlockStoreWithCache.getChainHead().getHeader().getHash(), block849137ToStore.getHeader().getHash());

            // assert receive header returns successful response and saves the block
            final Integer RECEIVE_HEADER_SUCCESSFUL = 0;
            assertEquals(RECEIVE_HEADER_SUCCESSFUL, bridgeSupport.receiveHeader(blockWithTooMuchWork));
            assertNotNull(btcBlockStoreWithCache.get(blockWithTooMuchWork.getHash()));
        }

        @ParameterizedTest
        @MethodSource("notMainnetArgs")
        void receiveHeaders_networkNotMainnet_savesAllBlocks(BridgeConstants bridgeConstants) throws BlockStoreException, IOException {
            BridgeStorageProvider bridgeStorageProvider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), bridgeConstants.getBtcParams());
            BridgeSupport bridgeSupport = bridgeSupportBuilder
                .withBridgeConstants(bridgeConstants)
                .withProvider(bridgeStorageProvider)
                .withHost(host)
                .withBtcBlockStoreFactory(btcBlockStoreFactory)
                .build();
            BtcBlockStoreWithCache btcBlockStoreWithCache = btcBlockStoreFactory.newInstance(host, bridgeConstants, bridgeStorageProvider);

            // Create block 849134 with cumulative difficulty
            BigInteger block849134ChainWork = new BigInteger("00000000000000000000000000000000000000007ffef81fa11393037c9df17b", 16);
            StoredBlock block849134ToStore = new StoredBlock(block849134, block849134ChainWork, 849134);

            // save block 849134 in storage
            btcBlockStoreWithCache.put(block849134ToStore);
            btcBlockStoreWithCache.setChainHead(block849134ToStore);
            // assert that block 849134 was correctly saved
            assertEquals(btcBlockStoreWithCache.getChainHead().getHeader().getHash(), block849134.getHash());

            BtcBlock[] headersToSend = new BtcBlock[]{block849135, block849136, block849137, blockWithTooMuchWork, block849139};
            // assert all blocks are correctly saved
            bridgeSupport.receiveHeaders(headersToSend);
            assertNotNull(btcBlockStoreWithCache.get(block849135.getHash()));
            assertNotNull(btcBlockStoreWithCache.get(block849136.getHash()));
            assertNotNull(btcBlockStoreWithCache.get(block849137.getHash()));
            assertNotNull(btcBlockStoreWithCache.get(blockWithTooMuchWork.getHash()));
            assertNotNull(btcBlockStoreWithCache.get(block849139.getHash()));
        }

        private Stream<Arguments> notMainnetArgs() {
            BridgeConstants testnet = BridgeTestNetConstants.getInstance();
            BridgeConstants regtest = new BridgeRegTestConstants();

            return Stream.of(
                Arguments.of(testnet),
                Arguments.of(regtest)
            );
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("test release transaction info processing")
    class ReleaseTransactionInfo {
        private final Coin outpointValue1 = Coin.valueOf(400_000);
        private final Coin outpointValue2 = Coin.valueOf(90_000);
        private final Coin outpointValue3 = Coin.valueOf(50_000);

        // requesting a release with an amount that needs all the outpoints,
        // but that also has some change to the fed
        private final Coin requestedValue1 = Coin.valueOf(100_000);
        private final Coin requestedValue2 = Coin.valueOf(400_000);
        private final List<Coin> outpointValues = List.of(outpointValue1, outpointValue2, outpointValue3);
        private final Coin totalAmountRequested = requestedValue1.add(requestedValue2);

        @BeforeEach
        void setUp() {
            federationStorageProvider.setNewFederation(activeFederation);

            Coin feePerKb = Coin.valueOf(10_000L);
            when(feePerKbSupport.getFeePerKb()).thenReturn(feePerKb);

            host.blockNumber(0L);
        }

        private void setUpBridgeSupport() {
            bridgeStorageProvider = new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), btcMainnetParams);
            btcBlockStoreFactory =
                new RepositoryBtcBlockStoreWithCache.Factory(btcMainnetParams, 100, 100);

            BridgeEventLogger bridgeEventLogger = new BridgeEventLoggerImpl(bridgeMainNetConstants, host);

            // recreate bridge support with real btcLockSenderProvider instead of a mock to be able to parse pegin
            BtcLockSenderProvider btcLockSenderProvider = new BtcLockSenderProvider();
            bridgeSupport = bridgeSupportBuilder
                .withBridgeConstants(bridgeMainNetConstants)
                .withHost(host)
                .withProvider(bridgeStorageProvider)
                .withEventLogger(bridgeEventLogger)
                .withBtcBlockStoreFactory(btcBlockStoreFactory)
                .withFederationSupport(federationSupport)
                .withFeePerKbSupport(feePerKbSupport)
                .withBtcLockSenderProvider(btcLockSenderProvider)
                .build();
        }

        private void setUpReleaseRequests() {
            // save utxos in active federation wallet
            List<UTXO> activeFederationUTXOs = List.of(
                BitcoinTestUtils.createUTXO(1, 0, outpointValue1, activeFederation.getAddress()),
                BitcoinTestUtils.createUTXO(2, 0, outpointValue2, activeFederation.getAddress()),
                BitcoinTestUtils.createUTXO(3, 0, outpointValue3, activeFederation.getAddress())
            );
            federationStorageProvider.getNewFederationBtcUTXOs().addAll(activeFederationUTXOs);

            // save releases in queue
            Address destinationAddress1 = BitcoinTestUtils.createP2PKHAddress(btcMainnetParams, "firstAddress");
            Hash rskTxHash1 = PegTestUtils.createHash3(1);
            ReleaseRequestQueue.Entry releaseEntry1 = new ReleaseRequestQueue.Entry(destinationAddress1, requestedValue1, rskTxHash1);

            Address destinationAddress2 = BitcoinTestUtils.createP2PKHAddress(btcMainnetParams, "secondAddress");
            Hash rskTxHash2 = PegTestUtils.createHash3(2);
            ReleaseRequestQueue.Entry releaseEntry2 = new ReleaseRequestQueue.Entry(destinationAddress2, requestedValue2, rskTxHash2);

            ReleaseRequestQueue releaseRequestQueue = new ReleaseRequestQueue(List.of(releaseEntry1, releaseEntry2));
            host.putStorage(RELEASE_REQUEST_QUEUE_WITH_TXHASH.getKey(), BridgeSerializationUtils.serializeReleaseRequestQueueWithTxHash(releaseRequestQueue));
        }

        @Test
        void updateCollections_whenReleasesInQueueAndLegacyFed_shouldSetRedeemDataInScriptSigAndProcessReleaseTransactionInfo() throws IOException {
            // Arrange
            setUpReleaseRequests();
            setUpBridgeSupport();

            // Act
            bridgeSupport.updateCollections(tx);
            bridgeSupport.save();

            // Assert
            BtcTransaction releaseTransaction = getReleaseFromPegoutsWFC(bridgeStorageProvider);

            // check the active fed redeem script data is in input script sig
            assertScriptSigHasExpectedInputRedeemData(releaseTransaction.getInput(0), activeFederation.getRedeemScript());

            assertReleaseWasSettled(
                host,
                bridgeStorageProvider,
                host.logs(),
                host.blockNumber(),
                tx.getHash(),
                releaseTransaction,
                outpointValues,
                totalAmountRequested
            );
        }

        @Test
        void updateCollections_whenReleasesInQueueAndSegwitCompatibleFed_shouldSetRedeemDataInWitnessAndProcessReleaseTransactionInfo() throws IOException {
            // Arrange
            activeFederation = P2shP2wshErpFederationBuilder.builder().build();
            federationStorageProvider.setNewFederation(activeFederation);

            setUpReleaseRequests();
            setUpBridgeSupport();

            // Act
            bridgeSupport.updateCollections(tx);
            bridgeSupport.save();

            // Assert
            BtcTransaction releaseTransaction = getReleaseFromPegoutsWFC(bridgeStorageProvider);

            // check the active fed redeem script data
            Script redeemScript = activeFederation.getRedeemScript();
            int inputIndex = 0;
            assertWitnessAndScriptSigHaveExpectedInputRedeemData(
                releaseTransaction.getWitness(inputIndex),
                releaseTransaction.getInput(inputIndex),
                redeemScript
            );

            assertReleaseWasSettled(
                host,
                bridgeStorageProvider,
                host.logs(),
                host.blockNumber(),
                tx.getHash(),
                releaseTransaction,
                outpointValues,
                totalAmountRequested
            );
        }

        @Test
        void pegoutsFlow_fromAReleaseRequest_toThePegoutChangeBeingCorrectlyRegistered_whenSegwitFed() throws Exception {
            // arrange
            // we need to recreate the federators keys to have the priv keys for signing
            List<BtcECKey> membersBtcPublicKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(new String[]{
                "member01", "member02", "member03", "member04", "member05", "member06", "member07", "member08", "member09", "member10",
                "member11", "member12", "member13", "member14", "member15", "member16", "member17", "member18", "member19", "member20"
            }, true);
            activeFederation = P2shP2wshErpFederationBuilder.builder()
                .withMembersBtcPublicKeys(membersBtcPublicKeys)
                .build();
            federationStorageProvider.setNewFederation(activeFederation);
            setUpReleaseRequests();

            host.blockNumber(pegoutTxIndexActivationHeight);
            setUpBridgeSupport();

            // call update collections so release requests are moved to pegouts wfc structure
            bridgeSupport.updateCollections(tx);
            bridgeSupport.save();

            // get release from pegouts wfc
            BtcTransaction releaseTransaction = getReleaseFromPegoutsWFC(bridgeStorageProvider);
            // assert release transaction was created as expected
            assertReleaseWasSettled(
                host,
                bridgeStorageProvider,
                host.logs(),
                host.blockNumber(),
                tx.getHash(),
                releaseTransaction,
                outpointValues,
                totalAmountRequested
            );
            assertWitnessAndScriptSigHaveExpectedInputRedeemData(
                releaseTransaction.getWitness(0),
                releaseTransaction.getInput(0),
                activeFederation.getRedeemScript()
            );

            // advance blockchain so pegouts have enough confirmations
            var blockNumber = host.blockNumber() + bridgeMainNetConstants.getRsk2BtcMinimumAcceptableConfirmations();
            host.blockNumber(blockNumber);
            updateBridgeSupport();

            // call update collections so pegouts are moved from wfc to wfs
            bridgeSupport.updateCollections(tx);
            bridgeSupport.save();

            // get pegout from pegouts wfs
            SortedMap<Hash, BtcTransaction> pegoutsWFS = bridgeStorageProvider.getPegoutsWaitingForSignatures();
            assertEquals(1, pegoutsWFS.size());
            Hash rskTxHash = pegoutsWFS.firstKey();
            BtcTransaction pegoutWFS = pegoutsWFS.get(rskTxHash);

            // advance blockchain to start signing pegout
            var newBlockNumber = host.blockNumber() + 1;
            host.blockNumber(newBlockNumber);
            updateBridgeSupport();

            List<BtcECKey> signers = membersBtcPublicKeys.subList(0, activeFederation.getNumberOfSignaturesRequired());
            // sign with federators
            List<Sha256Hash> sigHashes = generateTransactionInputsSigHashes(pegoutWFS);
            for (BtcECKey federatorSignerKey : signers) {
                List<byte[]> signatures = generateSignerEncodedSignatures(federatorSignerKey, sigHashes);
                bridgeSupport.addSignature(federatorSignerKey, signatures, rskTxHash);
            }

            // assert federators signed and release was made
            for (BtcECKey federatorSignerKey : signers) {
                assertFederatorSigning(rskTxHash.getBytes().toArrayUnsafe(), pegoutWFS, sigHashes, activeFederation, federatorSignerKey, host.logs());
            }
            assertLogReleaseBtc(host.logs(), pegoutWFS, rskTxHash);

            int activeFedUtxosSizeBeforeRegisteringChange = federationSupport.getActiveFederationBtcUTXOs().size();
            // register release change utxo
            int releaseTxBlockNumber = (int) host.blockNumber();
            setUpForTransactionRegistration(pegoutWFS, releaseTxBlockNumber);

            bridgeSupport.registerBtcTransaction(
                tx,
                pegoutWFS.bitcoinSerialize(),
                releaseTxBlockNumber,
                pmtWithTransactions.bitcoinSerialize()
            );

            // assert utxo was registered
            assertEquals(activeFedUtxosSizeBeforeRegisteringChange + 1, federationSupport.getActiveFederationBtcUTXOs().size());

            assertTransactionWasProcessed(bridgeStorageProvider, pegoutWFS.getHash(), releaseTxBlockNumber);
        }

        private void updateBridgeSupport() {
            bridgeSupport = bridgeSupportBuilder
                .withBridgeConstants(bridgeMainNetConstants)
                .withHost(host)
                .withProvider(bridgeStorageProvider)
                .withBtcBlockStoreFactory(btcBlockStoreFactory)
                .withFederationSupport(federationSupport)
                .withFeePerKbSupport(feePerKbSupport)
                .build();
        }

        private void assertReleaseOutpointsValuesWereNotSavedInStorage(BtcTransaction releaseTransaction) {
            byte[] actualReleaseOutpointsValues = host.getStorage(getStorageKeyForReleaseOutpointsValues(releaseTransaction.getHash()));
            assertNull(actualReleaseOutpointsValues);
        }

        @Test
        void registerBtcTransaction_forRefundableLegacyPeginFromMultisig_processReleaseTransactionsInfo() throws Exception {
            // arrange
            setUpBridgeSupport();
            Coin amountToSend = Coin.FIFTY_COINS.multiply(10);
            BtcTransaction pegin = arrangeLegacyPeginFromMultiSigToActiveFed(amountToSend);

            // act
            bridgeSupport.registerBtcTransaction(
                tx,
                pegin.bitcoinSerialize(),
                pegoutTxIndexActivationHeight,
                pmtWithTransactions.bitcoinSerialize()
            );
            bridgeSupport.save();

            // assert
            BtcTransaction releaseTransaction = getReleaseFromPegoutsWFC(bridgeStorageProvider);
            assertReleaseRejectionWasSettled(
                host,
                bridgeStorageProvider,
                host.logs(),
                host.blockNumber(),
                tx.getHash(),
                releaseTransaction,
                List.of(amountToSend),
                amountToSend
            );
        }

        private BtcTransaction arrangeLegacyPeginFromMultiSigToActiveFed(Coin amountToSend) throws Exception {
            BtcTransaction pegin = createLegacyPeginFromMultiSigToActiveFed(amountToSend);
            setUpForTransactionRegistration(pegin, pegoutTxIndexActivationHeight);

            return pegin;
        }

        private BtcTransaction createLegacyPeginFromMultiSigToActiveFed(Coin amountToSend) {
            BtcTransaction pegin = new BtcTransaction(btcMainnetParams);
            List<BtcECKey> pubKeys = Arrays.asList(
                getBtcEcKeyFromSeed("legacy_pegin_p2sh_multisig_key_1"),
                getBtcEcKeyFromSeed("legacy_pegin_p2sh_multisig_key_2")
            );
            Script redeemScript = ScriptBuilder.createRedeemScript(2, pubKeys);
            Script scriptSig = BitcoinUtils.createBaseInputScriptThatSpendsFromRedeemScript(redeemScript);
            pegin.addInput(BitcoinTestUtils.createHash(1), 0, scriptSig);
            pegin.addOutput(amountToSend, activeFederation.getAddress());

            return pegin;
        }
    }

    private void setUpForTransactionRegistration(BtcTransaction btcTx, int btcTxToRegisterBlockNumber) throws Exception {
        pmtWithTransactions = createValidPmtForTransactions(List.of(btcTx), btcMainnetParams);
        var chainHeight = btcTxToRegisterBlockNumber + bridgeMainNetConstants.getBtc2RskMinimumAcceptableConfirmations();

        BtcBlockStoreWithCache btcBlockStore = btcBlockStoreFactory.newInstance(host, bridgeMainNetConstants, bridgeStorageProvider);
        recreateChainFromPmt(btcBlockStore, chainHeight, pmtWithTransactions, btcTxToRegisterBlockNumber, btcMainnetParams);
        bridgeStorageProvider.save();
    }

    @Test
    void migrating_many_utxos_works_even_utxos_distribution() throws IOException {
        int utxosToCreate = 400;
        int expectedTransactions = (int) Math.ceil((double) utxosToCreate / bridgeConstantsRegtest.getMaxInputsPerPegoutTransaction());
        test_migrating_many_utxos(true, utxosToCreate, expectedTransactions);
    }

    @Test
    void migrating_many_utxos_works_uneven_utxos_distribution() throws IOException {
        int utxosToCreate = 410;
        int expectedTransactions = (int) Math.ceil((double) utxosToCreate / bridgeConstantsRegtest.getMaxInputsPerPegoutTransaction());
        test_migrating_many_utxos(true, utxosToCreate, expectedTransactions);
    }

    private void test_migrating_many_utxos(boolean isRskip294Active, int utxosToCreate, int expectedTransactions) throws IOException {

        List<FederationMember> oldFedMembers = new ArrayList<>();
        int oldFedMembersAmount = 13;
        for (int i = 0; i < oldFedMembersAmount; i++) {
            oldFedMembers.add(FederationMember.getFederationMemberFromKey(new BtcECKey()));
        }

        FederationArgs oldFedArgs = new FederationArgs(oldFedMembers,
            Instant.now(),
            0,
            btcRegTestParams
        );
        Federation oldFed = FederationFactory.buildStandardMultiSigFederation(oldFedArgs);

        List<FederationMember> newFedMembers = Arrays.asList(
            FederationMember.getFederationMemberFromKey(new BtcECKey()),
            FederationMember.getFederationMemberFromKey(new BtcECKey()),
            FederationMember.getFederationMemberFromKey(new BtcECKey())
        );
        FederationArgs newFedArgs = new FederationArgs(newFedMembers, Instant.now(), 1, btcRegTestParams);
        Federation newFed = FederationFactory.buildStandardMultiSigFederation(newFedArgs);

        // Set block right after the migration should start
        long blockNumber = newFed.getCreationBlockNumber() +
            bridgeConstantsRegtest.getFederationConstants().getFederationActivationAge() +
            bridgeConstantsRegtest.getFederationConstants().getFundsMigrationAgeSinceActivationBegin() +
            1;
        host.blockNumber(blockNumber);

        List<UTXO> utxosToMigrate = new ArrayList<>();
        for (int i = 0; i < utxosToCreate; i++) {
            utxosToMigrate.add(new UTXO(
                BitcoinTestUtils.createHash(i),
                0,
                Coin.FIFTY_COINS.multiply(10),
                0,
                false,
                oldFed.getP2SHScript())
            );
        }

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = new PegoutsWaitingForConfirmations(Collections.emptySet());

        BridgeStorageProvider bridgeStorageProvider = mock(BridgeStorageProvider.class);
        when(bridgeStorageProvider.getPegoutsWaitingForConfirmations()).thenReturn(pegoutsWaitingForConfirmations);
        when(bridgeStorageProvider.getReleaseRequestQueue()).thenReturn(new ReleaseRequestQueue(new ArrayList<>()));

        FederationStorageProvider federationStorageProviderMock = mock(FederationStorageProvider.class);
        when(federationStorageProviderMock.getNewFederation(any())).thenReturn(newFed);
        when(federationStorageProviderMock.getOldFederation(any())).thenReturn(oldFed);
        when(federationStorageProviderMock.getOldFederationBtcUTXOs()).thenReturn(utxosToMigrate);

        feePerKbSupport = mock(FeePerKbSupport.class);
        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.MILLICOIN);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProviderMock)
            .withHost(host)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(bridgeStorageProvider)
            .withHost(host)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .build();

        // Ensure a new transaction is created after each call to updateCollections
        // until the expected number is reached
        for (int i = 0; i < expectedTransactions; i++) {
            bridgeSupport.updateCollections(PegTestUtils.callWithHash(Hash.ZERO));
            assertEquals(i + 1, pegoutsWaitingForConfirmations.getEntries().size());
        }
        assertTrue(utxosToMigrate.isEmpty()); // Migrated UTXOs are removed from the list

        // Assert inputs size of each transaction
        List<Integer> expectedInputSizes = new ArrayList<>();
        int remainingUtxos = utxosToCreate;
        while (remainingUtxos > 0) {
            int expectedSize;
            if (isRskip294Active) {
                int maxInputsPerTransaction = bridgeConstantsRegtest.getMaxInputsPerPegoutTransaction();
                expectedSize = Math.min(remainingUtxos, maxInputsPerTransaction);
            } else {
                expectedSize = remainingUtxos;
                while (expectedSize > 100) { // Input size is limited to 100, see bitcoinj-thin
                    expectedSize = (int) Math.ceil((double) expectedSize / 2);
                }
            }
            expectedInputSizes.add(expectedSize);
            remainingUtxos -= expectedSize;
        }

        pegoutsWaitingForConfirmations.getEntries().forEach(e -> {
            Integer inputsSize = e.getBtcTransaction().getInputs().size();
            expectedInputSizes.remove(inputsSize);
        });
        assertEquals(0, expectedInputSizes.size());
        assertTrue(expectedInputSizes.isEmpty()); // All expected sizes should have been found and removed
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("test migration transaction between different types of feds")
    class MigrationTransaction {
        Coin value1 = Coin.valueOf(1_000_000);
        Coin value2 = Coin.valueOf(500_000);
        Coin value3 = Coin.valueOf(300_000);
        List<Coin> utxos = Arrays.asList(value1, value2, value3);
        Coin totalAmountRequested = value1.add(value2).add(value3);
        List<UTXO> retiringFederationUTXOs;
        long blockNumber;

        private void setUp(Federation retiringFederation, Federation activeFederation) {
            federationStorageProvider.setOldFederation(retiringFederation);
            retiringFederationUTXOs = Arrays.asList(
                BitcoinTestUtils.createUTXO(1, 0, utxos.get(0), retiringFederation.getAddress()),
                BitcoinTestUtils.createUTXO(2, 0, utxos.get(1), retiringFederation.getAddress()),
                BitcoinTestUtils.createUTXO(3, 0, utxos.get(2), retiringFederation.getAddress())
            );
            federationStorageProvider.getOldFederationBtcUTXOs().addAll(retiringFederationUTXOs);

            federationStorageProvider.setNewFederation(activeFederation);

            blockNumber = federationConstantsMainnet.getFederationActivationAge() + federationConstantsMainnet.getFundsMigrationAgeSinceActivationBegin() + activeFederation.getCreationBlockNumber() + 1;
            host.blockNumber(blockNumber);
            federationSupport = FederationSupportBuilder.builder()
                .withFederationConstants(federationConstantsMainnet)
                .withFederationStorageProvider(federationStorageProvider)
                .withHost(host)
                .build();

            BridgeEventLogger bridgeEventLogger = new BridgeEventLoggerImpl(bridgeMainNetConstants, host);

            bridgeSupport = bridgeSupportBuilder
                .withBridgeConstants(bridgeMainNetConstants)
                .withHost(host)
                .withProvider(bridgeStorageProvider)
                .withBtcBlockStoreFactory(btcBlockStoreFactory)
                .withFederationSupport(federationSupport)
                .withFeePerKbSupport(feePerKbSupport)
                .withEventLogger(bridgeEventLogger)
                .build();
        }

        @ParameterizedTest
        @MethodSource("federationArgs")
        void migration_fromLegacyRetiring_toLegacyActiveFed(Federation retiringFederation, Federation activeFederation) throws IOException {
            // arrange
            setUp(retiringFederation, activeFederation);

            bridgeSupport.updateCollections(tx);
            bridgeSupport.save();

            BtcTransaction migrationTransaction = getReleaseFromPegoutsWFC(bridgeStorageProvider);
            assertReleaseWasSettled(
                host,
                bridgeStorageProvider,
                host.logs(),
                blockNumber,
                tx.getHash(),
                migrationTransaction,
                utxos,
                totalAmountRequested
            );
        }

        private static Stream<Arguments> federationArgs() {
            Federation legacyRetiringFederation = P2shErpFederationBuilder.builder().build();
            List<BtcECKey> legacyActiveMembersBtcKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
                new String[]{"active01", "active02", "active03", "active04", "active05", "active06", "active07", "active08", "active09"},
                true
            );
            Federation legacyActiveFederation = P2shErpFederationBuilder.builder()
                .withMembersBtcPublicKeys(legacyActiveMembersBtcKeys)
                .build();

            Federation segwitActiveFederation = P2shP2wshErpFederationBuilder.builder()
                .build();

            List<BtcECKey> segwitRetiringMembersBtcKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(new String[]{
                    "retiring01", "retiring02", "retiring03", "retiring04", "retiring05", "retiring06", "retiring07", "retiring08", "retiring09", "retiring10",
                    "retiring11", "retiring12", "retiring13", "retiring14", "retiring15", "retiring16", "retiring17", "retiring18", "retiring19", "retiring20"
                },
                true
            );
            Federation segwitRetiringFederation = P2shP2wshErpFederationBuilder.builder()
                .withMembersBtcPublicKeys(segwitRetiringMembersBtcKeys)
                .build();

            return Stream.of(
                Arguments.of(legacyRetiringFederation, legacyActiveFederation),
                Arguments.of(legacyRetiringFederation, segwitActiveFederation),
                Arguments.of(segwitRetiringFederation, segwitActiveFederation)
            );
        }

    }

    @Test
    void getNextPegoutCreationBlockNumber() {

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        when(provider.getNextPegoutHeight()).thenReturn(Optional.of(10L));

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withProvider(provider)
            .build();

        assertEquals(10L, bridgeSupport.getNextPegoutCreationBlockNumber());
    }

    @Test
    void getQueuedPegoutsCount() throws IOException {

        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        when(provider.getReleaseRequestQueueSize()).thenReturn(2);

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withProvider(provider)
            .build();

        assertEquals(2, bridgeSupport.getQueuedPegoutsCount());
    }

    private static Stream<Arguments> getEstimatedFeesForNextPegOutEventArgsProvider_forSegwit(BridgeConstants bridgeConstants) {

        FederationConstants federationConstants = bridgeConstants.getFederationConstants();
        List<BtcECKey> erpFedPubKeys = federationConstants.getErpFedPubKeysList();

        ErpFederation p2shFed = P2shErpFederationBuilder.builder()
            .withCreationTime(Instant.now())
            .withCreationBlockNumber(1L)
            .withNetworkParameters(bridgeConstants.getBtcParams())
            .withErpPublicKeys(erpFedPubKeys)
            .withErpActivationDelay(federationConstants.getErpFedActivationDelay())
            .build();

        ErpFederation p2shP2wshFed = P2shP2wshErpFederationBuilder.builder()
            .withCreationTime(Instant.now())
            .withCreationBlockNumber(1L)
            .withNetworkParameters(bridgeConstants.getBtcParams())
            .withErpPublicKeys(erpFedPubKeys)
            .withErpActivationDelay(federationConstants.getErpFedActivationDelay())
            .build();

        ReleaseRequestQueue.Entry pegoutRequest1 = new ReleaseRequestQueue.Entry(getBtcEcKeyFromSeed("1").toAddress(bridgeConstants.getBtcParams()), Coin.valueOf(1, 0));
        ReleaseRequestQueue.Entry pegoutRequest2 = new ReleaseRequestQueue.Entry(getBtcEcKeyFromSeed("2").toAddress(bridgeConstants.getBtcParams()), Coin.valueOf(2, 0));
        ReleaseRequestQueue.Entry pegoutRequest3 = new ReleaseRequestQueue.Entry(getBtcEcKeyFromSeed("3").toAddress(bridgeConstants.getBtcParams()), Coin.valueOf(3, 0));

        ReleaseRequestQueue.Entry bigPegoutRequest = new ReleaseRequestQueue.Entry(getBtcEcKeyFromSeed("4").toAddress(bridgeConstants.getBtcParams()), Coin.valueOf(10, 0));

        UTXO p2shFedUtxo1 = createUTXO(Coin.valueOf(8, 0), p2shFed.getAddress());
        UTXO p2shFedBigUtxoUtxo = createUTXO(Coin.valueOf(13, 0), p2shFed.getAddress());

        UTXO p2shP2wshFedUtxo1 = createUTXO(Coin.valueOf(8, 0), p2shP2wshFed.getAddress());
        UTXO p2shP2wshFedBigUtxo = createUTXO(Coin.valueOf(13, 0), p2shP2wshFed.getAddress());

        return Stream.of(
            // active fed is p2sh and there are 0 pegout requests
            Arguments.of(
                p2shFed,
                Coin.valueOf(bridgeConstants instanceof BridgeMainNetConstants? 9490L: 9480L),
                List.of(),
                List.of(p2shFedUtxo1)
            ),
            // active fed is p2sh and there are 1 pegout requests
            // when there are no utxos available, the old logic should be executed and return the expected fee
            Arguments.of(
                p2shFed,
                Coin.valueOf(bridgeConstants instanceof BridgeMainNetConstants? 17940L: 17920L),
                List.of(pegoutRequest1),
                List.of()
            ),
            // active fed is p2sh and there is 1 pegout request
            // when there are utxos available, the new logic is used.
            Arguments.of(
                p2shFed,
                Coin.valueOf(bridgeConstants instanceof BridgeMainNetConstants? 9830L: 9820L),
                List.of(pegoutRequest1),
                List.of(p2shFedUtxo1)
            ),
            // active fed is p2sh and there are 2 pegout requests
            Arguments.of(
                p2shFed,
                Coin.valueOf(bridgeConstants instanceof BridgeMainNetConstants? 10170L: 10160L),
                List.of(pegoutRequest1, pegoutRequest2),
                List.of(p2shFedUtxo1)
            ),
            // active fed is p2sh and there are 3 pegout requests
            Arguments.of(
                p2shFed,
                Coin.valueOf(bridgeConstants instanceof BridgeMainNetConstants? 10510L: 10500L),
                List.of(pegoutRequest1, pegoutRequest2, pegoutRequest3),
                List.of(p2shFedUtxo1)
            ),

            // 2 inputs
            // active fed is p2sh and there is 2 pegout requests
            Arguments.of(
                p2shFed,
                Coin.valueOf(bridgeConstants instanceof BridgeMainNetConstants? 18900L: 18880L),
                List.of(pegoutRequest1, bigPegoutRequest),
                List.of(p2shFedUtxo1, p2shFedBigUtxoUtxo)
            ),
            // active fed is p2sh and there are 2 pegout requests
            Arguments.of(
                p2shFed,
                Coin.valueOf(bridgeConstants instanceof BridgeMainNetConstants? 19240L: 19220L),
                List.of(pegoutRequest1, pegoutRequest2, bigPegoutRequest),
                List.of(p2shFedUtxo1, p2shFedBigUtxoUtxo)
            ),
            // active fed is p2sh and there are 3 pegout requests
            Arguments.of(
                p2shFed,
                Coin.valueOf(bridgeConstants instanceof BridgeMainNetConstants? 19580L: 19560L),
                List.of(pegoutRequest1, pegoutRequest2, pegoutRequest3, bigPegoutRequest),
                List.of(p2shFedUtxo1, p2shFedBigUtxoUtxo)
            ),
            // active fed is p2sh p2wsh and there are 0 pegout requests
            Arguments.of(
                p2shP2wshFed,
                Coin.valueOf(5670L), // Savings: 59.75% for mainnet. 58.57% for regtest.
                List.of(),
                List.of(p2shP2wshFedUtxo1)
            ),
            // active fed is p2sh p2wsh and there is 1 pegout requests
            // when there are no utxos available, it will call `calculatePegoutTxSize` and return the expected value for a segwit federation
            Arguments.of(
                p2shP2wshFed,
                Coin.valueOf(bridgeConstants instanceof BridgeMainNetConstants? 9580L: 9570L),
                List.of(pegoutRequest1),
                List.of()
            ),
            // active fed is p2sh p2wsh and there is 1 pegout requests
            Arguments.of(
                p2shP2wshFed,
                Coin.valueOf(6010L), // Savings: 61.12% for mainnet. 59.94% for regtest.
                List.of(pegoutRequest1),
                List.of(p2shP2wshFedUtxo1)
            ),
            // active fed is p2sh p2wsh and there are 2 pegout requests
            Arguments.of(
                p2shP2wshFed,
                Coin.valueOf(6350L), // Savings: 62.42% for mainnet. 61.24% for regtest.
                List.of(pegoutRequest1, pegoutRequest2),
                List.of(p2shP2wshFedUtxo1)
            ),
            // active fed is p2sh p2wsh and there are 3 pegout requests
            Arguments.of(
                p2shP2wshFed,
                Coin.valueOf(6690L), // Savings: 63.66% for mainnet. 62.45% for regtest.
                List.of(pegoutRequest1, pegoutRequest2, pegoutRequest3),
                List.of(p2shP2wshFedUtxo1)
            ),

            // 2 inputs
            // active fed is segwit and there is 1 pegout requests
            Arguments.of(
                p2shP2wshFed,
                Coin.valueOf(11260L), // Savings: 59.58% for mainnet. 58.45% for regtest.
                List.of(pegoutRequest1, bigPegoutRequest),
                List.of(p2shP2wshFedUtxo1, p2shP2wshFedBigUtxo)
            ),
            // active fed is p2sh p2wsh and there are 2 pegout requests
            Arguments.of(
                p2shP2wshFed,
                Coin.valueOf(11600L), // Savings: 60.29% for mainnet. 59.13% for regtest.
                List.of(pegoutRequest1, pegoutRequest2, bigPegoutRequest),
                List.of(p2shP2wshFedUtxo1, p2shP2wshFedBigUtxo)
            ),
            // active fed is p2sh p2wsh and there are 3 pegout requests
            Arguments.of(
                p2shP2wshFed,
                Coin.valueOf(11940L), // Savings: 60.97% for mainnet. 59.83% for regtest.
                List.of(pegoutRequest1, pegoutRequest2, pegoutRequest3, bigPegoutRequest),
                List.of(p2shP2wshFedUtxo1, p2shP2wshFedBigUtxo)
            )

        );
    }

    private static Stream<Arguments> getEstimatedFeesForNextPegOutEventArgsProvider_forSegwit() {

        BridgeRegTestConstants bridgeConstantsRegtest = new BridgeRegTestConstants();

        Stream<Arguments> postRskip305Regtest = getEstimatedFeesForNextPegOutEventArgsProvider_forSegwit(bridgeConstantsRegtest);

        BridgeMainNetConstants bridgeMainNetConstants = BridgeMainNetConstants.getInstance();

        Stream<Arguments> postRskip305Mainnet = getEstimatedFeesForNextPegOutEventArgsProvider_forSegwit(bridgeMainNetConstants);

        return Stream.of(
            postRskip305Regtest,
            postRskip305Mainnet
        ).flatMap(Function.identity());

    }

    @ParameterizedTest
    @MethodSource("getEstimatedFeesForNextPegOutEventArgsProvider_forSegwit")
    void getEstimatedFeesForNextPegOutEvent_forSegwit(
        Federation federation,
        Coin expectedEstimatedFee,
        List<ReleaseRequestQueue.Entry> releaseRequestQueueEntries,
        List<UTXO> utxos
    ) throws IOException {
        // Arrange
        BridgeStorageProvider provider = mock(BridgeStorageProvider.class);
        FederationStorageProvider federationStorageProviderMock = mock(FederationStorageProvider.class);

        ReleaseRequestQueue releaseRequestQueue = new ReleaseRequestQueue(releaseRequestQueueEntries);

        when(provider.getReleaseRequestQueue()).thenReturn(releaseRequestQueue);
        when(federationStorageProviderMock.getNewFederation(any())).thenReturn(federation);

        when(federationStorageProviderMock.getNewFederationBtcUTXOs()).thenReturn(utxos);

        feePerKbSupport = mock(FeePerKbSupport.class);
        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.valueOf(10_000));

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationConstantsRegtest)
            .withFederationStorageProvider(federationStorageProviderMock)
            .build();

        BridgeSupport bridgeSupport = bridgeSupportBuilder
            .withProvider(provider)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .withBridgeConstants(bridgeConstantsRegtest)
            .build();

        // Act
        Coin estimatedFeesForNextPegOutEvent = bridgeSupport.getEstimatedFeesForNextPegOutEvent();

        // Assert
        assertEquals(expectedEstimatedFee, estimatedFeesForNextPegOutEvent);
    }

    private BridgeSupport getBridgeSupport(BridgeConstants constants, BridgeStorageProvider provider, BridgeHost host,
        BtcLockSenderProvider btcLockSenderProvider, PeginInstructionsProvider peginInstructionsProvider,
        BtcBlockStoreWithCache.Factory blockStoreFactory) {

        if (btcLockSenderProvider == null) {
            btcLockSenderProvider = mock(BtcLockSenderProvider.class);
        }
        if (peginInstructionsProvider == null) {
            peginInstructionsProvider = mock(PeginInstructionsProvider.class);
        }
        if (blockStoreFactory == null) {
            blockStoreFactory = mock(BtcBlockStoreWithCache.Factory.class);
        }
        feePerKbSupport = mock(FeePerKbSupport.class);
        federationSupport = mock(FederationSupport.class);

        return new BridgeSupport(
            constants,
            provider,
            mock(BridgeEventLogger.class),
            btcLockSenderProvider,
            peginInstructionsProvider,
            host,
            new Context(constants.getBtcParams()),
            feePerKbSupport,
            federationSupport,
            lockingCapSupport,
            blockStoreFactory
        );
    }

    private BridgeSupport getBridgeSupportConfiguredToTestReceiveHeader(
        BtcBlock btcBlock,
        BtcBlockStoreWithCache btcBlockStore,
        BridgeStorageProvider provider,
        StoredBlock storedBlock
    ) throws BlockStoreException {

        doReturn(10).when(storedBlock).getHeight();

        BtcBlock btcBlock2 = mock(BtcBlock.class);
        doReturn(BitcoinTestUtils.createHash(1)).when(btcBlock2).getHash();
        doReturn(btcBlock2).when(storedBlock).getHeader();

        doReturn(storedBlock).when(btcBlockStore).getChainHead();

        BtcBlockStoreWithCache.Factory mockFactory = mock(BtcBlockStoreWithCache.Factory.class);
        when(mockFactory.newInstance(any(), any(), any())).thenReturn(btcBlockStore);

        when(btcBlock.getPrevBlockHash()).thenReturn(Sha256Hash.ZERO_HASH);
        when(btcBlockStore.get(Sha256Hash.ZERO_HASH)).thenReturn(storedBlock);

        host.blockTimestamp(1611169584L);

        return bridgeSupportBuilder
            .withBridgeConstants(bridgeConstantsRegtest)
            .withProvider(provider)
            .withHost(host)
            .withBtcBlockStoreFactory(mockFactory)
            .build();
    }

    private long getBlockHeightInFundsMigrationAge(long federationCreationBlockNumber) {
        long federationActivationAge = federationConstantsMainnet.getFederationActivationAge();
        return federationCreationBlockNumber
            + federationActivationAge
            + federationConstantsMainnet.getFundsMigrationAgeSinceActivationBegin()
            + 1;
    }

    private BtcLockSenderProvider getBtcLockSenderProvider(BtcLockSender.TxSenderAddressType txSenderAddressType, Address btcAddress, org.hyperledger.besu.datatypes.Address rskAddress) {
        BtcLockSender btcLockSender = mock(BtcLockSender.class);
        when(btcLockSender.getTxSenderAddressType()).thenReturn(txSenderAddressType);
        when(btcLockSender.getBTCAddress()).thenReturn(btcAddress);
        when(btcLockSender.getRskAddress()).thenReturn(rskAddress);

        BtcLockSenderProvider btcLockSenderProvider = mock(BtcLockSenderProvider.class);
        when(btcLockSenderProvider.tryGetBtcLockSender(any())).thenReturn(Optional.of(btcLockSender));

        return btcLockSenderProvider;
    }

    private PeginInstructionsProvider getPeginInstructionsProviderForVersion1(org.hyperledger.besu.datatypes.Address rskDestinationAddress, Optional<Address> btcRefundAddress)
        throws PeginInstructionsException {
        PeginInstructionsVersion1 peginInstructions = mock(PeginInstructionsVersion1.class);
        when(peginInstructions.getProtocolVersion()).thenReturn(1);
        when(peginInstructions.getRskDestinationAddress()).thenReturn(rskDestinationAddress);
        when(peginInstructions.getBtcRefundAddress()).thenReturn(btcRefundAddress);

        PeginInstructionsProvider peginInstructionsProvider = mock(PeginInstructionsProvider.class);
        when(peginInstructionsProvider.buildPeginInstructions(any())).thenReturn(Optional.of(peginInstructions));

        return peginInstructionsProvider;
    }

    private FederationStorageProvider createFederationStorageProvider(BridgeHost host) {
        StorageAccessor bridgeStorageAccessor = new BridgeStorageAccessorImpl(host);
        return new FederationStorageProviderImpl(bridgeStorageAccessor);
    }
}
