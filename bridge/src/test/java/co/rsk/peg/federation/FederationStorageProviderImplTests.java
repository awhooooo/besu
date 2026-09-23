package co.rsk.peg.federation;

import static co.rsk.peg.BridgeSerializationUtils.serializeElection;
import static co.rsk.peg.federation.FederationFormatVersion.NON_STANDARD_ERP_FEDERATION;
import static co.rsk.peg.federation.FederationFormatVersion.P2SH_ERP_FEDERATION;
import static co.rsk.peg.federation.FederationFormatVersion.STANDARD_MULTISIG_FEDERATION;
import static co.rsk.peg.federation.FederationStorageIndexKey.*;
import static java.util.Objects.nonNull;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.UTXO;
import co.rsk.bitcoinj.script.Script;
import co.rsk.peg.BridgeSerializationUtils;
import co.rsk.peg.PegTestUtils;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.storage.StorageAccessor;
import co.rsk.peg.storage.UtxoRecords;
import co.rsk.peg.vote.ABICallElection;
import co.rsk.peg.vote.ABICallSpec;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Ported from RSKj's FederationStorageProviderImplTests over the in-memory host. The activation variants, the
 * versionless federation format, and the testnet UTXO keys are gone; the UTXO list tests are rewritten for the
 * record-backed lists, which write through instead of being saved.
 */
class FederationStorageProviderImplTests {
    private static final int STANDARD_MULTISIG_FEDERATION_FORMAT_VERSION = STANDARD_MULTISIG_FEDERATION.getFormatVersion();
    private static final int NON_STANDARD_ERP_FEDERATION_FORMAT_VERSION = NON_STANDARD_ERP_FEDERATION.getFormatVersion();
    private static final int P2SH_ERP_FEDERATION_FORMAT_VERSION = P2SH_ERP_FEDERATION.getFormatVersion();
    private static final int INVALID_FEDERATION_FORMAT = -1;
    private static final int EMPTY_FEDERATION_FORMAT = 0;

    private static final BridgeConstants bridgeConstants = BridgeMainNetConstants.getInstance();
    private static final FederationConstants federationConstants = bridgeConstants.getFederationConstants();
    private static final NetworkParameters networkParameters = federationConstants.getBtcParams();

    private static StorageAccessor inMemoryStorage() {
        return new BridgeStorageAccessorImpl(new InMemoryBridgeHost());
    }

    private static Stream<Arguments> provideFederationAndFormatArguments() {
        return Stream.of(
            Arguments.of(P2SH_ERP_FEDERATION_FORMAT_VERSION, P2shErpFederationBuilder.builder().build()),
            Arguments.of(NON_STANDARD_ERP_FEDERATION_FORMAT_VERSION, createNonStandardErpFederation()),
            Arguments.of(STANDARD_MULTISIG_FEDERATION_FORMAT_VERSION, StandardMultiSigFederationBuilder.builder().build()),
            Arguments.of(STANDARD_MULTISIG_FEDERATION_FORMAT_VERSION, null),
            Arguments.of(NON_STANDARD_ERP_FEDERATION_FORMAT_VERSION, null),
            Arguments.of(P2SH_ERP_FEDERATION_FORMAT_VERSION, null),
            Arguments.of(INVALID_FEDERATION_FORMAT, null),
            Arguments.of(EMPTY_FEDERATION_FORMAT, null)
        );
    }

    @ParameterizedTest
    @MethodSource("provideFederationAndFormatArguments")
    void testGetNewFederation(
        int federationFormat,
        Federation expectedFederation
    ) {
        // Arrange
        StorageAccessor storageAccessor = inMemoryStorage();
        byte[] federationFormatSerialized = getFederationFormatSerialized(federationFormat);
        storageAccessor.saveToRepository(NEW_FEDERATION_FORMAT_VERSION.getKey(), federationFormatSerialized);
        byte[] serializedFederation = getSerializedFederation(expectedFederation);
        storageAccessor.saveToRepository(NEW_FEDERATION_KEY.getKey(), serializedFederation);
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act
        Federation obtainedFederation = federationStorageProvider.getNewFederation(federationConstants);

        // Directly saving a null federation in storage to then assert that the method returns the cached federation
        if (nonNull(expectedFederation)) {
            storageAccessor.saveToRepository(NEW_FEDERATION_KEY.getKey(), null);
        }

        // Assert

        // Call the method again and assert the same cached federation is returned
        assertEquals(obtainedFederation, federationStorageProvider.getNewFederation(federationConstants));
        assertEquals(expectedFederation, obtainedFederation);
    }

    @ParameterizedTest
    @MethodSource("provideFederationAndFormatArguments")
    void testGetOldFederation(
        int federationFormat,
        Federation expectedFederation
    ) {
        // Arrange
        StorageAccessor storageAccessor = inMemoryStorage();
        byte[] federationFormatSerialized = getFederationFormatSerialized(federationFormat);
        storageAccessor.saveToRepository(OLD_FEDERATION_FORMAT_VERSION.getKey(), federationFormatSerialized);
        byte[] serializedFederation = getSerializedFederation(expectedFederation);
        storageAccessor.saveToRepository(OLD_FEDERATION_KEY.getKey(), serializedFederation);
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act
        Federation obtainedFederation = federationStorageProvider.getOldFederation(federationConstants);

        // Directly saving a null federation in storage to then assert that the method returns the cached federation
        if (nonNull(expectedFederation)) {
            storageAccessor.saveToRepository(OLD_FEDERATION_KEY.getKey(), null);
        }

        // Assert
        assertEquals(expectedFederation, obtainedFederation);

        // Call the method again and assert the same cached federation is returned
        assertEquals(obtainedFederation, federationStorageProvider.getOldFederation(federationConstants));
    }

    private static Stream<Arguments> provideFederationWithoutFormatArguments() {
        return Stream.of(
            Arguments.of(INVALID_FEDERATION_FORMAT, StandardMultiSigFederationBuilder.builder().build()),
            Arguments.of(EMPTY_FEDERATION_FORMAT, StandardMultiSigFederationBuilder.builder().build())
        );
    }

    @ParameterizedTest
    @MethodSource("provideFederationWithoutFormatArguments")
    void getNewFederation_whenStoredWithoutFormatVersion_shouldThrowIllegalStateException(
        int federationFormat,
        Federation storedFederation
    ) {
        // Arrange
        StorageAccessor storageAccessor = inMemoryStorage();
        storageAccessor.saveToRepository(NEW_FEDERATION_FORMAT_VERSION.getKey(), getFederationFormatSerialized(federationFormat));
        storageAccessor.saveToRepository(NEW_FEDERATION_KEY.getKey(), BridgeSerializationUtils.serializeFederation(storedFederation));
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act & assert
        assertThrows(IllegalStateException.class, () -> federationStorageProvider.getNewFederation(federationConstants));
    }

    @ParameterizedTest
    @MethodSource("provideFederationWithoutFormatArguments")
    void getOldFederation_whenStoredWithoutFormatVersion_shouldThrowIllegalStateException(
        int federationFormat,
        Federation storedFederation
    ) {
        // Arrange
        StorageAccessor storageAccessor = inMemoryStorage();
        storageAccessor.saveToRepository(OLD_FEDERATION_FORMAT_VERSION.getKey(), getFederationFormatSerialized(federationFormat));
        storageAccessor.saveToRepository(OLD_FEDERATION_KEY.getKey(), BridgeSerializationUtils.serializeFederation(storedFederation));
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act & assert
        assertThrows(IllegalStateException.class, () -> federationStorageProvider.getOldFederation(federationConstants));
    }

    @Test
    void getOldFederation_previouslySetToNull_returnsNull() {
        StorageAccessor storageAccessor = inMemoryStorage();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);
        federationStorageProvider.setOldFederation(null);
        Federation oldFederation = federationStorageProvider.getOldFederation(federationConstants);
        assertNull(oldFederation);
    }

    private static Stream<Arguments> providePendingFederationAndFormatArguments() {
        return Stream.of(
            Arguments.of(P2SH_ERP_FEDERATION_FORMAT_VERSION, PendingFederationBuilder.builder().build()),
            Arguments.of(NON_STANDARD_ERP_FEDERATION_FORMAT_VERSION, PendingFederationBuilder.builder().build()),
            Arguments.of(STANDARD_MULTISIG_FEDERATION_FORMAT_VERSION, PendingFederationBuilder.builder().build())
        );
    }

    @ParameterizedTest
    @MethodSource("providePendingFederationAndFormatArguments")
    void testGetPendingFederation(
        int federationFormat,
        PendingFederation expectedFederation
    ) {
        // Arrange
        StorageAccessor storageAccessor = inMemoryStorage();
        byte[] federationFormatSerialized = getFederationFormatSerialized(federationFormat);
        storageAccessor.saveToRepository(PENDING_FEDERATION_FORMAT_VERSION.getKey(), federationFormatSerialized);

        byte[] serializedFederation = expectedFederation.serialize();
        storageAccessor.saveToRepository(PENDING_FEDERATION_KEY.getKey(), serializedFederation);
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act
        PendingFederation obtainedFederation = federationStorageProvider.getPendingFederation();

        // Directly saving a null federation in storage to then assert that the method returns the cached federation
        storageAccessor.saveToRepository(PENDING_FEDERATION_KEY.getKey(), null);

        // Assert

        // Call the method again and assert the same cached federation is returned
        assertEquals(obtainedFederation, federationStorageProvider.getPendingFederation());
        assertEquals(expectedFederation, obtainedFederation);
    }

    private static Stream<Arguments> provideMissingFormatArguments() {
        return Stream.of(
            Arguments.of(INVALID_FEDERATION_FORMAT),
            Arguments.of(EMPTY_FEDERATION_FORMAT)
        );
    }

    @ParameterizedTest
    @MethodSource("provideMissingFormatArguments")
    void getPendingFederation_whenStorageVersionIsNotAvailable_shouldThrowIllegalStateException(int federationFormat) {
        // Arrange
        PendingFederation storedFederation = PendingFederationBuilder.builder().build();

        StorageAccessor storageAccessor = inMemoryStorage();
        byte[] federationFormatSerialized = getFederationFormatSerialized(federationFormat);
        storageAccessor.saveToRepository(PENDING_FEDERATION_FORMAT_VERSION.getKey(), federationFormatSerialized);

        byte[] serializedFederation = storedFederation.serialize();
        storageAccessor.saveToRepository(PENDING_FEDERATION_KEY.getKey(), serializedFederation);
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act & assert
        assertThrows(IllegalStateException.class, federationStorageProvider::getPendingFederation);
    }

    @Test
    void getPendingFederation_previouslySet_returnsCachedPendingFederation() {

        // Arrange
        PendingFederation expectedPendingFederation = PendingFederationBuilder.builder().build();

        // Act
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(null);
        federationStorageProvider.setPendingFederation(expectedPendingFederation);

        // Assert
        assertEquals(expectedPendingFederation, federationStorageProvider.getPendingFederation());
    }

    @Test
    void getPendingFederation_previouslySetToNull_returnsNull() {
        // Arrange
        StorageAccessor storageAccessor = mock(StorageAccessor.class);

        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act
        federationStorageProvider.setPendingFederation(null);
        PendingFederation pendingFederation = federationStorageProvider.getPendingFederation();

        // Assert
        assertNull(pendingFederation);
        verify(storageAccessor, never()).getFromRepository(any(), any());
    }

    @Test
    void getPendingFederation_nullPendingFederationInStorage_returnsNull() {
        // Arrange
        StorageAccessor storageAccessor = inMemoryStorage();
        byte[] federationFormatSerialized = getFederationFormatSerialized(STANDARD_MULTISIG_FEDERATION_FORMAT_VERSION);
        storageAccessor.saveToRepository(PENDING_FEDERATION_FORMAT_VERSION.getKey(), federationFormatSerialized);

        storageAccessor.saveToRepository(PENDING_FEDERATION_KEY.getKey(), null);
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act
        PendingFederation actualPendingFederation = federationStorageProvider.getPendingFederation();

        // Assert
        assertNull(actualPendingFederation);
    }

    private static Stream<Arguments> provideSaveFederationTestArguments() {
        Federation standardFederation = StandardMultiSigFederationBuilder.builder().build();
        Federation nonStandardFederation = createNonStandardErpFederation();
        Federation ps2hErpFederation = P2shErpFederationBuilder.builder().build();

        return Stream.of(
            // Any fed type is saved along with its version
            Arguments.of(STANDARD_MULTISIG_FEDERATION_FORMAT_VERSION, standardFederation),
            Arguments.of(NON_STANDARD_ERP_FEDERATION_FORMAT_VERSION, nonStandardFederation),
            Arguments.of(P2SH_ERP_FEDERATION_FORMAT_VERSION, ps2hErpFederation)
        );
    }

    @Test
    void saveNewFederation_should_not_save_null() {
        StorageAccessor storageAccessor = mock(StorageAccessor.class);

        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act
        federationStorageProvider.setNewFederation(null);
        federationStorageProvider.save();

        verify(storageAccessor, never()).saveToRepository(any(Bytes32.class), any(), any());
        verify(storageAccessor, never()).saveToRepository(any(Bytes32.class), any(byte[].class));
    }

    @ParameterizedTest
    @MethodSource("provideSaveFederationTestArguments")
    void testSaveNewFederation(
        int expectedFormatToSave,
        Federation federationToSave
    ) {
        // Arrange
        StorageAccessor storageAccessor = inMemoryStorage();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act
        federationStorageProvider.setNewFederation(federationToSave);
        federationStorageProvider.save();

        // Assert
        int savedFormatVersion = storageAccessor.getFromRepository(NEW_FEDERATION_FORMAT_VERSION.getKey(), BridgeSerializationUtils::deserializeInteger);
        assertEquals(expectedFormatToSave, savedFormatVersion);
        assertArrayEquals(
            BridgeSerializationUtils.serializeFederation(federationToSave),
            storageAccessor.getFromRepository(NEW_FEDERATION_KEY.getKey(), data -> data)
        );
    }

    @Test
    void saveOldFederation_should_save_null() {
        // Arrange
        // A federation is in storage first, so that saving null is seen to clear it
        StorageAccessor storageAccessor = inMemoryStorage();
        Federation storedFederation = P2shErpFederationBuilder.builder().build();
        storageAccessor.saveToRepository(OLD_FEDERATION_FORMAT_VERSION.getKey(), storedFederation.getFormatVersion(), BridgeSerializationUtils::serializeInteger);
        storageAccessor.saveToRepository(OLD_FEDERATION_KEY.getKey(), storedFederation, BridgeSerializationUtils::serializeFederation);

        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act
        federationStorageProvider.setOldFederation(null);
        federationStorageProvider.save();

        // Assert
        // The version of a null old federation is the standard one, to keep backwards compatibility
        int savedFormatVersion = storageAccessor.getFromRepository(OLD_FEDERATION_FORMAT_VERSION.getKey(), BridgeSerializationUtils::deserializeInteger);
        assertEquals(STANDARD_MULTISIG_FEDERATION_FORMAT_VERSION, savedFormatVersion);
        assertNull(storageAccessor.getFromRepository(OLD_FEDERATION_KEY.getKey(), data -> data));
    }

    @ParameterizedTest
    @MethodSource("provideSaveFederationTestArguments")
    void testSaveOldFederation(
        int expectedFormat,
        Federation federationToSave
    ) {
        // Arrange
        StorageAccessor storageAccessor = inMemoryStorage();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act
        federationStorageProvider.setOldFederation(federationToSave);
        federationStorageProvider.save();

        // Assert
        int savedFormatVersion = storageAccessor.getFromRepository(OLD_FEDERATION_FORMAT_VERSION.getKey(), BridgeSerializationUtils::deserializeInteger);
        assertEquals(expectedFormat, savedFormatVersion);
        assertArrayEquals(
            BridgeSerializationUtils.serializeFederation(federationToSave),
            storageAccessor.getFromRepository(OLD_FEDERATION_KEY.getKey(), data -> data)
        );
    }

    @Test
    void getNewFederationBtcUTXOs() {
        StorageAccessor storageAccessor = inMemoryStorage();
        Address btcAddress = BitcoinTestUtils.createP2PKHAddress(networkParameters, "test");

        // Save utxos directly in storage
        List<UTXO> expectedUtxos = BitcoinTestUtils.createUTXOs(2, btcAddress);
        appendRecords(storageAccessor, NEW_FEDERATION_BTC_UTXOS_KEY, expectedUtxos);

        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Getting utxos from method
        List<UTXO> actualUtxos = federationStorageProvider.getNewFederationBtcUTXOs();

        // Should be as the expected utxos
        assertEquals(expectedUtxos, actualUtxos);
    }

    @Test
    void getNewFederationBtcUTXOs_calledTwice_returnsTheSameList() {
        StorageAccessor storageAccessor = inMemoryStorage();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        Address btcAddress = BitcoinTestUtils.createP2PKHAddress(networkParameters, "test");

        // Save utxos directly in storage.
        List<UTXO> expectedUtxos = BitcoinTestUtils.createUTXOs(1, btcAddress);
        appendRecords(storageAccessor, NEW_FEDERATION_BTC_UTXOS_KEY, expectedUtxos);

        // Get utxos from method and check they are as expected
        List<UTXO> actualUtxos = federationStorageProvider.getNewFederationBtcUTXOs();
        assertEquals(1, actualUtxos.size());
        assertEquals(expectedUtxos, actualUtxos);

        // The list is the provider's one view of the records for the whole call: adding through it writes the
        // records, and a second get returns the same list
        List<UTXO> extraUtxos = Arrays.asList(
            BitcoinTestUtils.createUTXO(2, 0, Coin.COIN, btcAddress),
            BitcoinTestUtils.createUTXO(3, 0, Coin.COIN, btcAddress)
        );
        actualUtxos.addAll(extraUtxos);

        List<UTXO> actualUtxosAfterSecondGet = federationStorageProvider.getNewFederationBtcUTXOs();
        assertSame(actualUtxos, actualUtxosAfterSecondGet);
        assertEquals(3, actualUtxosAfterSecondGet.size());

        // Get utxos directly from storage and confirm that the storage has the new utxos
        List<UTXO> actualUtxosInStorage = loadRecords(storageAccessor, NEW_FEDERATION_BTC_UTXOS_KEY);
        assertEquals(3, actualUtxosInStorage.size());
        assertEquals(actualUtxosAfterSecondGet, actualUtxosInStorage);
    }

    @Test
    void getOldFederationBtcUTXOs() {
        StorageAccessor storageAccessor = inMemoryStorage();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        Address btcAddress = BitcoinTestUtils.createP2PKHAddress(networkParameters, "test");

        // Save utxos directly in storage.
        List<UTXO> expectedUtxos = BitcoinTestUtils.createUTXOs(1, btcAddress);
        appendRecords(storageAccessor, OLD_FEDERATION_BTC_UTXOS_KEY, expectedUtxos);

        // Get utxos from method and check they are as expected
        List<UTXO> actualUtxos = federationStorageProvider.getOldFederationBtcUTXOs();
        assertEquals(1, actualUtxos.size());
        assertEquals(expectedUtxos, actualUtxos);
    }

    @Test
    void getOldFederationBtcUTXOs_calledTwice_returnsTheSameList() {
        StorageAccessor storageAccessor = inMemoryStorage();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        Address btcAddress = BitcoinTestUtils.createP2PKHAddress(networkParameters, "test");

        // Save utxos directly in storage.
        List<UTXO> expectedUtxos = BitcoinTestUtils.createUTXOs(1, btcAddress);
        appendRecords(storageAccessor, OLD_FEDERATION_BTC_UTXOS_KEY, expectedUtxos);

        // Get utxos from method and check they are as expected
        List<UTXO> actualUtxos = federationStorageProvider.getOldFederationBtcUTXOs();
        assertEquals(1, actualUtxos.size());
        assertEquals(expectedUtxos, actualUtxos);

        // The list is the provider's one view of the records for the whole call: adding through it writes the
        // records, and a second get returns the same list
        List<UTXO> extraUtxos = Arrays.asList(
            BitcoinTestUtils.createUTXO(2, 0, Coin.COIN, btcAddress),
            BitcoinTestUtils.createUTXO(3, 0, Coin.COIN, btcAddress)
        );
        actualUtxos.addAll(extraUtxos);

        List<UTXO> actualUtxosAfterSecondGet = federationStorageProvider.getOldFederationBtcUTXOs();
        assertSame(actualUtxos, actualUtxosAfterSecondGet);
        assertEquals(3, actualUtxosAfterSecondGet.size());

        // Get utxos directly from storage and confirm that the storage has the new utxos
        List<UTXO> actualUtxosInStorage = loadRecords(storageAccessor, OLD_FEDERATION_BTC_UTXOS_KEY);
        assertEquals(3, actualUtxosInStorage.size());
        assertEquals(actualUtxosAfterSecondGet, actualUtxosInStorage);
    }

    @Test
    void getFederationElection_whenElectionIsInStorage_shouldReturnNewElection() {
        // Arrange
        AddressBasedAuthorizer authorizer = federationConstants.getFederationChangeAuthorizer();

        ABICallElection expectedElection = getSampleElection("function1", authorizer, FederationChangeCaller.FIRST_AUTHORIZED);
        byte[] expectedElectionEncoded = BridgeSerializationUtils.serializeElection(expectedElection);

        StorageAccessor storageAccessor = inMemoryStorage();
        storageAccessor.saveToRepository(FEDERATION_ELECTION_KEY.getKey(), expectedElectionEncoded);

        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act
        ABICallElection actualElection = federationStorageProvider.getFederationElection(authorizer);

        // Assert
        assertArrayEquals(expectedElectionEncoded, serializeElection(actualElection));
    }

    @Test
    void getFederationElection_whenCalledTwice_shouldReturnCached() {
        // Arrange
        AddressBasedAuthorizer authorizer = federationConstants.getFederationChangeAuthorizer();

        ABICallElection expectedElection = getSampleElection(
            "function1",
            authorizer,
            FederationChangeCaller.SECOND_AUTHORIZED
        );
        byte[] expectedElectionEncoded = BridgeSerializationUtils.serializeElection(expectedElection);

        StorageAccessor storageAccessor = inMemoryStorage();
        storageAccessor.saveToRepository(FEDERATION_ELECTION_KEY.getKey(), expectedElectionEncoded);

        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act
        ABICallElection actualElection = federationStorageProvider.getFederationElection(authorizer);
        assertArrayEquals(expectedElectionEncoded, serializeElection(actualElection));
        ABICallElection secondElectionSample = getSampleElection("function2", authorizer, FederationChangeCaller.THIRD_AUTHORIZED);
        storageAccessor.saveToRepository(FEDERATION_ELECTION_KEY.getKey(), BridgeSerializationUtils.serializeElection(secondElectionSample));

        // Assert
        ABICallElection cachedElection = federationStorageProvider.getFederationElection(authorizer);

        assertArrayEquals(serializeElection(actualElection), serializeElection(cachedElection));
        assertFalse(Arrays.equals(expectedElectionEncoded, serializeElection(secondElectionSample)));
    }

    @Test
    void getFederationElection_whenElectionIsNotInStorage_shouldReturnDefault() {
        // Arrange
        AddressBasedAuthorizer authorizer = federationConstants.getFederationChangeAuthorizer();

        ABICallElection expectedElection = new ABICallElection(authorizer);

        StorageAccessor storageAccessor = inMemoryStorage();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        // Act
        ABICallElection actualElection = federationStorageProvider.getFederationElection(authorizer);

        // Assert
        byte[] expectedElectionEncoded = BridgeSerializationUtils.serializeElection(expectedElection);
        assertArrayEquals(expectedElectionEncoded, serializeElection(actualElection));
    }

    @Test
    void getNextFederationCreationBlockHeight_getsValueFromStorage() {
        // Arrange
        StorageAccessor storageAccessor = inMemoryStorage();
        long expectedValue = 1_000_000L;

        storageAccessor.saveToRepository(NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey(), BridgeSerializationUtils.serializeLong(expectedValue));

        // Act
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);
        Optional<Long> actualValue = federationStorageProvider.getNextFederationCreationBlockHeight();

        // Assert
        assertTrue(actualValue.isPresent());
        assertEquals(expectedValue, actualValue.get());

        // Setting in storage a different value to assert that calling the method again should return cached value
        storageAccessor.saveToRepository(NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey(), BridgeSerializationUtils.serializeLong(2_000_000L));

        Optional<Long> actualCachedValue = federationStorageProvider.getNextFederationCreationBlockHeight();

        assertTrue(actualCachedValue.isPresent());
        assertEquals(expectedValue, actualCachedValue.get());
    }

    @Test
    void getNextFederationCreationBlockHeight_whenNoValueInStorage_returnsEmpty() {
        // Arrange
        StorageAccessor storageAccessor = inMemoryStorage();
        storageAccessor.saveToRepository(NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey(), null);

        // Act
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);
        Optional<Long> actualValue = federationStorageProvider.getNextFederationCreationBlockHeight();

        // Assert
        assertFalse(actualValue.isPresent());
    }

    @Test
    void saveNewFederationBtcUTXOs_utxosShouldBeSavedToStorage() {
        // Arrange
        StorageAccessor storageAccessor = inMemoryStorage();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        List<UTXO> utxos = federationStorageProvider.getNewFederationBtcUTXOs();
        Address btcAddress = BitcoinTestUtils.createP2PKHAddress(networkParameters, "test");
        int expectedCountOfUtxos = 3;
        List<UTXO> extraUtxos = BitcoinTestUtils.createUTXOs(expectedCountOfUtxos, btcAddress);
        utxos.addAll(extraUtxos);

        // The list writes through: the records are in storage before anything is saved
        assertEquals(extraUtxos, loadRecords(storageAccessor, NEW_FEDERATION_BTC_UTXOS_KEY));

        // Act
        federationStorageProvider.save();

        // Assert

        // Getting the utxos from storage to ensure they were stored in the storage.
        List<UTXO> finalListOfUtxosFromStorage = loadRecords(storageAccessor, NEW_FEDERATION_BTC_UTXOS_KEY);

        assertEquals(expectedCountOfUtxos, utxos.size());
        assertEquals(utxos, finalListOfUtxosFromStorage);

        // Ensuring `getNewFederationBtcUTXOs` of a new provider also returns the one saved in the storage.
        List<UTXO> finalListOfUtxosFromMethod = new FederationStorageProviderImpl(storageAccessor).getNewFederationBtcUTXOs();
        assertEquals(finalListOfUtxosFromStorage, finalListOfUtxosFromMethod);
    }

    @Test
    void saveOldFederationBtcUTXOs_utxosShouldBeSavedToStorage() {
        // Arrange
        StorageAccessor storageAccessor = inMemoryStorage();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        Address btcAddress = BitcoinTestUtils.createP2PKHAddress(networkParameters, "test");
        List<UTXO> utxos = federationStorageProvider.getOldFederationBtcUTXOs();
        List<UTXO> extraUtxos = BitcoinTestUtils.createUTXOs(1, btcAddress);
        utxos.addAll(extraUtxos);

        // The list writes through: the records are in storage before anything is saved
        assertEquals(extraUtxos, loadRecords(storageAccessor, OLD_FEDERATION_BTC_UTXOS_KEY));

        // Act
        federationStorageProvider.save();

        // Assert
        List<UTXO> finalListOfUtxosFromStorage = loadRecords(storageAccessor, OLD_FEDERATION_BTC_UTXOS_KEY);

        assertEquals(1, finalListOfUtxosFromStorage.size());
        assertEquals(utxos, finalListOfUtxosFromStorage);

        // Ensuring `getOldFederationBtcUTXOs` of a new provider also returns the one saved in the storage.
        List<UTXO> finalListOfUtxosFromMethod = new FederationStorageProviderImpl(storageAccessor).getOldFederationBtcUTXOs();
        assertEquals(finalListOfUtxosFromStorage, finalListOfUtxosFromMethod);
    }

    @Test
    void save_doesNotRewriteTheUtxoLists() {
        // Arrange
        InMemoryBridgeHost host = new InMemoryBridgeHost();
        StorageAccessor storageAccessor = new BridgeStorageAccessorImpl(host);
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        Address btcAddress = BitcoinTestUtils.createP2PKHAddress(networkParameters, "test");
        federationStorageProvider.getNewFederationBtcUTXOs().addAll(BitcoinTestUtils.createUTXOs(3, btcAddress));
        federationStorageProvider.getOldFederationBtcUTXOs().addAll(BitcoinTestUtils.createUTXOs(2, btcAddress));
        host.resetSlotCounters();

        // Act
        federationStorageProvider.save();

        // Assert
        assertEquals(0, host.slotWrites());
    }

    @Test
    void savePendingFederation_shouldBeSavedInStorageWithFormatVersion() {
        // Arrange
        PendingFederation expectedPendingFederation = PendingFederationBuilder.builder().build();
        StorageAccessor storageAccessor = inMemoryStorage();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);
        federationStorageProvider.setPendingFederation(expectedPendingFederation);

        // Act
        federationStorageProvider.save();

        // Assert
        PendingFederation actualPendingFederationInStorage = storageAccessor.getFromRepository(
            PENDING_FEDERATION_KEY.getKey(),
            PendingFederation::deserialize
        );

        int formatVersion = storageAccessor.getFromRepository(PENDING_FEDERATION_FORMAT_VERSION.getKey(), BridgeSerializationUtils::deserializeInteger);

        assertEquals(STANDARD_MULTISIG_FEDERATION.getFormatVersion(), formatVersion);
        assertEquals(expectedPendingFederation, actualPendingFederationInStorage);
    }

    @Test
    void saveActiveFederationCreationBlockHeight_whenHeightIsNotNull_shouldSaveToStorage() {
        // Arrange
        StorageAccessor storageAccessor = inMemoryStorage();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        long expectedFederationCreationBlockHeight = 1_300_000L;
        federationStorageProvider.setActiveFederationCreationBlockHeight(expectedFederationCreationBlockHeight);

        // Act
        federationStorageProvider.save();

        // Assert
        Optional<Long> actualFederationCreationBlockHeight = storageAccessor.getFromRepository(
            ACTIVE_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey(),
            BridgeSerializationUtils::deserializeOptionalLong
        );

        assertTrue(actualFederationCreationBlockHeight.isPresent());
        assertEquals(expectedFederationCreationBlockHeight, actualFederationCreationBlockHeight.get());
    }

    @Test
    void saveNextFederationCreationBlockHeight_whenHeightIsNotNull_shouldSaveToStorage() {
        // Arrange
        StorageAccessor storageAccessor = inMemoryStorage();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        long expectedFederationCreationBlockHeight = 1_400_000L;
        federationStorageProvider.setNextFederationCreationBlockHeight(expectedFederationCreationBlockHeight);

        // Act
        federationStorageProvider.save();

        // Assert
        Optional<Long> actualFederationCreationBlockHeight = storageAccessor.getFromRepository(NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey(), BridgeSerializationUtils::deserializeOptionalLong);

        assertTrue(actualFederationCreationBlockHeight.isPresent());
        assertEquals(expectedFederationCreationBlockHeight, actualFederationCreationBlockHeight.get());
    }

    @Test
    void saveLastRetiredFederationP2SHScript_getsValueFromStorage() {
        // Arrange
        Script expectedScript = P2shErpFederationBuilder.builder().build().getDefaultP2SHScript();

        StorageAccessor storageAccessor = inMemoryStorage();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);
        federationStorageProvider.setLastRetiredFederationP2SHScript(expectedScript);

        // Act
        federationStorageProvider.save();

        // Assert
        Script actualScript = storageAccessor.getFromRepository(
            LAST_RETIRED_FEDERATION_P2SH_SCRIPT_KEY.getKey(),
            BridgeSerializationUtils::deserializeScript
        );

        assertEquals(expectedScript, actualScript);
    }

    @Test
    void saveFederationElection_shouldSaveInStorage() {
        // Arrange
        AddressBasedAuthorizer authorizer = federationConstants.getFederationChangeAuthorizer();
        StorageAccessor storageAccessor = inMemoryStorage();
        FederationStorageProvider federationStorageProvider = new FederationStorageProviderImpl(storageAccessor);

        ABICallElection defaultFederationElection = federationStorageProvider.getFederationElection(authorizer);
        byte[] defaultFederationElectionSerialized = BridgeSerializationUtils.serializeElection(defaultFederationElection);
        ABICallSpec abiCallSpec = new ABICallSpec("function1", new byte[][]{});
        org.hyperledger.besu.datatypes.Address voterAddress = FederationChangeCaller.FIRST_AUTHORIZED.getAddress();
        assertTrue(defaultFederationElection.vote(abiCallSpec, voterAddress));
        byte[] federationElectionSerializedWithVote = BridgeSerializationUtils.serializeElection(defaultFederationElection);

        // Act
        federationStorageProvider.save();

        // Assert
        ABICallElection actualAbiCallElection = storageAccessor.getFromRepository(
            FEDERATION_ELECTION_KEY.getKey(),
            data -> BridgeSerializationUtils.deserializeElection(data, authorizer)
        );
        byte[] actualAbiCallElectionSerialized = BridgeSerializationUtils.serializeElection(actualAbiCallElection);

        assertFalse(Arrays.equals(defaultFederationElectionSerialized, federationElectionSerializedWithVote));
        assertArrayEquals(federationElectionSerializedWithVote, actualAbiCallElectionSerialized);
    }

    @Nested
    @Tag("proposed federation tests")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ProposedFederationTests {
        private final Federation proposedFederation = P2shErpFederationBuilder.builder().build();
        private StorageAccessor bridgeStorageAccessor;
        private FederationStorageProvider federationStorageProvider;

        @BeforeEach
        void setUp() {
            bridgeStorageAccessor = inMemoryStorage();
            federationStorageProvider = new FederationStorageProviderImpl(bridgeStorageAccessor);
        }

        @Test
        void saveProposedFederation_whenProposedFederationIsNotSet_shouldNotSave() {
            federationStorageProvider.save();

            assertNull(getProposedFederationFromRepository());
            assertNull(getProposedFederationFormatVersionFromRepository());
        }

        @Test
        void saveProposedFederation_whenProposedFederationIsSet_shouldSave() {
            federationStorageProvider.setProposedFederation(proposedFederation);
            federationStorageProvider.save();

            assertEquals(proposedFederation, getProposedFederationFromRepository());
            assertEquals(proposedFederation.getFormatVersion(), getProposedFederationFormatVersionFromRepository());
        }

        @Test
        void saveProposedFederation_whenProposedFederationIsSetToNull_shouldSave() {
            // first save a non-null value to make sure saving a null one is actually happening
            federationStorageProvider.setProposedFederation(proposedFederation);
            federationStorageProvider.save();

            federationStorageProvider.setProposedFederation(null);
            federationStorageProvider.save();

            assertNull(getProposedFederationFromRepository());
            assertNull(getProposedFederationFormatVersionFromRepository());
        }

        private Federation getProposedFederationFromRepository() {
            return bridgeStorageAccessor.getFromRepository(
                PROPOSED_FEDERATION.getKey(),
                data -> {
                    if (data == null) {
                        return null;
                    }

                    return BridgeSerializationUtils.deserializeFederationAccordingToVersion(data, getProposedFederationFormatVersionFromRepository(), federationConstants);
                }
            );
        }

        private Integer getProposedFederationFormatVersionFromRepository() {
            byte[] versionSerialized = bridgeStorageAccessor.getFromRepository(PROPOSED_FEDERATION_FORMAT_VERSION.getKey(), data -> data);

            return Optional.ofNullable(versionSerialized)
                .map(BridgeSerializationUtils::deserializeInteger)
                .orElse(null);
        }

        @Test
        void getProposedFederation_whenThereIsNoProposedFederationSavedNorSet_shouldReturnEmpty() {
            Optional<Federation> actualProposedFederation = federationStorageProvider.getProposedFederation(federationConstants);
            assertFalse(actualProposedFederation.isPresent());
        }

        @Test
        void getProposedFederation_whenProposedFederationIsSet_shouldReturnFederationSet() {
            federationStorageProvider.setProposedFederation(proposedFederation);

            Optional<Federation> actualProposedFederation = federationStorageProvider.getProposedFederation(federationConstants);
            assertEquals(Optional.of(proposedFederation), actualProposedFederation);
        }

        @Test
        void getProposedFederation_whenProposedFederationIsSetToNull_shouldReturnEmpty() {
            federationStorageProvider.setProposedFederation(null);

            Optional<Federation> actualProposedFederation = federationStorageProvider.getProposedFederation(federationConstants);
            assertFalse(actualProposedFederation.isPresent());
        }

        @Test
        void getProposedFederation_whenStorageIsNotEmptyAndProposedFederationIsSet_shouldReturnFederationSet() {
            // first we have to save the another proposed fed so the repo is not empty
            Federation savedFederation = FederationTestUtils.getErpFederation(federationConstants.getBtcParams());
            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION_FORMAT_VERSION.getKey(), savedFederation.getFormatVersion(), BridgeSerializationUtils::serializeInteger);
            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION.getKey(), savedFederation, BridgeSerializationUtils::serializeFederation);

            federationStorageProvider.setProposedFederation(proposedFederation);

            Optional<Federation> actualProposedFederation = federationStorageProvider.getProposedFederation(federationConstants);
            assertEquals(Optional.of(proposedFederation), actualProposedFederation);
        }

        @Test
        void getProposedFederation_whenStorageIsNotEmptyAndProposedFederationIsSetToNull_shouldReturnEmpty() {
            // first we have to save the another proposed fed so the repo is not empty
            Federation savedFederation = FederationTestUtils.getErpFederation(federationConstants.getBtcParams());
            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION_FORMAT_VERSION.getKey(), savedFederation.getFormatVersion(), BridgeSerializationUtils::serializeInteger);
            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION.getKey(), savedFederation, BridgeSerializationUtils::serializeFederation);

            federationStorageProvider.setProposedFederation(null);

            Optional<Federation> actualProposedFederation = federationStorageProvider.getProposedFederation(federationConstants);
            assertFalse(actualProposedFederation.isPresent());
        }

        @Test
        void getProposedFederation_whenProposedFederationIsSaved_shouldReturnSavedFederation() {
            // first we have to save the proposed fed so the repo is not empty
            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION_FORMAT_VERSION.getKey(), proposedFederation.getFormatVersion(), BridgeSerializationUtils::serializeInteger);
            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION.getKey(), proposedFederation, BridgeSerializationUtils::serializeFederation);

            Optional<Federation> actualProposedFederation = federationStorageProvider.getProposedFederation(federationConstants);
            assertEquals(Optional.of(proposedFederation), actualProposedFederation);
        }

        @Test
        void getProposedFederation_whenNullProposedFederationIsSaved_shouldReturnEmpty() {
            // first save a non-null value to make sure saving a null one is actually happening
            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION_FORMAT_VERSION.getKey(), proposedFederation.getFormatVersion(), BridgeSerializationUtils::serializeInteger);
            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION.getKey(), proposedFederation, BridgeSerializationUtils::serializeFederation);

            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION_FORMAT_VERSION.getKey(), null, BridgeSerializationUtils::serializeInteger);
            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION.getKey(), null, BridgeSerializationUtils::serializeFederation);

            Optional<Federation> actualProposedFederation = federationStorageProvider.getProposedFederation(federationConstants);
            assertFalse(actualProposedFederation.isPresent());
        }

        @Test
        void getProposedFederation_whenProposedFederationIsSavedWithoutStorageVersion_shouldThrowIllegalStateException() {
            // save a proposed federation without storage version
            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION.getKey(), proposedFederation, BridgeSerializationUtils::serializeFederation);

            assertThrows(IllegalStateException.class, () -> federationStorageProvider.getProposedFederation(federationConstants));
        }

        @Test
        void getProposedFederation_whenProposedFederationIsCached_shouldReturnCachedFederation() {
            // first we have to save the proposed fed so the repo is not empty
            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION_FORMAT_VERSION.getKey(), proposedFederation.getFormatVersion(), BridgeSerializationUtils::serializeInteger);
            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION.getKey(), proposedFederation, BridgeSerializationUtils::serializeFederation);
            // this should set the proposed fed in proposedFederation field
            federationStorageProvider.getProposedFederation(federationConstants);

            // saving in the repo another fed to make sure the cached value is the one being returned
            Federation anotherFederation = FederationTestUtils.getErpFederation(federationConstants.getBtcParams());
            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION_FORMAT_VERSION.getKey(), anotherFederation.getFormatVersion(), BridgeSerializationUtils::serializeInteger);
            bridgeStorageAccessor.saveToRepository(PROPOSED_FEDERATION.getKey(), anotherFederation, BridgeSerializationUtils::serializeFederation);

            Optional<Federation> actualProposedFederation = federationStorageProvider.getProposedFederation(federationConstants);
            assertEquals(Optional.of(proposedFederation), actualProposedFederation);
        }
    }

    private static Federation createNonStandardErpFederation() {
        List<FederationMember> members = FederationMember.getFederationMembersFromKeys(
            PegTestUtils.createRandomBtcECKeys(7)
        );

        FederationArgs federationArgs = new FederationArgs(
            members,
            Instant.now(),
            1L,
            networkParameters
        );
        List<BtcECKey> erpPubKeys = federationConstants.getErpFedPubKeysList();
        long activationDelay = federationConstants.getErpFedActivationDelay();

        return FederationFactory.buildNonStandardErpFederation(
            federationArgs,
            erpPubKeys,
            activationDelay
        );
    }

    private byte[] getFederationFormatSerialized(int federationFormat) {
        switch (federationFormat) {
            case INVALID_FEDERATION_FORMAT:
                return null;
            case EMPTY_FEDERATION_FORMAT:
                return new byte[]{};
            default:
                return BridgeSerializationUtils.serializeInteger(federationFormat);
        }
    }

    private byte[] getSerializedFederation(Federation federation) {
        if (federation == null) {
            return null;
        }

        return BridgeSerializationUtils.serializeFederation(federation);
    }

    /** Writes the UTXOs straight into the records of the given list, as the bridge would have left them. */
    private static void appendRecords(StorageAccessor storageAccessor, FederationStorageIndexKey listKey, List<UTXO> utxos) {
        UtxoRecords records = new UtxoRecords(storageAccessor, listKey.getKey());
        utxos.forEach(records::append);
    }

    private static List<UTXO> loadRecords(StorageAccessor storageAccessor, FederationStorageIndexKey listKey) {
        List<UTXO> utxos = new ArrayList<>();
        for (UtxoRecords.Entry entry : new UtxoRecords(storageAccessor, listKey.getKey()).loadEntries()) {
            utxos.add(entry.utxo());
        }
        return utxos;
    }

    private static ABICallElection getSampleElection(String functionName, AddressBasedAuthorizer authorizer, FederationChangeCaller federationChangeCaller) {
        Map<ABICallSpec, List<org.hyperledger.besu.datatypes.Address>> sampleVotes = new HashMap<>();
        sampleVotes.put(
            new ABICallSpec(functionName, new byte[][]{}),
            Collections.singletonList(federationChangeCaller.getAddress())
        );
        return new ABICallElection(authorizer, sampleVotes);
    }
}
