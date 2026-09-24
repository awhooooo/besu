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
package co.rsk.peg.federation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.script.Script;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.federation.FederationMember.KeyType;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.federation.constants.FederationMainNetConstants;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.utils.PublicKeys;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import co.rsk.peg.storage.StorageAccessor;
import co.rsk.test.builders.FederationSupportBuilder;
import org.hyperledger.besu.datatypes.Hash;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class FederationSupportImplTest {

    private static final FederationConstants federationMainnetConstants = FederationMainNetConstants.getInstance();
    private final Federation genesisFederation = FederationTestUtils.getGenesisFederation(federationMainnetConstants);
    private final FederationSupportBuilder federationSupportBuilder = FederationSupportBuilder.builder();
    private Federation newFederation;
    private StorageAccessor storageAccessor;
    private FederationStorageProvider storageProvider;
    private FederationSupport federationSupport;

    @BeforeEach
    void setUp() {
        storageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
        storageProvider = new FederationStorageProviderImpl(storageAccessor);
        federationSupport = federationSupportBuilder
            .withFederationConstants(federationMainnetConstants)
            .withFederationStorageProvider(storageProvider)
            .build();
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("null new and old federations")
    class ActiveFederationTestsWithNullFederations {
        @BeforeEach
        void setUp() {
            storageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
            storageProvider = new FederationStorageProviderImpl(storageAccessor);

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .build();
        }

        @Test
        @Tag("getActiveFederation")
        void getActiveFederation_returnsGenesisFederation() {
            Federation activeFederation = federationSupport.getActiveFederation();
            assertEquals(genesisFederation, activeFederation);
        }

        @Test
        @Tag("getActiveFederationRedeemScript")
        void getActiveFederationRedeemScript_returnsGenesisFederationRedeemScript() {
            Optional<Script> activeFederationRedeemScript = federationSupport.getActiveFederationRedeemScript();
            assertTrue(activeFederationRedeemScript.isPresent());
            assertEquals(genesisFederation.getRedeemScript(), activeFederationRedeemScript.get());
        }

        @Test
        @Tag("getActiveFederationAddress")
        void getActiveFederationAddress_returnsGenesisFederationAddress() {
            Address activeFederationAddress = federationSupport.getActiveFederationAddress();
            assertEquals(genesisFederation.getAddress(), activeFederationAddress);
        }

        @Test
        @Tag("getActiveFederationSize")
        void getActiveFederationSize_returnsGenesisFederationSize() {
            int activeFederationSize = federationSupport.getActiveFederationSize();
            assertEquals(genesisFederation.getSize(), activeFederationSize);
        }

        @Test
        @Tag("getActiveFederationThreshold")
        void getActiveFederationThreshold_returnsGenesisFederationThreshold() {
            int activeFederationThreshold = federationSupport.getActiveFederationThreshold();
            assertEquals(genesisFederation.getNumberOfSignaturesRequired(), activeFederationThreshold);
        }

        @Test
        @Tag("getActiveFederationCreationTime")
        void getActiveFederationCreationTime_returnsGenesisFederationCreationTime() {
            Instant activeFederationCreationTime = federationSupport.getActiveFederationCreationTime();
            assertEquals(genesisFederation.getCreationTime(), activeFederationCreationTime);
        }

        @Test
        @Tag("getActiveFederationCreationBlockNumber")
        void getActiveFederationCreationBlockNumber_returnsGenesisFederationCreationBlockNumber() {
            long activeFederationCreationBlockNumber = federationSupport.getActiveFederationCreationBlockNumber();
            assertEquals(genesisFederation.getCreationBlockNumber(), activeFederationCreationBlockNumber);
        }

        @Test
        @Tag("getActiveFederatorPublicKeyOfType")
        void getActiveFederatorPublicKeyOfType_withNegativeIndex_throwsIndexOutOfBoundsException() {
            assertThrows(IndexOutOfBoundsException.class, () -> federationSupport.getActiveFederatorPublicKeyOfType(-1, KeyType.BTC));
        }

        @Test
        @Tag("getActiveFederatorPublicKeyOfType")
        void getActiveFederatorPublicKeyOfType_withIndexGreaterThanGenesisFederationSize_throwsIndexOutOfBoundsException() {
            int genesisFederationSize = genesisFederation.getSize();
            assertThrows(
                IndexOutOfBoundsException.class, () ->
                federationSupport.getActiveFederatorPublicKeyOfType(genesisFederationSize, KeyType.BTC)
            );
        }

        @Test
        @Tag("getActiveFederatorPublicKeyOfType")
        void getActiveFederatorPublicKeyOfType_returnsFederatorPublicKeysFromGenesisFederation() {
            BtcECKey federatorFromGenesisFederationBtcPublicKey = genesisFederation.getBtcPublicKeys().get(0);
            BtcECKey federatorFromGenesisFederationRskPublicKey = getRskPublicKeysFromFederationMembers(genesisFederation.getMembers()).get(0);
            BtcECKey federatorFromGenesisFederationMstPublicKey = getMstPublicKeysFromFederationMembers(genesisFederation.getMembers()).get(0);

            // since genesis federation was created without specifying rsk public keys
            // these are set deriving the btc public keys,
            // so we should first assert that
            BtcECKey ecKeyDerivedFromBtcKey = BtcECKey.fromPublicOnly(federatorFromGenesisFederationBtcPublicKey.getPubKey());
            assertEquals(ecKeyDerivedFromBtcKey, federatorFromGenesisFederationRskPublicKey);
            // since genesis federation was created without specifying mst public keys
            // these are set copying the rsk public keys,
            // so we should first assert that
            assertEquals(federatorFromGenesisFederationRskPublicKey, federatorFromGenesisFederationMstPublicKey);

            byte[] activeFederatorBtcPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.BTC);
            assertArrayEquals(federatorFromGenesisFederationBtcPublicKey.getPubKey(), activeFederatorBtcPublicKey);

            byte[] activeFederatorRskPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.RSK);
            assertArrayEquals(PublicKeys.compressed(federatorFromGenesisFederationRskPublicKey), activeFederatorRskPublicKey);

            byte[] activeFederatorMstPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.MST);
            assertArrayEquals(PublicKeys.compressed(federatorFromGenesisFederationMstPublicKey), activeFederatorMstPublicKey);
        }

        @Test
        @Tag("getActiveFederationBtcUTXOs")
        void getActiveFederationUTXOs_returnsGenesisFederationUTXOs() {
            List<UTXO> genesisFederationUTXOs = BitcoinTestUtils.createUTXOs(10, genesisFederation.getAddress());
            storageProvider.getNewFederationBtcUTXOs().addAll(genesisFederationUTXOs);

            List<UTXO> activeFederationUTXOs = federationSupport.getActiveFederationBtcUTXOs();
            assertEquals(genesisFederationUTXOs, activeFederationUTXOs);
        }

        @Test
        void getLiveFederations_returnsGenesisFederation() {
            List<Federation> liveFederations = federationSupport.getLiveFederations();

            assertEquals(1, liveFederations.size());
            assertEquals(genesisFederation, liveFederations.get(0));
        }

        @Test
        void getFederationContext() {
            FederationContext federationContext = federationSupport.getFederationContext();
            List<Federation> liveFederations = federationContext.getLiveFederations();

            assertEquals(genesisFederation, federationContext.getActiveFederation());
            assertTrue(federationContext.getRetiringFederation().isEmpty());
            assertTrue(federationContext.getLastRetiredFederationP2SHScript().isEmpty());

            assertEquals(1, liveFederations.size());
            assertEquals(genesisFederation, liveFederations.get(0));
        }
    }

    @Nested
    @Tag("null old federation, non null new federation")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ActiveFederationTestsWithNullOldFederation {
        @BeforeEach
        void setUp() {
            storageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
            storageProvider = new FederationStorageProviderImpl(storageAccessor);

            // create new federation
            newFederation = P2shErpFederationBuilder.builder().build();

            storageProvider.setNewFederation(newFederation);
            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .build();
        }

        @Test
        @Tag("getActiveFederation")
        void getActiveFederation_returnsNewFederation() {
            Federation activeFederation = federationSupport.getActiveFederation();
            assertEquals(newFederation, activeFederation);
        }

        @Test
        @Tag("getActiveFederationRedeemScript")
        void getActiveFederationRedeemScript_returnsNewFederationRedeemScript() {
            Optional<Script> activeFederationRedeemScript = federationSupport.getActiveFederationRedeemScript();
            assertTrue(activeFederationRedeemScript.isPresent());
            assertEquals(newFederation.getRedeemScript(), activeFederationRedeemScript.get());
        }

        @Test
        @Tag("getActiveFederationAddress")
        void getActiveFederationAddress_returnsNewFederationAddress() {
            Address activeFederationAddress = federationSupport.getActiveFederationAddress();
            assertEquals(newFederation.getAddress(), activeFederationAddress);
        }

        @Test
        @Tag("getActiveFederationSize")
        void getActiveFederationSize_returnsNewFederationSize() {
            int activeFederationSize = federationSupport.getActiveFederationSize();
            assertEquals(newFederation.getSize(), activeFederationSize);
        }

        @Test
        @Tag("getActiveFederationThreshold")
        void getActiveFederationThreshold_returnsNewFederationThreshold() {
            int activeFederationThreshold = federationSupport.getActiveFederationThreshold();
            assertEquals(newFederation.getNumberOfSignaturesRequired(), activeFederationThreshold);
        }

        @Test
        @Tag("getActiveFederationCreationTime")
        void getActiveFederationCreationTime_returnsNewFederationCreationTime() {
            Instant activeFederationCreationTime = federationSupport.getActiveFederationCreationTime();
            assertEquals(newFederation.getCreationTime(), activeFederationCreationTime);
        }

        @Test
        @Tag("getActiveFederationCreationBlockNumber")
        void getActiveFederationCreationBlockNumber_returnsNewFederationCreationBlockNumber() {
            long activeFederationCreationBlockNumber = federationSupport.getActiveFederationCreationBlockNumber();
            assertEquals(newFederation.getCreationBlockNumber(), activeFederationCreationBlockNumber);
        }

        @Test
        @Tag("getActiveFederatorPublicKeyOfType")
        void getActiveFederatorPublicKeyOfType_returnsFederatorFromNewFederationPublicKeys() {
            BtcECKey federatorFromNewFederationBtcPublicKey = newFederation.getBtcPublicKeys().get(0);
            BtcECKey federatorFromNewFederationRskPublicKey = getRskPublicKeysFromFederationMembers(newFederation.getMembers()).get(0);
            BtcECKey federatorFromNewFederationMstPublicKey = getMstPublicKeysFromFederationMembers(newFederation.getMembers()).get(0);

            // since new federation was created without specifying rsk public keys
            // these are set deriving the btc public keys,
            // so we should first assert that
            BtcECKey ecKeyDerivedFromBtcKey = BtcECKey.fromPublicOnly(federatorFromNewFederationBtcPublicKey.getPubKey());
            assertEquals(ecKeyDerivedFromBtcKey, federatorFromNewFederationRskPublicKey);
            // since new federation was created without specifying mst public keys
            // these are set copying the rsk public keys,
            // so we should first assert that
            assertEquals(federatorFromNewFederationRskPublicKey, federatorFromNewFederationMstPublicKey);

            byte[] activeFederatorBtcPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.BTC);
            assertArrayEquals(federatorFromNewFederationBtcPublicKey.getPubKey(), activeFederatorBtcPublicKey);

            byte[] activeFederatorRskPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.RSK);
            assertArrayEquals(PublicKeys.compressed(federatorFromNewFederationRskPublicKey), activeFederatorRskPublicKey);

            byte[] activeFederatorMstPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.MST);
            assertArrayEquals(PublicKeys.compressed(federatorFromNewFederationMstPublicKey), activeFederatorMstPublicKey);
        }

        @Test
        @Tag("getActiveFederatorPublicKeyOfType")
        void getActiveFederatorPublicKeyOfType_withSpecificRskKeys_returnsFederatorFromNewFederationPublicKeys() {
            // create new federation with specific rsk public keys
            List<BtcECKey> rskECKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
                new String[]{"rsk01", "rsk02", "rsk03", "rsk04", "rsk05", "rsk06", "rsk07", "rsk08", "rsk09"}, false
            );
            newFederation = P2shErpFederationBuilder.builder()
                .withMembersRskPublicKeys(rskECKeys)
                .build();
            storageProvider.setNewFederation(newFederation);

            BtcECKey federatorFromNewFederationBtcPublicKey = newFederation.getBtcPublicKeys().get(0);
            BtcECKey federatorFromNewFederationRskPublicKey = getRskPublicKeysFromFederationMembers(newFederation.getMembers()).get(0);
            BtcECKey federatorFromNewFederationMstPublicKey = getMstPublicKeysFromFederationMembers(newFederation.getMembers()).get(0);

            // since new federation was created without specifying mst public keys
            // these are set copying the rsk public keys,
            // so we should first assert that
            assertEquals(federatorFromNewFederationRskPublicKey, federatorFromNewFederationMstPublicKey);

            byte[] activeFederatorBtcPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.BTC);
            assertArrayEquals(federatorFromNewFederationBtcPublicKey.getPubKey(), activeFederatorBtcPublicKey);

            byte[] activeFederatorRskPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.RSK);
            assertArrayEquals(PublicKeys.compressed(federatorFromNewFederationRskPublicKey), activeFederatorRskPublicKey);

            byte[] activeFederatorMstPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.MST);
            assertArrayEquals(PublicKeys.compressed(federatorFromNewFederationMstPublicKey), activeFederatorMstPublicKey);
        }

        @Test
        @Tag("getActiveFederatorPublicKeyOfType")
        void getActiveFederatorPublicKeyOfType_withSpecificRskAndMstKeys_returnsFederatorFromNewFederationPublicKeys() {
            // create new federation with specific rsk and mst public keys
            List<BtcECKey> rskECKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
                new String[]{"rsk01", "rsk02", "rsk03", "rsk04", "rsk05", "rsk06", "rsk07", "rsk08", "rsk09"}, false
            );
            List<BtcECKey> mstECKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
                new String[]{"mst01", "mst02", "mst03", "mst04", "mst05", "mst06", "mst07", "mst08", "mst09"}, false
            );
            newFederation = P2shErpFederationBuilder.builder()
                .withMembersRskPublicKeys(rskECKeys)
                .withMembersMstPublicKeys(mstECKeys)
                .build();
            storageProvider.setNewFederation(newFederation);

            BtcECKey federatorFromNewFederationBtcPublicKey = newFederation.getBtcPublicKeys().get(0);
            BtcECKey federatorFromNewFederationRskPublicKey = getRskPublicKeysFromFederationMembers(newFederation.getMembers()).get(0);
            BtcECKey federatorFromNewFederationMstPublicKey = getMstPublicKeysFromFederationMembers(newFederation.getMembers()).get(0);

            byte[] activeFederatorBtcPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.BTC);
            assertArrayEquals(federatorFromNewFederationBtcPublicKey.getPubKey(), activeFederatorBtcPublicKey);

            byte[] activeFederatorRskPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.RSK);
            assertArrayEquals(PublicKeys.compressed(federatorFromNewFederationRskPublicKey), activeFederatorRskPublicKey);

            byte[] activeFederatorMstPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.MST);
            assertArrayEquals(PublicKeys.compressed(federatorFromNewFederationMstPublicKey), activeFederatorMstPublicKey);
        }

        @Test
        @Tag("getActiveFederationBtcUTXOs")
        void getActiveFederationUTXOs_returnsNewFederationUTXOs() {
            List<UTXO> newFederationUTXOs = BitcoinTestUtils.createUTXOs(10, newFederation.getAddress());
            storageProvider.getNewFederationBtcUTXOs().addAll(newFederationUTXOs);

            List<UTXO> activeFederationUTXOs = federationSupport.getActiveFederationBtcUTXOs();
            assertEquals(newFederationUTXOs, activeFederationUTXOs);
        }

        @Test
        void getLiveFederations_returnsActiveFederation() {
            List<Federation> liveFederations = federationSupport.getLiveFederations();

            assertEquals(1, liveFederations.size());
            assertEquals(newFederation, liveFederations.get(0));
        }

        @Test
        void getFederationContext() {
            FederationContext federationContext = federationSupport.getFederationContext();
            List<Federation> liveFederations = federationContext.getLiveFederations();

            assertEquals(newFederation, federationContext.getActiveFederation());
            assertTrue(federationContext.getRetiringFederation().isEmpty());
            assertTrue(federationContext.getLastRetiredFederationP2SHScript().isEmpty());

            assertEquals(1, liveFederations.size());
            assertEquals(newFederation, liveFederations.get(0));
        }
    }

    @Nested
    @Tag("non null new and old federations")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ActiveFederationTestsWithNonNullFederations {
        long oldFederationCreationBlockNumber = 20;
        Federation oldFederation = P2shErpFederationBuilder.builder()
            .withCreationBlockNumber(oldFederationCreationBlockNumber)
            .build();

        long newFederationCreationBlockNumber = 65;
        List<BtcECKey> newFederationKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
            new String[]{"fa01", "fa02", "fa03", "fa04", "fa05", "fa06", "fa07", "fa08", "fa09"}, true
        );
        Federation newFederation = P2shErpFederationBuilder.builder()
            .withMembersBtcPublicKeys(newFederationKeys)
            .withCreationBlockNumber(newFederationCreationBlockNumber)
            .build();

        // new federation should be active if we are past the activation block number
        // old federation should be active if we are before the activation block number
        long blockNumberFederationActivation = newFederationCreationBlockNumber + federationMainnetConstants.getFederationActivationAge();

        @BeforeEach
        void setUp() {
            // save federations in storage
            storageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
            storageProvider = new FederationStorageProviderImpl(storageAccessor);
            storageProvider.setOldFederation(oldFederation);
            storageProvider.setNewFederation(newFederation);

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .build();
        }

        @ParameterizedTest
        @Tag("getActiveFederation")
        @MethodSource("expectedFederationArgs")
        void getActiveFederation_returnsExpectedFederationAccordingToActivationAge(
            long currentBlock,
            Federation expectedFederation) {
            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            Federation activeFederation = federationSupport.getActiveFederation();
            assertEquals(expectedFederation, activeFederation);
        }

        private Stream<Arguments> expectedFederationArgs() {
            return Stream.of(
                Arguments.of(blockNumberFederationActivation - 1, oldFederation),
                Arguments.of(blockNumberFederationActivation, newFederation)
            );
        }

        @Test
        @Tag("getLiveFederations")
        void getLiveFederations_beforeFederationActivation_shouldOnlyReturnActiveFedLive() {
            long currentBlock = blockNumberFederationActivation - 1;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            List<Federation> liveFederations = federationSupport.getLiveFederations();

            assertEquals(1, liveFederations.size());
            assertEquals(oldFederation, liveFederations.get(0));
        }

        @Test
        @Tag("getFederationContext")
        void getFederationContext_beforeFederationActivation_shouldReturnFedContextWithOnlyActiveFed() {
            long currentBlock = blockNumberFederationActivation - 1;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            FederationContext federationContext = federationSupport.getFederationContext();

            assertEquals(oldFederation, federationContext.getActiveFederation());
            assertFalse(federationContext.getRetiringFederation().isPresent());
            assertFalse(federationContext.getLastRetiredFederationP2SHScript().isPresent());
        }

        @Test
        @Tag("getLiveFederations")
        void getLiveFederations_afterFederationActivation_shouldReturnActiveAndRetiringFedsLive() {
            long currentBlock = blockNumberFederationActivation;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            List<Federation> liveFederations = federationSupport.getLiveFederations();

            assertEquals(2, liveFederations.size());
            assertEquals(newFederation, liveFederations.get(0));
            assertEquals(oldFederation, liveFederations.get(1));
        }

        @Test
        @Tag("getFederationContext")
        void getFederationContext_afterFederationActivation_shouldReturnFedContextWithActiveAndRetiringFeds() {
            long currentBlock = blockNumberFederationActivation;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            FederationContext federationContext = federationSupport.getFederationContext();

            assertEquals(newFederation, federationContext.getActiveFederation());
            assertTrue(federationContext.getRetiringFederation().isPresent());
            assertEquals(oldFederation, federationContext.getRetiringFederation().get());
            assertTrue(federationContext.getLastRetiredFederationP2SHScript().isEmpty());
        }

        @ParameterizedTest
        @Tag("getActiveFederationRedeemScript")
        @MethodSource("expectedRedeemScriptArgs")
        void getActiveFederationRedeemScript_returnsExpectedRedeemScriptAccordingToActivationAge(
            long currentBlock,
            Script expectedRedeemScript) {
            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            Optional<Script> activeFederationRedeemScript = federationSupport.getActiveFederationRedeemScript();
            assertTrue(activeFederationRedeemScript.isPresent());
            assertEquals(expectedRedeemScript, activeFederationRedeemScript.get());
        }

        private Stream<Arguments> expectedRedeemScriptArgs() {
            return Stream.of(
                Arguments.of(blockNumberFederationActivation - 1, oldFederation.getRedeemScript()),
                Arguments.of(blockNumberFederationActivation, newFederation.getRedeemScript())
            );
        }

        @ParameterizedTest
        @Tag("getActiveFederationAddress")
        @MethodSource("expectedAddressArgs")
        void getActiveFederationAddress_returnsExpectedAddressAccordingToActivationAge(
            long currentBlock,
            Address expectedAddress) {
            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            Address activeFederationAddress = federationSupport.getActiveFederationAddress();
            assertEquals(expectedAddress, activeFederationAddress);
        }

        private Stream<Arguments> expectedAddressArgs() {
            return Stream.of(
                Arguments.of(blockNumberFederationActivation - 1, oldFederation.getAddress()),
                Arguments.of(blockNumberFederationActivation, newFederation.getAddress())
            );
        }

        @ParameterizedTest
        @Tag("getActiveFederationSize")
        @MethodSource("expectedSizeArgs")
        void getActiveFederationSize_returnsExpectedSizeAccordingToActivationAge(
            long currentBlock,
            int expectedSize
        ) {
            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            int activeFederationSize = federationSupport.getActiveFederationSize();
            assertEquals(expectedSize, activeFederationSize);
        }

        private Stream<Arguments> expectedSizeArgs() {
            return Stream.of(
                Arguments.of(blockNumberFederationActivation - 1, oldFederation.getSize()),
                Arguments.of(blockNumberFederationActivation, newFederation.getSize())
            );
        }

        @ParameterizedTest
        @Tag("getActiveFederationThreshold")
        @MethodSource("expectedThresholdArgs")
        void getActiveFederationThreshold_returnsExpectedThresholdAccordingToActivationAge(
            long currentBlock,
            int expectedThreshold) {
            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            int activeFederationThreshold = federationSupport.getActiveFederationThreshold();
            assertEquals(expectedThreshold, activeFederationThreshold);
        }

        private Stream<Arguments> expectedThresholdArgs() {
            return Stream.of(
                Arguments.of(blockNumberFederationActivation - 1, oldFederation.getNumberOfSignaturesRequired()),
                Arguments.of(blockNumberFederationActivation, newFederation.getNumberOfSignaturesRequired())
            );
        }

        @ParameterizedTest
        @Tag("getActiveFederationCreationTime")
        @MethodSource("expectedCreationTimeArgs")
        void getActiveFederationCreationTime_returnsExpectedCreationTimeAccordingToActivationAge(
            long currentBlock,
            Instant expectedCreationTime) {
            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            Instant activeFederationCreationTime = federationSupport.getActiveFederationCreationTime();
            assertEquals(expectedCreationTime, activeFederationCreationTime);
        }

        private Stream<Arguments> expectedCreationTimeArgs() {
            return Stream.of(
                Arguments.of(blockNumberFederationActivation - 1, oldFederation.getCreationTime()),
                Arguments.of(blockNumberFederationActivation, newFederation.getCreationTime())
            );
        }

        @ParameterizedTest
        @Tag("getActiveFederationCreationBlockNumber")
        @MethodSource("expectedCreationBlockNumberArgs")
        void getActiveFederationCreationBlockNumber_returnsExpectedCreationBlockNumberAccordingToActivationAge(
            long currentBlock,
            long expectedCreationBlockNumber) {
            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            long activeFederationCreationBlockNumber = federationSupport.getActiveFederationCreationBlockNumber();
            assertEquals(expectedCreationBlockNumber, activeFederationCreationBlockNumber);
        }

        private Stream<Arguments> expectedCreationBlockNumberArgs() {
            return Stream.of(
                Arguments.of(blockNumberFederationActivation - 1, oldFederation.getCreationBlockNumber()),
                Arguments.of(blockNumberFederationActivation, newFederation.getCreationBlockNumber())
            );
        }

        @ParameterizedTest
        @Tag("getActiveFederatorPublicKeyOfType")
        @MethodSource("expectedFederatorPublicKeyOfTypeArgs")
        void getActiveFederatorPublicKeyOfType_returnsExpectedFederatorPublicKeysAccordingToActivationAge(
            long currentBlock,
            BtcECKey expectedFederatorBtcPublicKey,
            BtcECKey expectedFederatorRskPublicKey,
            BtcECKey expectedFederatorMstPublicKey) {
            // since new federation was created without specifying rsk public keys
            // these are set deriving the btc public keys,
            // so we should first assert that
            BtcECKey ecKeyDerivedFromBtcKey = BtcECKey.fromPublicOnly(expectedFederatorBtcPublicKey.getPubKey());
            assertEquals(ecKeyDerivedFromBtcKey, expectedFederatorRskPublicKey);
            // since new federation was created without specifying mst public keys
            // these are set copying the rsk public keys,
            // so we should first assert that
            assertEquals(expectedFederatorRskPublicKey, expectedFederatorMstPublicKey);

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            byte[] activeFederatorBtcPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.BTC);
            assertArrayEquals(expectedFederatorBtcPublicKey.getPubKey(), activeFederatorBtcPublicKey);

            byte[] activeFederatorRskPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.RSK);
            assertArrayEquals(PublicKeys.compressed(expectedFederatorRskPublicKey), activeFederatorRskPublicKey);

            byte[] activeFederatorMstPublicKey = federationSupport.getActiveFederatorPublicKeyOfType(0, KeyType.MST);
            assertArrayEquals(PublicKeys.compressed(expectedFederatorMstPublicKey), activeFederatorMstPublicKey);
        }

        private Stream<Arguments> expectedFederatorPublicKeyOfTypeArgs() {
            BtcECKey federatorFromOldFederationBtcPublicKey = oldFederation.getBtcPublicKeys().get(0);
            BtcECKey federatorFromOldFederationRskPublicKey = getRskPublicKeysFromFederationMembers(oldFederation.getMembers()).get(0);
            BtcECKey federatorFromOldFederationMstPublicKey = getMstPublicKeysFromFederationMembers(oldFederation.getMembers()).get(0);

            BtcECKey federatorFromNewFederationBtcPublicKey = newFederation.getBtcPublicKeys().get(0);
            BtcECKey federatorFromNewFederationRskPublicKey = getRskPublicKeysFromFederationMembers(newFederation.getMembers()).get(0);
            BtcECKey federatorFromNewFederationMstPublicKey = getMstPublicKeysFromFederationMembers(newFederation.getMembers()).get(0);

            return Stream.of(
                Arguments.of(blockNumberFederationActivation - 1, federatorFromOldFederationBtcPublicKey, federatorFromOldFederationRskPublicKey, federatorFromOldFederationMstPublicKey),
                Arguments.of(blockNumberFederationActivation, federatorFromNewFederationBtcPublicKey, federatorFromNewFederationRskPublicKey, federatorFromNewFederationMstPublicKey)
            );
        }

        @ParameterizedTest
        @Tag("getActiveFederationUTXOs")
        @MethodSource("expectedUTXOsArgs")
        void getActiveFederationBtcUTXOs_returnsExpectedUTXOsAccordingToActivationAge(
            long currentBlock,
            List<UTXO> expectedUTXOs) {
            List<UTXO> oldFederationUTXOs = BitcoinTestUtils.createUTXOs(5, oldFederation.getAddress());
            storageProvider.getOldFederationBtcUTXOs().addAll(oldFederationUTXOs);
            List<UTXO> newFederationUTXOs = BitcoinTestUtils.createUTXOs(10, newFederation.getAddress());
            storageProvider.getNewFederationBtcUTXOs().addAll(newFederationUTXOs);

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            List<UTXO> activeFederationUTXOs = federationSupport.getActiveFederationBtcUTXOs();
            assertEquals(expectedUTXOs, activeFederationUTXOs);
        }

        private Stream<Arguments> expectedUTXOsArgs() {
            List<UTXO> oldFederationUTXOs = BitcoinTestUtils.createUTXOs(5, oldFederation.getAddress());
            List<UTXO> newFederationUTXOs = BitcoinTestUtils.createUTXOs(10, newFederation.getAddress());

            return Stream.of(
                Arguments.of(blockNumberFederationActivation - 1, oldFederationUTXOs),
                Arguments.of(blockNumberFederationActivation, newFederationUTXOs)
            );
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("active federation creation block height tests")
    class ActiveFederationCreationBlockHeightTests {
        // if nextFederationCreationBlockHeight is not set,
        // method should return activeFederationCreationBlockHeight.
        // if nextFederationCreationBlockHeight is set, method should return:
        // nextFederationCreationBlockHeight if enough blocks have passed,
        // activeFederationCreationBlockHeight if not.
        // if activeFederationCreationBlockHeight is not set,
        // method should return 0L.

        long nextFederationCreationBlockHeight = 200L;
        long activeFederationCreationBlockHeight = 100L;

        long newFederationActivationAge = federationMainnetConstants.getFederationActivationAge();

        @BeforeEach
        void setUp() {
            storageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
            storageProvider = new FederationStorageProviderImpl(storageAccessor);

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .build();
        }

        @Test
        @Tag("getActiveFederationCreationBlockHeight")
        void getActiveFederationCreationBlockHeight_withNextAndActiveFederationCreationBlockHeightsUnset_returnsZero() {
            long activeFedCreationBlockHeight = federationSupport.getActiveFederationCreationBlockHeight();
            assertEquals(0L, activeFedCreationBlockHeight);
        }

        @Test
        @Tag("getActiveFederationCreationBlockHeight")
        void getActiveFederationCreationBlockHeight_withNextFederationCreationBlockHeightUnsetAndActiveFederationCreationBlockHeightSet_returnsActiveFederationCreationBlockHeight() {
            storageProvider.setActiveFederationCreationBlockHeight(activeFederationCreationBlockHeight);

            long activeFedCreationBlockHeight = federationSupport.getActiveFederationCreationBlockHeight();
            assertEquals(activeFederationCreationBlockHeight, activeFedCreationBlockHeight);
        }

        @ParameterizedTest
        @Tag("getActiveFederationCreationBlockHeight")
        @MethodSource("newFederationCreationBlockHeightAndCurrentBlockArgs")
        void getActiveFederationCreationBlockHeight_withNewFederationCreationBlockHeightSetAndActiveFederationCreationBlockHeightUnset_returnsCreationBlockHeightAccordingToCurrentBlock(
            long currentBlock,
            long expectedActiveFederationCreationBlockHeight
        ) {
            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();
            storageProvider.setNextFederationCreationBlockHeight(nextFederationCreationBlockHeight);

            long activeFedCreationBlockHeight = federationSupport.getActiveFederationCreationBlockHeight();
            assertEquals(expectedActiveFederationCreationBlockHeight, activeFedCreationBlockHeight);
        }

        private Stream<Arguments> newFederationCreationBlockHeightAndCurrentBlockArgs() {
            return Stream.of(
                Arguments.of(nextFederationCreationBlockHeight + newFederationActivationAge - 1, 0L),
                Arguments.of(nextFederationCreationBlockHeight + newFederationActivationAge, nextFederationCreationBlockHeight)
            );
        }

        @ParameterizedTest
        @Tag("getActiveFederationCreationBlockHeight")
        @MethodSource("newAndActiveFederationCreationBlockHeightsAndCurrentBlockArgs")
        void getActiveFederationCreationBlockHeight_withNewAndActiveFederationCreationBlockHeightsSet_returnsCreationBlockHeightAccordingToCurrentBlock(
            long currentBlock,
            long expectedActiveFederationCreationBlockHeight
        ) {
            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();
            storageProvider.setActiveFederationCreationBlockHeight(activeFederationCreationBlockHeight);
            storageProvider.setNextFederationCreationBlockHeight(nextFederationCreationBlockHeight);

            long activeFedCreationBlockHeight = federationSupport.getActiveFederationCreationBlockHeight();
            assertEquals(expectedActiveFederationCreationBlockHeight, activeFedCreationBlockHeight);
        }

        private Stream<Arguments> newAndActiveFederationCreationBlockHeightsAndCurrentBlockArgs() {
            return Stream.of(
                Arguments.of(nextFederationCreationBlockHeight + newFederationActivationAge - 1, activeFederationCreationBlockHeight),
                Arguments.of(nextFederationCreationBlockHeight + newFederationActivationAge, nextFederationCreationBlockHeight)
            );
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("update federation creation block height tests")
    class UpdateFederationCreationBlockHeightTests {
        // this method updates its provider's block heights.
        // if nextFederationCreationBlockHeight is not set,
        // method should do nothing.
        // if nextFederationCreationBlockHeight is set, method should:
        // if not enough blocks have passed, do nothing.
        // if enough blocks have passed,
        // update activeFederationCreationBlockHeight with nextFederationCreationBlockHeight
        // and clear nextFederationCreationBlockHeight by setting a -1L.

        long nextFederationCreationBlockHeight = 200L;

        long newFederationActivationAge = federationMainnetConstants.getFederationActivationAge();

        @BeforeEach
        void setUp() {
            storageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
            storageProvider = new FederationStorageProviderImpl(storageAccessor);

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .build();
        }

        @Test
        @Tag("updateFederationCreationBlockHeights")
        void updateFederationCreationBlockHeights_withNextFederationCreationBlockHeightUnset_doesNothing() {
            assertFalse(storageProvider.getActiveFederationCreationBlockHeight().isPresent());
        }

        @Test
        @Tag("updateFederationCreationBlockHeights")
        void updateFederationCreationBlockHeights_withNextFederationCreationBlockHeightSetButInactiveNewFederation_doesNothing() {
            long currentBlockNumber = nextFederationCreationBlockHeight + newFederationActivationAge - 1;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlockNumber))
                .build();
            storageProvider.setNextFederationCreationBlockHeight(nextFederationCreationBlockHeight);

            federationSupport.updateFederationCreationBlockHeights();
            assertFalse(storageProvider.getActiveFederationCreationBlockHeight().isPresent());
        }

        @Test
        @Tag("updateFederationCreationBlockHeights")
        void updateFederationCreationBlockHeights_withNextFederationCreationBlockHeightSetAndActiveNewFederation_shouldUpdateBlockHeights() {
            long currentBlockNumber = nextFederationCreationBlockHeight + newFederationActivationAge;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlockNumber))
                .build();
            storageProvider.setNextFederationCreationBlockHeight(nextFederationCreationBlockHeight);

            federationSupport.updateFederationCreationBlockHeights();
            assertTrue(storageProvider.getActiveFederationCreationBlockHeight().isPresent());
            assertEquals(nextFederationCreationBlockHeight, storageProvider.getActiveFederationCreationBlockHeight().get());
            assertTrue(storageProvider.getNextFederationCreationBlockHeight().isPresent());
            assertEquals(-1L, storageProvider.getNextFederationCreationBlockHeight().get());
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Tag("null federations")
    class RetiringFederationTestsWithNullFederations {
        @BeforeEach
        void setUp() {
            storageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
            storageProvider = new FederationStorageProviderImpl(storageAccessor);

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .build();
        }

        @Test
        @Tag("getRetiringFederation")
        void getRetiringFederation_returnsNull() {
            Federation retiringFederation = federationSupport.getRetiringFederation();
            assertNull(retiringFederation);
        }

        @Test
        @Tag("getRetiringFederationAddress")
        void getRetiringFederationAddress_returnsNull() {
            Address retiringFederationAddress = federationSupport.getRetiringFederationAddress();
            assertNull(retiringFederationAddress);
        }

        @Test
        @Tag("getRetiringFederationSize")
        void getRetiringFederationSize_returnsRetiringFederationNonExistentResponseCode() {
            int retiringFederationSize = federationSupport.getRetiringFederationSize();
            assertEquals(FederationChangeResponseCode.FEDERATION_NON_EXISTENT.getCode(), retiringFederationSize);
        }

        @Test
        @Tag("getRetiringFederationThreshold")
        void getRetiringFederationThreshold_returnsRetiringFederationNonExistentResponseCode() {
            int retiringFederationThreshold = federationSupport.getRetiringFederationThreshold();
            assertEquals(FederationChangeResponseCode.FEDERATION_NON_EXISTENT.getCode(), retiringFederationThreshold);
        }

        @Test
        @Tag("getRetiringFederationCreationTime")
        void getRetiringFederationCreationTime_returnsNull() {
            Instant retiringFederationCreationTime = federationSupport.getRetiringFederationCreationTime();
            assertNull(retiringFederationCreationTime);
        }

        @Test
        @Tag("getRetiringFederationCreationBlockNumber")
        void getRetiringFederationCreationBlockNumber_returnsRetiringFederationNonExistentResponseCode() {
            long retiringFederationCreationBlockNumber = federationSupport.getRetiringFederationCreationBlockNumber();
            assertEquals((long) FederationChangeResponseCode.FEDERATION_NON_EXISTENT.getCode(), retiringFederationCreationBlockNumber);
        }

        @Test
        @Tag("getRetiringFederatorPublicKeyOfType")
        void getRetiringFederatorPublicKeyOfType_returnsNull() {
            byte[] retiringFederatorBtcPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.BTC);
            assertNull(retiringFederatorBtcPublicKey);

            byte[] retiringFederatorRskPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.RSK);
            assertNull(retiringFederatorRskPublicKey);

            byte[] retiringFederatorMstPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.MST);
            assertNull(retiringFederatorMstPublicKey);
        }

        @Test
        @Tag("getRetiringFederationBtcUTXOs")
        void getRetiringFederationUTXOs_returnsEmptyList() {
            List<UTXO> retiringFederationUTXOs = federationSupport.getRetiringFederationBtcUTXOs();
            assertEquals(Collections.emptyList(), retiringFederationUTXOs);
        }
    }

    @Nested
    @Tag("null old federation, non null new federation")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RetiringFederationTestsWithNullOldFederation {
        @BeforeEach
        void setUp() {
            storageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
            storageProvider = new FederationStorageProviderImpl(storageAccessor);

            // create new federation
            newFederation = P2shErpFederationBuilder.builder().build();

            storageProvider.setNewFederation(newFederation);
            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .build();
        }

        @Test
        @Tag("getRetiringFederation")
        void getRetiringFederation_returnsNull() {
            Federation retiringFederation = federationSupport.getRetiringFederation();
            assertNull(retiringFederation);
        }

        @Test
        @Tag("getRetiringFederationAddress")
        void getRetiringFederationAddress_returnsNull() {
            Address retiringFederationAddress = federationSupport.getRetiringFederationAddress();
            assertNull(retiringFederationAddress);
        }

        @Test
        @Tag("getRetiringFederationSize")
        void getRetiringFederationSize_returnsRetiringFederationNonExistentResponseCode() {
            int retiringFederationSize = federationSupport.getRetiringFederationSize();
            assertEquals(FederationChangeResponseCode.FEDERATION_NON_EXISTENT.getCode(), retiringFederationSize);
        }

        @Test
        @Tag("getRetiringFederationThreshold")
        void getRetiringFederationThreshold_returnsRetiringFederationNonExistentResponseCode() {
            int retiringFederationThreshold = federationSupport.getRetiringFederationThreshold();
            assertEquals(FederationChangeResponseCode.FEDERATION_NON_EXISTENT.getCode(), retiringFederationThreshold);
        }

        @Test
        @Tag("getRetiringFederationCreationTime")
        void getRetiringFederationCreationTime_returnsNull() {
            Instant retiringFederationCreationTime = federationSupport.getRetiringFederationCreationTime();
            assertNull(retiringFederationCreationTime);
        }

        @Test
        @Tag("getRetiringFederationCreationBlockNumber")
        void getRetiringFederationCreationBlockNumber_returnsRetiringFederationNonExistentResponseCode() {
            long retiringFederationCreationBlockNumber = federationSupport.getRetiringFederationCreationBlockNumber();
            assertEquals((long) FederationChangeResponseCode.FEDERATION_NON_EXISTENT.getCode(), retiringFederationCreationBlockNumber);
        }

        @Test
        @Tag("getRetiringFederatorPublicKeyOfType")
        void getRetiringFederatorPublicKeyOfType_returnsNull() {
            byte[] retiringFederatorBtcPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.BTC);
            assertNull(retiringFederatorBtcPublicKey);

            byte[] retiringFederatorRskPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.RSK);
            assertNull(retiringFederatorRskPublicKey);

            byte[] retiringFederatorMstPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.MST);
            assertNull(retiringFederatorMstPublicKey);
        }

        @Test
        @Tag("getRetiringFederationBtcUTXOs")
        void getRetiringFederationUTXOs_returnsEmptyList() {
            // set UTXOs for new federation since there is no retiring federation
            List<UTXO> newFederationUTXOs = BitcoinTestUtils.createUTXOs(10, newFederation.getAddress());
            storageProvider.getNewFederationBtcUTXOs().addAll(newFederationUTXOs);

            List<UTXO> retiringFederationUTXOs = federationSupport.getRetiringFederationBtcUTXOs();
            assertEquals(Collections.emptyList(), retiringFederationUTXOs);
        }
    }

    @Nested
    @Tag("non null federations")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RetiringFederationTestsWithNonNullFederations {
        // new federation should be active if we are past the activation block number
        // old federation should be active if we are before the activation block number

        // create old and new federations
        long oldFederationCreationBlockNumber = 20;
        long newFederationCreationBlockNumber = 65;
        Federation oldFederation = P2shErpFederationBuilder.builder()
            .withCreationBlockNumber(oldFederationCreationBlockNumber)
            .build();
        List<BtcECKey> newFederationKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
            new String[]{"fa01", "fa02", "fa03", "fa04", "fa05", "fa06", "fa07", "fa08", "fa09"}, true
        );
        Federation newFederation = P2shErpFederationBuilder.builder()
            .withMembersBtcPublicKeys(newFederationKeys)
            .withCreationBlockNumber(newFederationCreationBlockNumber)
            .build();

        long newFederationActivationAge = federationMainnetConstants.getFederationActivationAge();
        long blockNumberFederationActivation = newFederationCreationBlockNumber + newFederationActivationAge;

        FederationStorageProvider storageProvider;

        @BeforeEach
        void setUp() {
            // save federations in storage
            storageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
            storageProvider = new FederationStorageProviderImpl(storageAccessor);
            storageProvider.setOldFederation(oldFederation);
            storageProvider.setNewFederation(newFederation);
        }

        @Test
        @Tag("getRetiringFederation")
        void getRetiringFederation_withNewFederationNotActive_returnsNull() {
            long currentBlock = blockNumberFederationActivation - 1;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            Federation retiringFederation = federationSupport.getRetiringFederation();
            assertNull(retiringFederation);
        }

        @Test
        @Tag("getRetiringFederation")
        void getRetiringFederation_withNewFederationActive_returnsOldFederation() {
            long currentBlock = blockNumberFederationActivation;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            Federation retiringFederation = federationSupport.getRetiringFederation();
            assertEquals(oldFederation, retiringFederation);
        }

        @Test
        @Tag("getRetiringFederationAddress")
        void getRetiringFederationAddress_withNewFederationNotActive_returnsNull() {
            long currentBlock = blockNumberFederationActivation - 1;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            Address retiringFederationAddress = federationSupport.getRetiringFederationAddress();
            assertNull(retiringFederationAddress);
        }

        @Test
        @Tag("getRetiringFederation")
        void getRetiringFederationAddress_withNewFederationActive_returnsOldFederationAddress() {
            long currentBlock = blockNumberFederationActivation;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            Address retiringFederationAddress = federationSupport.getRetiringFederationAddress();
            assertEquals(oldFederation.getAddress(), retiringFederationAddress);
        }

        @Test
        @Tag("getRetiringFederationSize")
        void getRetiringFederationSize_withNewFederationNotActive_returnsRetiringFederationNonExistentResponseCode() {
            long currentBlock = blockNumberFederationActivation - 1;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            int retiringFederationSize = federationSupport.getRetiringFederationSize();
            assertEquals(FederationChangeResponseCode.FEDERATION_NON_EXISTENT.getCode(), retiringFederationSize);
        }

        @Test
        @Tag("getRetiringFederationSize")
        void getRetiringFederationSize_withNewFederationActive_returnsOldFederationSize() {
            long currentBlock = blockNumberFederationActivation;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            int retiringFederationSize = federationSupport.getRetiringFederationSize();
            assertEquals(oldFederation.getSize(), retiringFederationSize);
        }

        @Test
        @Tag("getRetiringFederationThreshold")
        void getRetiringFederationThreshold_withNewFederationNotActive_returnsRetiringFederationNonExistentResponseCode() {
            long currentBlock = blockNumberFederationActivation - 1;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            int retiringFederationThreshold = federationSupport.getRetiringFederationThreshold();
            assertEquals(FederationChangeResponseCode.FEDERATION_NON_EXISTENT.getCode(), retiringFederationThreshold);
        }

        @Test
        @Tag("getRetiringFederationThreshold")
        void getRetiringFederationThreshold_withNewFederationActive_returnsOldFederationThreshold() {
            long currentBlock = blockNumberFederationActivation;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            int retiringFederationThreshold = federationSupport.getRetiringFederationThreshold();
            assertEquals(oldFederation.getNumberOfSignaturesRequired(), retiringFederationThreshold);
        }

        @Test
        @Tag("getRetiringFederationCreationTime")
        void getRetiringFederationCreationTime_withNewFederationNotActive_returnsNull() {
            long currentBlock = blockNumberFederationActivation - 1;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            Instant retiringFederationCreationTime = federationSupport.getRetiringFederationCreationTime();
            assertNull(retiringFederationCreationTime);
        }

        @Test
        @Tag("getRetiringFederationCreationTime")
        void getRetiringFederationCreationTime_withNewFederationActive_returnsOldFederationCreationTime() {
            long currentBlock = blockNumberFederationActivation;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            Instant retiringFederationCreationTime = federationSupport.getRetiringFederationCreationTime();
            assertEquals(oldFederation.getCreationTime(), retiringFederationCreationTime);
        }

        @Test
        @Tag("getRetiringFederationCreationBlockNumber")
        void getRetiringFederationCreationBlockNumber_withNewFederationNotActive_returnsRetiringFederationNonExistentResponseCode() {
            long currentBlock = blockNumberFederationActivation - 1;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            long retiringFederationCreationBlockNumber = federationSupport.getRetiringFederationCreationBlockNumber();
            assertEquals((long) FederationChangeResponseCode.FEDERATION_NON_EXISTENT.getCode(), retiringFederationCreationBlockNumber);
        }

        @Test
        @Tag("getRetiringFederationCreationBlockNumber")
        void getRetiringFederationCreationBlockNumber_withNewFederationActive_returnsOldFederationCreationBlockNumber() {
            long currentBlock = blockNumberFederationActivation;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            long retiringFederationCreationBlockNumber = federationSupport.getRetiringFederationCreationBlockNumber();
            assertEquals(oldFederation.getCreationBlockNumber(), retiringFederationCreationBlockNumber);
        }

        @Test
        @Tag("getRetiringFederatorPublicKeyOfType")
        void getRetiringFederatorPublicKeyOfType_withNewFederationActiveAndNegativeIndex_throwsIndexOutOfBoundsException() {
            long currentBlock = blockNumberFederationActivation;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            assertThrows(IndexOutOfBoundsException.class, () -> federationSupport.getRetiringFederatorPublicKeyOfType(-1, KeyType.BTC));
        }

        @Test
        @Tag("getRetiringFederatorPublicKeyOfType")
        void getRetiringFederatorPublicKeyOfType_withNewFederationActiveAndIndexGreaterThanRetiringFederationSize_throwsIndexOutOfBoundsException() {
            long currentBlock = blockNumberFederationActivation;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            int retiringFederationSize = oldFederation.getSize();
            assertThrows(IndexOutOfBoundsException.class, () -> federationSupport.getRetiringFederatorPublicKeyOfType(retiringFederationSize, KeyType.BTC));
        }

        @Test
        @Tag("getRetiringFederatorPublicKeyOfType")
        void getRetiringFederatorPublicKeyOfType_withNewFederationNotActive_returnsNull() {
            long currentBlock = blockNumberFederationActivation - 1;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            byte[] retiringFederatorBtcPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.BTC);
            assertNull(retiringFederatorBtcPublicKey);

            byte[] retiringFederatorRskPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.RSK);
            assertNull(retiringFederatorRskPublicKey);

            byte[] retiringFederatorMstPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.MST);
            assertNull(retiringFederatorMstPublicKey);
        }

        @Test
        @Tag("getRetiringFederatorPublicKeyOfType")
        void getRetiringFederatorPublicKeyOfType_withNewFederationActive_returnsFederatorFromOldFederationPublicKeys() {
            long currentBlock = blockNumberFederationActivation;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            BtcECKey federatorFromOldFederationBtcPublicKey = oldFederation.getBtcPublicKeys().get(0);
            BtcECKey federatorFromOldFederationRskPublicKey = getRskPublicKeysFromFederationMembers(oldFederation.getMembers()).get(0);
            BtcECKey federatorFromOldFederationMstPublicKey = getMstPublicKeysFromFederationMembers(oldFederation.getMembers()).get(0);

            // since old federation was created without specifying rsk public keys
            // these are set deriving the btc public keys,
            // so we should first assert that
            BtcECKey ecKeyDerivedFromBtcKey = BtcECKey.fromPublicOnly(federatorFromOldFederationBtcPublicKey.getPubKey());
            assertEquals(ecKeyDerivedFromBtcKey, federatorFromOldFederationRskPublicKey);
            // since old federation was created without specifying mst public keys
            // these are set copying the rsk public keys,
            // so we should first assert that
            assertEquals(federatorFromOldFederationRskPublicKey, federatorFromOldFederationMstPublicKey);

            byte[] retiringFederatorBtcPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.BTC);
            assertArrayEquals(federatorFromOldFederationBtcPublicKey.getPubKey(), retiringFederatorBtcPublicKey);

            byte[] retiringFederatorRskPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.RSK);
            assertArrayEquals(PublicKeys.compressed(federatorFromOldFederationRskPublicKey), retiringFederatorRskPublicKey);

            byte[] retiringFederatorMstPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.MST);
            assertArrayEquals(PublicKeys.compressed(federatorFromOldFederationMstPublicKey), retiringFederatorMstPublicKey);
        }

        @Test
        @Tag("getRetiringFederatorPublicKeyOfType")
        void getRetiringFederatorPublicKeyOfType_withSpecificRskKeysAndNewFederationActive_returnsExpectedFederatorPublicKeys() {
            long currentBlock = blockNumberFederationActivation;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            // create old federation with specific rsk public keys
            List<BtcECKey> rskECKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
                new String[]{"rsk01", "rsk02", "rsk03", "rsk04", "rsk05", "rsk06", "rsk07", "rsk08", "rsk09"}, false
            );
            Federation oldFederationWithRskKeys = P2shErpFederationBuilder.builder()
                .withMembersRskPublicKeys(rskECKeys)
                .build();
            storageProvider.setOldFederation(oldFederationWithRskKeys);

            BtcECKey federatorFromOldFederationBtcPublicKey = oldFederationWithRskKeys.getBtcPublicKeys().get(0);
            BtcECKey federatorFromOldFederationRskPublicKey = getRskPublicKeysFromFederationMembers(oldFederationWithRskKeys.getMembers()).get(0);
            BtcECKey federatorFromOldFederationMstPublicKey = getMstPublicKeysFromFederationMembers(oldFederationWithRskKeys.getMembers()).get(0);

            // since old federation was created without specifying mst public keys
            // these are set copying the rsk public keys,
            // so we should first assert that
            assertEquals(federatorFromOldFederationRskPublicKey, federatorFromOldFederationMstPublicKey);

            byte[] retiringFederatorBtcPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.BTC);
            assertArrayEquals(federatorFromOldFederationBtcPublicKey.getPubKey(), retiringFederatorBtcPublicKey);

            byte[] retiringFederatorRskPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.RSK);
            assertArrayEquals(PublicKeys.compressed(federatorFromOldFederationRskPublicKey), retiringFederatorRskPublicKey);

            byte[] retiringFederatorMstPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.MST);
            assertArrayEquals(PublicKeys.compressed(federatorFromOldFederationMstPublicKey), retiringFederatorMstPublicKey);
        }

        @Test
        @Tag("getRetiringFederatorPublicKeyOfType")
        void getRetiringFederatorPublicKeyOfType_withSpecificRskAndMstKeysAndNewFederationActive_returnsExpectedFederatorPublicKeys() {
            long currentBlock = blockNumberFederationActivation;

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            // create old federation with specific rsk and mst public keys
            List<BtcECKey> rskECKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
                new String[]{"rsk01", "rsk02", "rsk03", "rsk04", "rsk05", "rsk06", "rsk07", "rsk08", "rsk09"}, false
            );
            List<BtcECKey> mstECKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
                new String[]{"mst01", "mst02", "mst03", "mst04", "mst05", "mst06", "mst07", "mst08", "mst09"}, false
            );
            Federation oldFederationWithRskAndMstKeys = P2shErpFederationBuilder.builder()
                .withMembersRskPublicKeys(rskECKeys)
                .withMembersMstPublicKeys(mstECKeys)
                .build();
            storageProvider.setOldFederation(oldFederationWithRskAndMstKeys);

            BtcECKey federatorFromOldFederationBtcPublicKey = oldFederationWithRskAndMstKeys.getBtcPublicKeys().get(0);
            BtcECKey federatorFromOldFederationRskPublicKey = getRskPublicKeysFromFederationMembers(oldFederationWithRskAndMstKeys.getMembers()).get(0);
            BtcECKey federatorFromOldFederationMstPublicKey = getMstPublicKeysFromFederationMembers(oldFederationWithRskAndMstKeys.getMembers()).get(0);

            byte[] retiringFederatorBtcPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.BTC);
            assertArrayEquals(federatorFromOldFederationBtcPublicKey.getPubKey(), retiringFederatorBtcPublicKey);

            byte[] retiringFederatorRskPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.RSK);
            assertArrayEquals(PublicKeys.compressed(federatorFromOldFederationRskPublicKey), retiringFederatorRskPublicKey);

            byte[] retiringFederatorMstPublicKey = federationSupport.getRetiringFederatorPublicKeyOfType(0, KeyType.MST);
            assertArrayEquals(PublicKeys.compressed(federatorFromOldFederationMstPublicKey), retiringFederatorMstPublicKey);
        }

        @Test
        @Tag("getRetiringFederationUTXOs")
        void getRetiringFederationBtcUTXOs_withNewFederationNotActive_returnsEmptyList() {
            long currentBlock = blockNumberFederationActivation - 1;

            // set UTXOs for both feds
            List<UTXO> oldFederationUTXOs = BitcoinTestUtils.createUTXOs(5, oldFederation.getAddress());
            storageProvider.getOldFederationBtcUTXOs().addAll(oldFederationUTXOs);
            List<UTXO> newFederationUTXOs = BitcoinTestUtils.createUTXOs(10, newFederation.getAddress());
            storageProvider.getNewFederationBtcUTXOs().addAll(newFederationUTXOs);

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            List<UTXO> retiringFederationUTXOs = federationSupport.getRetiringFederationBtcUTXOs();
            assertEquals(Collections.emptyList(), retiringFederationUTXOs);
        }

        @Test
        @Tag("getRetiringFederationUTXOs")
        void getRetiringFederationBtcUTXOs_withNewFederationActive_returnsOldFederationBtcUTXOs() {
            long currentBlock = blockNumberFederationActivation;

            // set UTXOs for both feds
            List<UTXO> oldFederationUTXOs = BitcoinTestUtils.createUTXOs(5, oldFederation.getAddress());
            storageProvider.getOldFederationBtcUTXOs().addAll(oldFederationUTXOs);
            List<UTXO> newFederationUTXOs = BitcoinTestUtils.createUTXOs(10, newFederation.getAddress());
            storageProvider.getNewFederationBtcUTXOs().addAll(newFederationUTXOs);

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .withHost(new InMemoryBridgeHost().blockNumber(currentBlock))
                .build();

            List<UTXO> retiringFederationUTXOs = federationSupport.getRetiringFederationBtcUTXOs();
            assertEquals(oldFederationUTXOs, retiringFederationUTXOs);
        }
    }

    @Nested
    @Tag("null pending federation")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class PendingFederationTestsWithNullFederation {

        @BeforeEach
        void setUp() {
            storageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
            storageProvider = new FederationStorageProviderImpl(storageAccessor);

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .build();
        }

        @Test
        @Tag("getPendingFederationSize")
        void getPendingFederationSize_returnsPendingFederationNonExistentResponseCode() {
            int pendingFederationSize = federationSupport.getPendingFederationSize();
            assertEquals(FederationChangeResponseCode.FEDERATION_NON_EXISTENT.getCode(), pendingFederationSize);
        }

        @Test
        @Tag("getPendingFederationHash")
        void getPendingFederationHash_returnsNull() {
            Hash pendingFederationHash = federationSupport.getPendingFederationHash();
            assertNull(pendingFederationHash);
        }

        @Test
        @Tag("getPendingFederatorPublicKeyOfType")
        void getPendingFederatorPublicKeyOfType_returnsNull() {
            byte[] pendingFederatorBtcPublicKey = federationSupport.getPendingFederatorPublicKeyOfType(0, KeyType.BTC);
            assertNull(pendingFederatorBtcPublicKey);

            byte[] pendingFederatorRskPublicKey = federationSupport.getPendingFederatorPublicKeyOfType(0, KeyType.RSK);
            assertNull(pendingFederatorRskPublicKey);

            byte[] pendingFederatorMstPublicKey = federationSupport.getPendingFederatorPublicKeyOfType(0, KeyType.MST);
            assertNull(pendingFederatorMstPublicKey);
        }
    }

    @Nested
    @Tag("non null pending federation")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class PendingFederationTestsWithNonNullFederation {

        PendingFederation pendingFederation = PendingFederationBuilder.builder().build();

        @BeforeEach
        void setUp() {
            storageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
            storageProvider = new FederationStorageProviderImpl(storageAccessor);

            federationSupport = federationSupportBuilder
                .withFederationConstants(federationMainnetConstants)
                .withFederationStorageProvider(storageProvider)
                .build();

            storageProvider.setPendingFederation(pendingFederation);
        }

        @Test
        @Tag("getPendingFederationSize")
        void getPendingFederationSize_returnsPendingFederationSize() {
            int pendingFederationSize = federationSupport.getPendingFederationSize();
            assertEquals(pendingFederation.getSize(), pendingFederationSize);
        }

        @Test
        @Tag("getPendingFederationHash")
        void getPendingFederationHash_returnsPendingFederationHash() {
            Hash pendingFederationHash = federationSupport.getPendingFederationHash();
            assertEquals(pendingFederation.getHash(), pendingFederationHash);
        }

        @Test
        @Tag("getPendingFederatorPublicKeyOfType")
        void getPendingFederatorPublicKeyOfType_withNegativeIndex_throwsIndexOutOfBoundsException() {
            assertThrows(IndexOutOfBoundsException.class, () -> federationSupport.getPendingFederatorPublicKeyOfType(-1, KeyType.BTC));
        }

        @Test
        @Tag("getPendingFederatorPublicKeyOfType")
        void getPendingFederatorPublicKeyOfType_withIndexGreaterThanPendingFederationSize_throwsIndexOutOfBoundsException() {
            int pendingFederationSize = pendingFederation.getSize();
            assertThrows(IndexOutOfBoundsException.class, () -> federationSupport.getPendingFederatorPublicKeyOfType(pendingFederationSize, KeyType.BTC));
        }

        @Test
        @Tag("getPendingFederatorPublicKeyOfType")
        void getPendingFederatorPublicKeyOfType_returnsFederatorFromPendingFederationPublicKeys() {
            BtcECKey federatorFromPendingFederationBtcPublicKey = pendingFederation.getBtcPublicKeys().get(0);
            BtcECKey federatorFromPendingFederationRskPublicKey = getRskPublicKeysFromFederationMembers(pendingFederation.getMembers()).get(0);
            BtcECKey federatorFromPendingFederationMstPublicKey = getMstPublicKeysFromFederationMembers(pendingFederation.getMembers()).get(0);

            // since pending federation was created without specifying rsk public keys
            // these are set deriving the btc public keys,
            // so we should first assert that
            BtcECKey ecKeyDerivedFromBtcKey = BtcECKey.fromPublicOnly(federatorFromPendingFederationBtcPublicKey.getPubKey());
            assertEquals(ecKeyDerivedFromBtcKey, federatorFromPendingFederationRskPublicKey);
            // since pending federation was created without specifying mst public keys
            // these are set copying the rsk public keys,
            // so we should first assert that
            assertEquals(federatorFromPendingFederationRskPublicKey, federatorFromPendingFederationMstPublicKey);

            byte[] pendingFederatorBtcPublicKey = federationSupport.getPendingFederatorPublicKeyOfType(0, KeyType.BTC);
            assertArrayEquals(federatorFromPendingFederationBtcPublicKey.getPubKey(), pendingFederatorBtcPublicKey);

            byte[] pendingFederatorRskPublicKey = federationSupport.getPendingFederatorPublicKeyOfType(0, KeyType.RSK);
            assertArrayEquals(PublicKeys.compressed(federatorFromPendingFederationRskPublicKey), pendingFederatorRskPublicKey);

            byte[] pendingFederatorMstPublicKey = federationSupport.getPendingFederatorPublicKeyOfType(0, KeyType.MST);
            assertArrayEquals(PublicKeys.compressed(federatorFromPendingFederationMstPublicKey), pendingFederatorMstPublicKey);
        }

        @Test
        @Tag("getPendingFederatorPublicKeyOfType")
        void getPendingFederatorPublicKeyOfType_withSpecificRskKeys_returnsFederatorFromPendingFederationPublicKeys() {
            // create pending federation with specific rsk public keys
            List<BtcECKey> rskECKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
                new String[]{"rsk01", "rsk02", "rsk03", "rsk04", "rsk05", "rsk06", "rsk07", "rsk08", "rsk09"}, false
            );
            PendingFederation pendingFederationWithRskKeys = PendingFederationBuilder.builder()
                .withMembersRskPublicKeys(rskECKeys)
                .build();
            storageProvider.setPendingFederation(pendingFederationWithRskKeys);

            BtcECKey federatorFromPendingFederationBtcPublicKey = pendingFederationWithRskKeys.getBtcPublicKeys().get(0);
            BtcECKey federatorFromPendingFederationRskPublicKey = getRskPublicKeysFromFederationMembers(pendingFederationWithRskKeys.getMembers()).get(0);
            BtcECKey federatorFromPendingFederationMstPublicKey = getMstPublicKeysFromFederationMembers(pendingFederationWithRskKeys.getMembers()).get(0);

            // since pending federation was created without specifying mst public keys
            // these are set copying the rsk public keys,
            // so we should first assert that
            assertEquals(federatorFromPendingFederationRskPublicKey, federatorFromPendingFederationMstPublicKey);

            byte[] pendingFederatorBtcPublicKey = federationSupport.getPendingFederatorPublicKeyOfType(0, KeyType.BTC);
            assertArrayEquals(federatorFromPendingFederationBtcPublicKey.getPubKey(), pendingFederatorBtcPublicKey);

            byte[] pendingFederatorRskPublicKey = federationSupport.getPendingFederatorPublicKeyOfType(0, KeyType.RSK);
            assertArrayEquals(PublicKeys.compressed(federatorFromPendingFederationRskPublicKey), pendingFederatorRskPublicKey);

            byte[] pendingFederatorMstPublicKey = federationSupport.getPendingFederatorPublicKeyOfType(0, KeyType.MST);
            assertArrayEquals(PublicKeys.compressed(federatorFromPendingFederationMstPublicKey), pendingFederatorMstPublicKey);
        }

        @Test
        @Tag("getPendingFederatorPublicKeyOfType")
        void getPendingFederatorPublicKeyOfType_withSpecificRskAndMstKeys_returnsFederatorFromPendingFederationPublicKeys() {
            // create pending federation with specific rsk and mst public keys
            List<BtcECKey> rskECKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
                new String[]{"rsk01", "rsk02", "rsk03", "rsk04", "rsk05", "rsk06", "rsk07", "rsk08", "rsk09"}, false
            );
            List<BtcECKey> mstECKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
                new String[]{"mst01", "mst02", "mst03", "mst04", "mst05", "mst06", "mst07", "mst08", "mst09"}, false
            );
            PendingFederation pendingFederationWithRskAndMstKeys = PendingFederationBuilder.builder()
                .withMembersRskPublicKeys(rskECKeys)
                .withMembersMstPublicKeys(mstECKeys)
                .build();
            storageProvider.setPendingFederation(pendingFederationWithRskAndMstKeys);

            BtcECKey federatorFromPendingFederationBtcPublicKey = pendingFederationWithRskAndMstKeys.getBtcPublicKeys().get(0);
            BtcECKey federatorFromPendingFederationRskPublicKey = getRskPublicKeysFromFederationMembers(pendingFederationWithRskAndMstKeys.getMembers()).get(0);
            BtcECKey federatorFromPendingFederationMstPublicKey = getMstPublicKeysFromFederationMembers(pendingFederationWithRskAndMstKeys.getMembers()).get(0);

            byte[] pendingFederatorBtcPublicKey = federationSupport.getPendingFederatorPublicKeyOfType(0, KeyType.BTC);
            assertArrayEquals(federatorFromPendingFederationBtcPublicKey.getPubKey(), pendingFederatorBtcPublicKey);

            byte[] pendingFederatorRskPublicKey = federationSupport.getPendingFederatorPublicKeyOfType(0, KeyType.RSK);
            assertArrayEquals(PublicKeys.compressed(federatorFromPendingFederationRskPublicKey), pendingFederatorRskPublicKey);

            byte[] pendingFederatorMstPublicKey = federationSupport.getPendingFederatorPublicKeyOfType(0, KeyType.MST);
            assertArrayEquals(PublicKeys.compressed(federatorFromPendingFederationMstPublicKey), pendingFederatorMstPublicKey);
        }
    }

    @Test
    @Tag("new federation btc utxos")
    void getNewFederationBtcUTXOs_whenNoUTXOsWereSaved_returnsEmptyList() {
        List<UTXO> newFederationUTXOs = federationSupport.getNewFederationBtcUTXOs();
        assertEquals(Collections.emptyList(), newFederationUTXOs);
    }

    @Test
    @Tag("new federation btc utxos")
    void getNewFederationBtcUTXOs_whenSavingUTXOs_returnsNewFederationUTXOs() {
        Address btcAddress = BitcoinTestUtils.createP2PKHAddress(federationMainnetConstants.getBtcParams(), "address");
        List<UTXO> newFederationUTXOs = BitcoinTestUtils.createUTXOs(10, btcAddress);

        storageProvider.getNewFederationBtcUTXOs().addAll(newFederationUTXOs);

        List<UTXO> actualNewFederationUTXOs = federationSupport.getNewFederationBtcUTXOs();
        assertEquals(newFederationUTXOs, actualNewFederationUTXOs);
    }

    @Test
    @Tag("clear retired federation")
    void clearRetiredFederation_whenHavingOldFederation_removesOldFederation() {
        ErpFederation federation = P2shErpFederationBuilder.builder().build();
        storageProvider.setOldFederation(federation);

        // check the old federation was correctly saved
        Federation oldFederation = storageProvider.getOldFederation(federationMainnetConstants);
        assertEquals(federation, oldFederation);

        federationSupport.clearRetiredFederation();
        // check the old federation was removed
        oldFederation = storageProvider.getOldFederation(federationMainnetConstants);
        assertNull(oldFederation);
    }

    @Test
    @Tag("clear proposed federation")
    void clearProposedFederation_removesProposedFederation() {
        // arrange
        ErpFederation federation = P2shErpFederationBuilder.builder().build();
        storageProvider.setProposedFederation(federation);

        // first check the proposed federation was correctly saved
        Optional<Federation> proposedFederation = storageProvider.getProposedFederation(federationMainnetConstants);
        assertTrue(proposedFederation.isPresent());
        assertEquals(federation, proposedFederation.get());

        // act
        federationSupport.clearProposedFederation();

        // assert
        Optional<Federation> currentProposedFederation = storageProvider.getProposedFederation(federationMainnetConstants);
        assertFalse(currentProposedFederation.isPresent());
    }

    @Test
    @Tag("save")
    void save_callsStorageProviderSave() {
        storageProvider = mock(FederationStorageProviderImpl.class);
        federationSupport = federationSupportBuilder
            .withFederationConstants(federationMainnetConstants)
            .withFederationStorageProvider(storageProvider)
            .build();

        federationSupport.save();
        verify(storageProvider).save();
    }

    @Test
    void getProposedFederation_whenStorageProviderReturnsEmpty_shouldReturnEmpty() {
        // Act
        Optional<Federation> actualProposedFederation = federationSupport.getProposedFederation();

        // Assert
        assertFalse(actualProposedFederation.isPresent());
    }

    @Test
    void getProposedFederation_whenStorageProviderReturnsProposedFederation_shouldReturnProposedFederation() {
        // Arrange
        Federation proposedFederation = P2shErpFederationBuilder.builder().build();
        storageProvider.setProposedFederation(proposedFederation);

        // Act
        Optional<Federation> actualProposedFederation = federationSupport.getProposedFederation();

        // Assert
        assertTrue(actualProposedFederation.isPresent());
        assertEquals(proposedFederation, actualProposedFederation.get());
    }
        
    @Test
    void getProposedFederationSize_whenStorageProviderReturnsEmpty_shouldReturnEmpty() {
        // Act
        Optional<Integer> actualProposedFederationSize = federationSupport.getProposedFederationSize();

        // Assert
        assertFalse(actualProposedFederationSize.isPresent());
    }

    @Test
    void getProposedFederationSize_whenStorageProviderReturnsProposedFederation_shouldReturnProposedFederationSize() {
        // Arrange
        List<BtcECKey> federationKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
            new String[] { "fa01", "fa02", "fa03", "fa04", "fa05", "fa06", "fa07", "fa08", "fa09" }, true);
        Federation proposedFederation = P2shErpFederationBuilder.builder().withMembersBtcPublicKeys(federationKeys).build();
        storageProvider.setProposedFederation(proposedFederation);
        int expectedSize = federationKeys.size();

        // Act
        Optional<Integer> actualProposedFederationSize = federationSupport.getProposedFederationSize();

        // Assert
        assertTrue(actualProposedFederationSize.isPresent());
        assertEquals(expectedSize, actualProposedFederationSize.get());
    }

    @Test
    void getProposedFederationAddress_whenStorageProviderReturnsEmpty_shouldReturnEmpty() {
        // Act
        Optional<Address> actualProposedFederationAddress = federationSupport.getProposedFederationAddress();

        // Assert
        assertFalse(actualProposedFederationAddress.isPresent());
    }

    @Test
    void getProposedFederationAddress_whenStorageProviderReturnsProposedFederation_shouldReturnProposedFederationAddress() {
        // Arrange
        Federation proposedFederation = P2shErpFederationBuilder.builder().build();
        storageProvider.setProposedFederation(proposedFederation);

        // Act
        Optional<Address> actualProposedFederationAddress = federationSupport.getProposedFederationAddress();

        // Assert
        assertTrue(actualProposedFederationAddress.isPresent());
        assertEquals(proposedFederation.getAddress(), actualProposedFederationAddress.get());
    }

    @Test
    void getProposedFederationCreationTime_whenStorageProviderReturnsEmpty_shouldReturnEmpty() {
        // Act
        Optional<Instant> actualCreationTime = federationSupport.getProposedFederationCreationTime();

        // Assert
        assertFalse(actualCreationTime.isPresent());
    }

    @Test
    void getProposedFederationCreationTime_whenStorageProviderReturnsProposedFederation_shouldReturnCreationTime() {
        // Arrange
        Instant creationTime = Instant.EPOCH;
        Federation proposedFederation = P2shErpFederationBuilder.builder()
            .withCreationTime(creationTime)
            .build();
        storageProvider.setProposedFederation(proposedFederation);

        // Act
        Optional<Instant> actualCreationTime = federationSupport.getProposedFederationCreationTime();

        // Assert
        assertTrue(actualCreationTime.isPresent());
        assertEquals(creationTime, actualCreationTime.get());
    }

    @Test
    void getProposedFederationCreationBlockNumber_whenStorageProviderReturnsEmpty_shouldReturnErrorCode() {
        // Act
        Optional<Long> actualCreationBlockNumber = federationSupport.getProposedFederationCreationBlockNumber();

        // Assert
        assertFalse(actualCreationBlockNumber.isPresent());
    }

    @Test
    void getProposedFederationCreationBlockNumber_whenStorageProviderReturnsProposedFederation_shouldReturnCreationBlockNumber() {
        // Arrange
        long creationBlockNumber = 12345L;
        Federation proposedFederation = P2shErpFederationBuilder.builder()
            .withCreationBlockNumber(creationBlockNumber)
            .build();
        storageProvider.setProposedFederation(proposedFederation);

        // Act
        Optional<Long> actualCreationBlockNumber = federationSupport.getProposedFederationCreationBlockNumber();

        // Assert
        assertTrue(actualCreationBlockNumber.isPresent());
        assertEquals(creationBlockNumber, actualCreationBlockNumber.get());
    }

    @ParameterizedTest
    @EnumSource(FederationMember.KeyType.class)
    void getProposedFederatorPublicKeyOfType_whenFederationIsEmpty_shouldReturnEmpty(FederationMember.KeyType keyType) {
        // Act
        Optional<byte[]> actualPublicKey = federationSupport.getProposedFederatorPublicKeyOfType(0, keyType);

        // Assert
        assertFalse(actualPublicKey.isPresent());
    }

    @Test
    void getProposedFederatorPublicKeyOfType_whenStorageProviderReturnsProposedFederationAndKeyTypeIsBTC_shouldReturnPublicKey() {
        // Arrange
        List<BtcECKey> federationKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
            new String[] { "fa01", "fa02", "fa03", "fa04", "fa05", "fa06", "fa07", "fa08", "fa09" }, true);
        Federation proposedFederation = P2shErpFederationBuilder.builder().withMembersBtcPublicKeys(federationKeys).build();
        FederationMember.KeyType keyType = FederationMember.KeyType.BTC;
        byte[] expectedPublicKey = PublicKeys.compressed(proposedFederation.getMembers().get(0).getPublicKey(keyType));
        storageProvider.setProposedFederation(proposedFederation);

        // Act
        Optional<byte[]> actualPublicKey = federationSupport.getProposedFederatorPublicKeyOfType(0, keyType);

        // Assert
        assertTrue(actualPublicKey.isPresent());
        assertArrayEquals(expectedPublicKey, actualPublicKey.get());
    }

    @Test
    void getProposedFederatorPublicKeyOfType_whenStorageProviderReturnsProposedFederationAndKeyTypeIsRSK_shouldReturnPublicKey() {
        // Arrange
        List<BtcECKey> federationKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
            new String[] { "fa01", "fa02", "fa03", "fa04", "fa05", "fa06", "fa07", "fa08", "fa09" }, true);
        List<BtcECKey> federationRskKeys = federationKeys.stream()
            .map(BtcECKey::getPubKey)
            .map(BtcECKey::fromPublicOnly)
            .toList();
        Federation proposedFederation = P2shErpFederationBuilder.builder().withMembersRskPublicKeys(federationRskKeys).build();
        FederationMember.KeyType keyType = FederationMember.KeyType.RSK;
        byte[] expectedPublicKey = PublicKeys.compressed(proposedFederation.getMembers().get(0).getPublicKey(keyType));
        storageProvider.setProposedFederation(proposedFederation);

        // Act
        Optional<byte[]> actualPublicKey = federationSupport.getProposedFederatorPublicKeyOfType(0, keyType);

        // Assert
        assertTrue(actualPublicKey.isPresent());
        assertArrayEquals(expectedPublicKey, actualPublicKey.get());
    }

    @Test
    void getProposedFederatorPublicKeyOfType_whenStorageProviderReturnsProposedFederationAndKeyTypeIsMst_shouldReturnPublicKey() {
        // Arrange
        List<BtcECKey> federationKeys = BitcoinTestUtils.getBtcEcKeysFromSeeds(
            new String[] { "fa01", "fa02", "fa03", "fa04", "fa05", "fa06", "fa07", "fa08", "fa09" }, true);
        List<BtcECKey> federationMstKeys = federationKeys.stream()
            .map(BtcECKey::getPubKey)
            .map(BtcECKey::fromPublicOnly)
            .toList();
        Federation proposedFederation = P2shErpFederationBuilder.builder().withMembersMstPublicKeys(federationMstKeys).build();
        FederationMember.KeyType keyType = FederationMember.KeyType.MST;
        byte[] expectedPublicKey = PublicKeys.compressed(proposedFederation.getMembers().get(0).getPublicKey(keyType));
        storageProvider.setProposedFederation(proposedFederation);

        // Act
        Optional<byte[]> actualPublicKey = federationSupport.getProposedFederatorPublicKeyOfType(0, keyType);

        // Assert
        assertTrue(actualPublicKey.isPresent());
        assertArrayEquals(expectedPublicKey, actualPublicKey.get());
    }

    private List<BtcECKey> getRskPublicKeysFromFederationMembers(List<FederationMember> members) {
        return members.stream()
            .map(FederationMember::getRskPublicKey)
            .toList();
    }

    private List<BtcECKey> getMstPublicKeysFromFederationMembers(List<FederationMember> members) {
        return members.stream()
            .map(FederationMember::getMstPublicKey)
            .toList();
    }
}
