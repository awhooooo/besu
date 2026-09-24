package co.rsk.peg.federation;

import static co.rsk.peg.bitcoin.BitcoinTestUtils.flatKeysAsByteArray;
import static org.junit.jupiter.api.Assertions.*;

import co.rsk.RskTestUtils;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.UTXO;
import co.rsk.bitcoinj.script.Script;
import co.rsk.peg.BridgeEvents;
import co.rsk.peg.PegTestUtils;
import co.rsk.peg.abi.AbiFunction;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.federation.constants.FederationMainNetConstants;
import co.rsk.peg.host.CallContext;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.storage.StorageAccessor;
import co.rsk.peg.utils.BridgeEventLogger;
import co.rsk.peg.utils.BridgeEventLoggerImpl;
import co.rsk.peg.utils.PublicKeys;
import co.rsk.peg.vote.ABICallSpec;
import co.rsk.test.builders.FederationSupportBuilder;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

class VoteFederationChangeTest {
    private static final FederationConstants federationMainnetConstants = FederationMainNetConstants.getInstance();
    private static final BtcECKey federatorBtcKey = BtcECKey.fromPrivate(BigInteger.valueOf(100));
    private static final BtcECKey federatorRskKey = BtcECKey.fromPrivate(BigInteger.valueOf(200));
    private static final BtcECKey federatorMstKey = BtcECKey.fromPrivate(BigInteger.valueOf(300));
    private static final CallContext firstAuthorizedTx = PegTestUtils.callFrom(FederationChangeCaller.FIRST_AUTHORIZED.getAddress());
    private static final CallContext secondAuthorizedTx = PegTestUtils.callFrom(FederationChangeCaller.SECOND_AUTHORIZED.getAddress());
    private static final CallContext thirdAuthorizedTx = PegTestUtils.callFrom(FederationChangeCaller.THIRD_AUTHORIZED.getAddress());
    private static final CallContext fourthAuthorizedTx = PegTestUtils.callFrom(FederationChangeCaller.FOURTH_AUTHORIZED.getAddress());
    private static final CallContext fifthAuthorizedTx = PegTestUtils.callFrom(FederationChangeCaller.FIFTH_AUTHORIZED.getAddress());
    private static final List<CallContext> authorized_tx_set = List.of(firstAuthorizedTx, secondAuthorizedTx, thirdAuthorizedTx, fourthAuthorizedTx, fifthAuthorizedTx);
    private static final AbiFunction commitFederationEvent = BridgeEvents.COMMIT_FEDERATION.getEvent();
    private static final long RSK_EXECUTION_BLOCK_NUMBER = 1000L;
    private static final long RSK_EXECUTION_BLOCK_TIMESTAMP = 10L;
    private static final PendingFederation pendingFederationToBe = new PendingFederation(FederationTestUtils.getFederationMembers(9));
    private static final Federation activeFederation = FederationTestUtils.getErpFederation(federationMainnetConstants.getBtcParams());

    private final FederationSupportBuilder federationSupportBuilder = FederationSupportBuilder.builder();
    private FederationSupport federationSupport;
    private InMemoryBridgeHost host;
    private BridgeEventLogger bridgeEventLogger;
    private StorageAccessor bridgeStorageAccessor;
    private FederationStorageProvider storageProvider;

    @BeforeEach
    void setUp() {
        host = new InMemoryBridgeHost()
            .blockNumber(RSK_EXECUTION_BLOCK_NUMBER)
            .blockTimestamp(RSK_EXECUTION_BLOCK_TIMESTAMP);
        bridgeEventLogger = new BridgeEventLoggerImpl(BridgeMainNetConstants.getInstance(), host);

        bridgeStorageAccessor = new BridgeStorageAccessorImpl(host);
        storageProvider = new FederationStorageProviderImpl(bridgeStorageAccessor);
        storageProvider.setNewFederation(activeFederation);

        federationSupport = federationSupportBuilder
            .withFederationConstants(federationMainnetConstants)
            .withFederationStorageProvider(storageProvider)
            .withHost(host)
            .build();
    }

    @Test
    void voteFederationChange_withNonExistingFunction_returnsNonExistingResponseCode() {
        // Arrange
        ABICallSpec nonExistingFunctionCallSpec = new ABICallSpec("nonExistingFunctionName", new byte[][]{});

        // Act
        int result = federationSupport.voteFederationChange(firstAuthorizedTx, nonExistingFunctionCallSpec, bridgeEventLogger);

        // Assert
        assertEquals(FederationChangeResponseCode.NON_EXISTING_FUNCTION_CALLED.getCode(), result);
    }

    // vote create federation tests
    @Test
    void voteCreateFederation_withUnauthorizedCaller_returnsUnauthorizedResponseCode() {
        // Act
        CallContext unauthorizedTx = PegTestUtils.callFrom(FederationChangeCaller.UNAUTHORIZED.getAddress());
        int result = voteToCreatePendingFederation(unauthorizedTx);

        // Assert
        assertEquals(FederationChangeResponseCode.UNAUTHORIZED_CALLER.getCode(), result);
    }

    @Test
    void voteCreateFederation_withoutEnoughVotes_returnsSuccessfulButDoesNotCreatePendingFederation() {
        // Act
        int result = voteToCreatePendingFederation(firstAuthorizedTx);

        // Assert
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), result);
        assertNull(federationSupport.getPendingFederationHash());
    }

    @Test
    void voteCreateFederation_whenVotingTwiceWithSameAuthorizer_returnsGenericErrorResponseCodeInSecondCall() {
        // Act
        // First create call
        int firstCall = voteToCreatePendingFederation(firstAuthorizedTx);

        // Second create call
        int secondCall = voteToCreatePendingFederation(firstAuthorizedTx);

        // Assert
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), firstCall);
        assertEquals(FederationChangeResponseCode.GENERIC_ERROR.getCode(), secondCall);

    }

    @Test
    void voteCreateFederation_withEnoughVotes_returnsSuccessfulResponseCodeAndPendingFederationCreated() {
        // Act and assert
        voteAndAssertCreateEmptyPendingFederation();
        assertNotNull(federationSupport.getPendingFederationHash());
    }


    // vote add federator public keys tests
    @Test
    void voteAddFederatorPublicKeys_withOnlyOneKey_throwsArrayIndexOutOfBoundsException() {
        // Arrange
        voteAndAssertCreateEmptyPendingFederation();
        ABICallSpec addOnlyBtcFederatorPublicKeyAbiCallSpec = new ABICallSpec(FederationChangeFunction.ADD_MULTI.getKey(), new byte[][]{ federatorBtcKey.getPubKey() });

        // Act and assert
        assertThrows(ArrayIndexOutOfBoundsException.class,
            () -> federationSupport.voteFederationChange(firstAuthorizedTx, addOnlyBtcFederatorPublicKeyAbiCallSpec, bridgeEventLogger)
        );
    }

    @Test
    void voteAddFederatorPublicKeys_withoutEnoughVotes_returnsSuccessfulButDoesNotAddFederator() {
        // Arrange
        voteAndAssertCreateEmptyPendingFederation();

        // Act
        int result = voteToAddFederatorPublicKeysToPendingFederation(firstAuthorizedTx, federatorBtcKey, federatorRskKey, federatorMstKey);

        // Assert
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), result);
        assertEquals(0, federationSupport.getPendingFederationSize());
    }

    @Test
    void voteAddFederatorPublicKeys_withEnoughVotes_returnsSuccessfulAndAddsFederatorPublicKeys() {
        // Arrange
        voteAndAssertCreateEmptyPendingFederation();

        // Act
        voteAndAssertAddFederatorPublicKeysToPendingFederation(federatorBtcKey, federatorRskKey, federatorMstKey);
        assertEquals(1, federationSupport.getPendingFederationSize());

        byte[] actualBtcECkey = federationSupport.getPendingFederatorPublicKeyOfType(0, FederationMember.KeyType.BTC);
        byte[] actualRskKey = federationSupport.getPendingFederatorPublicKeyOfType(0, FederationMember.KeyType.RSK);
        byte[] actualMstKey = federationSupport.getPendingFederatorPublicKeyOfType(0, FederationMember.KeyType.MST);
        assertArrayEquals(federatorBtcKey.getPubKey(), actualBtcECkey);
        assertArrayEquals(PublicKeys.compressed(federatorRskKey), actualRskKey);
        assertArrayEquals(PublicKeys.compressed(federatorMstKey), actualMstKey);
    }

    @ParameterizedTest
    @MethodSource("keysWithOneInvalidKeyArgProvider")
    void voteAddFederatorPublicKeys_withOneInvalidPublicKey_returnsGenericErrorResponseCode(
        byte[] federatorBtcKeySerialized, byte[] federatorRskKeySerialized, byte[] federatorMstKeySerialized
    ) {
        // Arrange
        voteAndAssertCreateEmptyPendingFederation();

        // Act
        ABICallSpec addFederatorAbiCallSpec = new ABICallSpec(FederationChangeFunction.ADD_MULTI.getKey(),
            new byte[][]{ federatorBtcKeySerialized, federatorRskKeySerialized, federatorMstKeySerialized }
        );
        int voteAddMultiKeyResult = federationSupport.voteFederationChange(firstAuthorizedTx, addFederatorAbiCallSpec, bridgeEventLogger);

        // Assert
        assertEquals(FederationChangeResponseCode.GENERIC_ERROR.getCode(), voteAddMultiKeyResult);
    }

    private static Stream<Arguments> keysWithOneInvalidKeyArgProvider() {
        byte[] invalidKey = RskTestUtils.generateBytes(1, 30);

        return Stream.of(
            Arguments.of(federatorBtcKey.getPubKey(), PublicKeys.uncompressed(federatorRskKey), invalidKey),
            Arguments.of(federatorBtcKey.getPubKey(), invalidKey, PublicKeys.uncompressed(federatorMstKey)),
            Arguments.of(invalidKey, PublicKeys.uncompressed(federatorRskKey), PublicKeys.uncompressed(federatorMstKey))
        );
    }

    @Test
    void voteAddFederatorPublicKeys_whenSameAuthorizerVotesTwice_returnsGenericErrorResponseCode() {
        // Arrange
        voteAndAssertCreateEmptyPendingFederation();

        // Act
        // Voting add public key twice with same authorizer
        int firstVoteAddFederationResult = voteToAddFederatorPublicKeysToPendingFederation(firstAuthorizedTx, federatorBtcKey, federatorRskKey, federatorMstKey);
        int secondVoteAddFederationResult = voteToAddFederatorPublicKeysToPendingFederation(firstAuthorizedTx, federatorBtcKey, federatorRskKey, federatorMstKey);

        // Assert
        // First call is successful
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), firstVoteAddFederationResult);
        // Second call fails
        assertEquals(FederationChangeResponseCode.GENERIC_ERROR.getCode(), secondVoteAddFederationResult);

        assertEquals(0, federationSupport.getPendingFederationSize());
    }

    @ParameterizedTest
    @MethodSource("keysWithOneDifferentKeyArgProvider")
    void voteAddFederatorPublicKeys_whenVotingAnotherFederatorThatSharesOneKeyWithExistingFederator_returnsFederatorAlreadyPresentResponseCode(
        BtcECKey federator2BtcPublicKey, BtcECKey federator2RskPublicKey, BtcECKey federator2MstPublicKey
    ) {
        voteAndAssertCreateEmptyPendingFederation();

        // Act
        // Add first federator to pending federation
        voteAndAssertAddFederatorPublicKeysToPendingFederation(federatorBtcKey, federatorRskKey, federatorMstKey);
        // Voting new federator public keys,
        // that will be considered the same as the previous one
        // because they share at least one key
        int firstVoteAddMultiFederator2KeysResult = voteToAddFederatorPublicKeysToPendingFederation(firstAuthorizedTx, federator2BtcPublicKey, federator2RskPublicKey, federator2MstPublicKey);

        // Assert
        assertEquals(FederationChangeResponseCode.FEDERATOR_ALREADY_PRESENT.getCode(), firstVoteAddMultiFederator2KeysResult);

        // Pending federation size is 1, because authorizers first voted for the same federator and the second was ignored
        assertEquals(1, federationSupport.getPendingFederationSize());
    }

    @ParameterizedTest
    @MethodSource("keysWithOneDifferentKeyArgProvider")
    void voteAddFederatorPublicKeys_whenVotingFederatorsWithOneDifferentKey_returnsSuccessfulButDoesNotAddFederator(
        BtcECKey federatorBtcPublicKey, BtcECKey federatorRskPublicKey, BtcECKey federatorMstPublicKey
    ) {
        voteAndAssertCreateEmptyPendingFederation();

        // Act
        int firstVoteResult = voteToAddFederatorPublicKeysToPendingFederation(firstAuthorizedTx, federatorBtcKey, federatorRskKey, federatorMstKey);
        // Voting a federator with just one different key from previous one.
        // It will be considered a different federator,
        // so second vote won't add it to the pending federation
        int secondVoteResult = voteToAddFederatorPublicKeysToPendingFederation(secondAuthorizedTx, federatorBtcPublicKey, federatorRskPublicKey, federatorMstPublicKey);

        // Assert
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), firstVoteResult);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), secondVoteResult);

        // Pending federation size is 0, because we voted for different federators
        assertEquals(0, federationSupport.getPendingFederationSize());
    }

    private static Stream<Arguments> keysWithOneDifferentKeyArgProvider() {
        BtcECKey differentBtcKey = BtcECKey.fromPrivate(BigInteger.valueOf(400));
        BtcECKey differentRskKey = BtcECKey.fromPrivate(BigInteger.valueOf(500));
        BtcECKey differentMstKey = BtcECKey.fromPrivate(BigInteger.valueOf(600));

        return Stream.of(
            Arguments.of(differentBtcKey, federatorRskKey, federatorMstKey),
            Arguments.of(federatorBtcKey, differentRskKey, federatorMstKey),
            Arguments.of(federatorBtcKey, federatorRskKey, differentMstKey)
        );
    }

    @Test
    void voteAddFederatorPublicKey_whenAdding100Federators_returnsSuccessfulResponseCodeAndFedSize100() {
        // Arrange
        voteAndAssertCreateEmptyPendingFederation();

        // Act
        // Voting to add 100 federators to pending federation
        int expectedCountOfMembers = 100;
        voteAndAssertAddFederatorPublicKeysToPendingFederation(expectedCountOfMembers);

        // Assert
        assertEquals(expectedCountOfMembers, federationSupport.getPendingFederationSize());
    }


    // vote commit federation tests
    @Test
    void voteCommitFederation_withEmptyFederation_returnsInsufficientMembersResponseCode() {
        // Arrange
        voteAndAssertCreateEmptyPendingFederation();

        // Act
        int result = voteToCommitPendingFederation(firstAuthorizedTx);

        // Assert
        assertEquals(FederationChangeResponseCode.INSUFFICIENT_MEMBERS.getCode(), result);
    }

    @Test
    void voteCommitFederation_commit1MemberFederation_returnsInsufficientMembersResponseCode() {
        // Arrange
        voteAndAssertCreateEmptyPendingFederation();
        voteAndAssertAddFederatorPublicKeysToPendingFederation(federatorBtcKey, federatorRskKey, federatorMstKey);

        // Act
        int firstVoteResult = voteToCommitPendingFederation(firstAuthorizedTx);

        // Assert
        assertEquals(FederationChangeResponseCode.INSUFFICIENT_MEMBERS.getCode(), firstVoteResult);
    }

    @Test
    void voteCommitFederation_commit10MembersFederation_returnsSuccessfulResponseCode() {
        // Arrange
        voteAndAssertCreateEmptyPendingFederation();

        // Voting to add 10 federators to pending federation
        final int EXPECTED_COUNT_OF_MEMBERS = 10;
        voteAndAssertAddFederatorPublicKeysToPendingFederation(EXPECTED_COUNT_OF_MEMBERS);

        voteAndAssertCommitPendingFederation();
    }

    @Test
    void voteCommitFederation_commitFederationWith20Members_shouldNotThrow() {
        // Arrange
        voteAndAssertCreateEmptyPendingFederation();

        // Voting to add 20 federators to pending federation
        final int EXPECTED_COUNT_OF_MEMBERS = 20;
        voteAndAssertAddFederatorPublicKeysToPendingFederation(EXPECTED_COUNT_OF_MEMBERS);

        // Act and assert
        List<CallContext> shuffled = new ArrayList<>(authorized_tx_set);
        Collections.shuffle(shuffled);

        voteToCommitPendingFederation(shuffled.get(0));
        voteToCommitPendingFederation(shuffled.get(1));
        voteToCommitPendingFederation(shuffled.get(2));

        Optional<Federation> proposedFederation = storageProvider.getProposedFederation(federationMainnetConstants);
        assertTrue(proposedFederation.isPresent());

        List<BtcECKey> membersPubKeys = proposedFederation.get().getBtcPublicKeys();
        assertEquals(EXPECTED_COUNT_OF_MEMBERS, membersPubKeys.size());
    }

    @Test
    void voteCommitFederation_whenPendingFederationIsSet_shouldPerformCommitFederationActions() {
        // arrange
        Federation activeFederation = federationSupport.getActiveFederation();
        List<UTXO> activeFederationUTXOs = BitcoinTestUtils.createUTXOs(10, activeFederation.getAddress());
        storageProvider.getNewFederationBtcUTXOs().addAll(activeFederationUTXOs);

        voteAndAssertCreateEmptyPendingFederation();
        voteAndAssertAddFederationMembersPublicKeysToPendingFederation(pendingFederationToBe.getMembers());

        // act
        voteAndAssertCommitPendingFederation();

        // assertions
        // assert proposed federation was set correctly
        Optional<Federation> proposedFederationOpt = storageProvider.getProposedFederation(federationMainnetConstants);
        assertTrue(proposedFederationOpt.isPresent());
        Federation proposedFederation = proposedFederationOpt.get();
        long expectedProposedFederationCreationTimeValue = proposedFederation.getCreationTime().getEpochSecond();
        Instant expectedProposedFederationCreationTime = Instant.ofEpochSecond(RSK_EXECUTION_BLOCK_TIMESTAMP);
        assertIsTheExpectedFederation(proposedFederation, expectedProposedFederationCreationTimeValue, expectedProposedFederationCreationTime);

        assertPendingFederationVotingWasCleaned();

        assertLogCommitFederation(activeFederation, proposedFederation);

        assertNoHandoverToNewFederation();
    }


    @Test
    void commitProposedFederation_shouldPerformCommitProposedFederationActions() {
        // arrange
        storageProvider.setNewFederation(activeFederation);

        List<UTXO> activeFederationUTXOs = BitcoinTestUtils.createUTXOs(10, activeFederation.getAddress());
        storageProvider.getNewFederationBtcUTXOs().addAll(activeFederationUTXOs);

        Federation proposedFederation = P2shP2wshErpFederationBuilder.builder().build();
        storageProvider.setProposedFederation(proposedFederation);

        // act
        federationSupport.commitProposedFederation();

        // assert
        assertFalse(federationSupport.getProposedFederation().isPresent());
        assertHandoverToNewFederation(activeFederationUTXOs, proposedFederation);
    }

    // vote rollback federation tests
    @Test
    void rollbackFederation_returnsSuccessfulResponseCodeAndRollsbackThePendingFederation() {
        // Arrange
        voteAndAssertCreateEmptyPendingFederation();

        // Voting to have at least one federation member before rollback
        voteAndAssertAddFederatorPublicKeysToPendingFederation(federatorBtcKey, federatorRskKey, federatorMstKey);
        int pendingFederationSizeBeforeRollback = federationSupport.getPendingFederationSize();
        assertEquals(1, pendingFederationSizeBeforeRollback);

        // Act
        List<CallContext> shuffled = new ArrayList<>(authorized_tx_set);
        Collections.shuffle(shuffled);

        int firstVoteRollbackResult = voteToRollbackPendingFederation(shuffled.get(0));
        int secondVoteRollbackResult = voteToRollbackPendingFederation(shuffled.get(1));
        int thirdVoteRollbackResult = voteToRollbackPendingFederation(shuffled.get(2));

        // Assert
        int pendingFederationSizeAfterRollback = federationSupport.getPendingFederationSize();
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), firstVoteRollbackResult);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), secondVoteRollbackResult);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), thirdVoteRollbackResult);
        assertEquals(-1, pendingFederationSizeAfterRollback);
        assertNull(federationSupport.getPendingFederationHash());
    }

    private void assertIsTheExpectedFederation(Federation federation, long expectedCreationTimeValue, Instant expectedCreationTime) {
        assertEquals(RSK_EXECUTION_BLOCK_TIMESTAMP, expectedCreationTimeValue);
        assertEquals(RSK_EXECUTION_BLOCK_NUMBER, federation.getCreationBlockNumber());

        Federation federationBuiltFromPendingFederation = pendingFederationToBe.buildFederation(
            expectedCreationTime,
            RSK_EXECUTION_BLOCK_NUMBER,
            federationMainnetConstants
        );
        assertEquals(federationBuiltFromPendingFederation, federation);
    }

    private void voteAndAssertAddFederationMembersPublicKeysToPendingFederation(List<FederationMember> federationMembers) {
        for (FederationMember federationMember : federationMembers) {
            BtcECKey memberBtcKey = federationMember.getBtcPublicKey();
            BtcECKey memberRskKey = federationMember.getRskPublicKey();
            BtcECKey memberMstKey = federationMember.getMstPublicKey();

            voteAndAssertAddFederatorPublicKeysToPendingFederation(memberBtcKey, memberRskKey, memberMstKey);
        }
    }

    private void assertHandoverToNewFederation(List<UTXO> utxosToMove, Federation newFederation) {
        assertUTXOsWereMovedFromNewToOldFederation(utxosToMove);
        assertNewAndOldFederationsWereSet(newFederation);
        assertLastRetiredFederationScriptWasSet();
        assertNewActiveFederationCreationBlockHeightWasSet(newFederation.getCreationBlockNumber());
    }

    private void assertNoHandoverToNewFederation() {
        assertUTXOsWereNotMovedFromNewToOldFederation();
        assertNewAndOldFederationsWereNotSet();
        assertLastRetiredFederationScriptWasNotSet();
        assertNewActiveFederationCreationBlockHeightWasNotSet();
    }

    private void assertUTXOsWereMovedFromNewToOldFederation(List<UTXO> utxosToMove) {
        // assert utxos were moved from new federation to old federation
        List<UTXO> oldFederationUTXOs = storageProvider.getOldFederationBtcUTXOs();
        assertEquals(utxosToMove, oldFederationUTXOs);

        // assert new federation utxos were cleaned
        List<UTXO> newFederationUTXOs = storageProvider.getNewFederationBtcUTXOs();
        assertTrue(newFederationUTXOs.isEmpty());
    }

    private void assertUTXOsWereNotMovedFromNewToOldFederation() {
        // assert old federation utxos are still empty
        List<UTXO> oldFederationUTXOs = storageProvider.getOldFederationBtcUTXOs();
        assertTrue(oldFederationUTXOs.isEmpty());

        // assert new federation utxos are not empty
        List<UTXO> newFederationUTXOs = storageProvider.getNewFederationBtcUTXOs();
        assertFalse(newFederationUTXOs.isEmpty());
    }

    private void assertNewAndOldFederationsWereSet(Federation expectedNewFederation) {
        // assert the active federation was set as the old federation
        Federation oldFederation = storageProvider.getOldFederation(federationMainnetConstants);
        assertEquals(federationSupport.getActiveFederation(), oldFederation);

        // assert the federation built from the pending one was set as the new federation
        Federation newFederation = storageProvider.getNewFederation(federationMainnetConstants);
        assertEquals(expectedNewFederation, newFederation);
    }

    private void assertNewAndOldFederationsWereNotSet() {
        // assert old federation is still null
        Federation oldFederation = storageProvider.getOldFederation(federationMainnetConstants);
        assertNull(oldFederation);

        // assert new federation is still the active federation
        Federation newFederation = storageProvider.getNewFederation(federationMainnetConstants);
        assertEquals(activeFederation, newFederation);
    }

    private void assertPendingFederationVotingWasCleaned() {
        assertNull(storageProvider.getPendingFederation());

        Map<ABICallSpec, List<Address>> federationElectionVotes = storageProvider.getFederationElection(federationMainnetConstants.getFederationChangeAuthorizer()).getVotes();
        assertTrue(federationElectionVotes.isEmpty());
    }

    private void assertNewActiveFederationCreationBlockHeightWasSet(long expectedNextFederationCreationBlockHeight) {
        Optional<Long> nextFederationCreationBlockHeight = storageProvider.getNextFederationCreationBlockHeight();
        assertTrue(nextFederationCreationBlockHeight.isPresent());
        assertEquals(expectedNextFederationCreationBlockHeight, nextFederationCreationBlockHeight.get());
    }

    private void assertLastRetiredFederationScriptWasSet() {
        ErpFederation activeFederationCasted = (ErpFederation) federationSupport.getActiveFederation();
        Script activeFederationMembersP2SHScript = activeFederationCasted.getDefaultP2SHScript();
        Optional<Script> lastRetiredFederationP2SHScript = storageProvider.getLastRetiredFederationP2SHScript();
        assertTrue(lastRetiredFederationP2SHScript.isPresent());
        assertEquals(activeFederationMembersP2SHScript, lastRetiredFederationP2SHScript.get());
    }

    private void assertNewActiveFederationCreationBlockHeightWasNotSet() {
        Optional<Long> nextFederationCreationBlockHeight = storageProvider.getNextFederationCreationBlockHeight();
        assertFalse(nextFederationCreationBlockHeight.isPresent());
    }

    private void assertLastRetiredFederationScriptWasNotSet() {
        Optional<Script> lastRetiredFederationP2SHScript = storageProvider.getLastRetiredFederationP2SHScript();
        assertFalse(lastRetiredFederationP2SHScript.isPresent());
    }

    private void assertLogCommitFederation(Federation federationToBeRetired, Federation votedFederation) {
        Bytes encodedData = getEncodedData(federationToBeRetired, votedFederation);

        // assert the event was emitted just once with the expected topic and data
        List<Log> logs = host.logs();
        assertEquals(1, logs.size());

        Log log = logs.get(0);
        assertEquals(commitFederationEvent.encodeEventTopics(), log.getTopics());
        assertEquals(encodedData, log.getData());
    }

    private Bytes getEncodedData(Federation federationToBeRetired, Federation votedFederation) {
        byte[] oldFederationFlatPubKeys = flatKeysAsByteArray(federationToBeRetired.getBtcPublicKeys());
        String oldFederationBtcAddress = federationToBeRetired.getAddress().toBase58();
        byte[] newFederationFlatPubKeys = flatKeysAsByteArray(votedFederation.getBtcPublicKeys());
        String newFederationBtcAddress = votedFederation.getAddress().toBase58();
        long newFedActivationBlockNumber = RSK_EXECUTION_BLOCK_NUMBER + federationMainnetConstants.getFederationActivationAge();

        return commitFederationEvent.encodeEventData(
            oldFederationFlatPubKeys,
            oldFederationBtcAddress,
            newFederationFlatPubKeys,
            newFederationBtcAddress,
            newFedActivationBlockNumber
        );
    }

    // utility methods
    private int voteToCreatePendingFederation(CallContext tx) {
        ABICallSpec createFederationAbiCallSpec = new ABICallSpec(FederationChangeFunction.CREATE.getKey(), new byte[][]{});
        return federationSupport.voteFederationChange(tx, createFederationAbiCallSpec, bridgeEventLogger);
    }

    private void voteAndAssertCreateEmptyPendingFederation() {
        // Voting with enough authorizers to create the pending federation
        List<CallContext> shuffled = new ArrayList<>(authorized_tx_set);
        Collections.shuffle(shuffled);

        int resultFromFirstAuthorizer = voteToCreatePendingFederation(shuffled.get(0));
        int resultFromSecondAuthorizer = voteToCreatePendingFederation(shuffled.get(1));
        int resultFromThirdAuthorizer = voteToCreatePendingFederation(shuffled.get(2));

        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), resultFromFirstAuthorizer);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), resultFromSecondAuthorizer);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), resultFromThirdAuthorizer);

        assertEquals(0, federationSupport.getPendingFederationSize());
        assertNotNull(federationSupport.getPendingFederationHash());
    }

    private int voteToAddFederatorPublicKeysToPendingFederation(CallContext tx, BtcECKey btcPublicKey, BtcECKey rskPublicKey, BtcECKey mstPublicKey) {
        ABICallSpec addFederatorAbiCallSpec = new ABICallSpec(FederationChangeFunction.ADD_MULTI.getKey(),
            new byte[][]{ btcPublicKey.getPubKey(), PublicKeys.uncompressed(rskPublicKey), PublicKeys.uncompressed(mstPublicKey) }
        );

        return federationSupport.voteFederationChange(tx, addFederatorAbiCallSpec, bridgeEventLogger);
    }

    private void voteAndAssertAddFederatorPublicKeysToPendingFederation(BtcECKey btcPublicKey, BtcECKey rskPublicKey, BtcECKey mstPublicKey) {
        List<CallContext> shuffled = new ArrayList<>(authorized_tx_set);
        Collections.shuffle(shuffled);

        int resultFromFirstAuthorizer = voteToAddFederatorPublicKeysToPendingFederation(shuffled.get(0), btcPublicKey, rskPublicKey, mstPublicKey);
        int resultFromSecondAuthorizer = voteToAddFederatorPublicKeysToPendingFederation(shuffled.get(1), btcPublicKey, rskPublicKey, mstPublicKey);
        int resultFromThirdAuthorizer = voteToAddFederatorPublicKeysToPendingFederation(shuffled.get(2), btcPublicKey, rskPublicKey, mstPublicKey);

        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), resultFromFirstAuthorizer);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), resultFromSecondAuthorizer);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), resultFromThirdAuthorizer);
    }

    private void voteAndAssertAddFederatorPublicKeysToPendingFederation(int amountOfMembers) {
        for (int i = 0; i < amountOfMembers; i++) {
            BtcECKey memberBtcKey = BtcECKey.fromPrivate(BigInteger.valueOf(i + 100));
            BtcECKey memberRskKey = BtcECKey.fromPrivate(BigInteger.valueOf(i + 101));
            BtcECKey memberMstKey = BtcECKey.fromPrivate(BigInteger.valueOf(i + 102));

            voteAndAssertAddFederatorPublicKeysToPendingFederation(memberBtcKey, memberRskKey, memberMstKey);
        }
    }

    private int voteToCommitPendingFederation(CallContext tx) {
        Hash pendingFederationHash = federationSupport.getPendingFederationHash();
        ABICallSpec commitFederationAbiCallSpec = new ABICallSpec(FederationChangeFunction.COMMIT.getKey(), new byte[][]{ pendingFederationHash.getBytes().toArrayUnsafe() });

        return federationSupport.voteFederationChange(tx, commitFederationAbiCallSpec, bridgeEventLogger);
    }

    private void voteAndAssertCommitPendingFederation() {
        List<CallContext> shuffled = new ArrayList<>(authorized_tx_set);
        Collections.shuffle(shuffled);

        int firstVoteResult = voteToCommitPendingFederation(shuffled.get(0));
        int secondVoteResult = voteToCommitPendingFederation(shuffled.get(1));
        int thirdVoteResult = voteToCommitPendingFederation(shuffled.get(2));

        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), firstVoteResult);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), secondVoteResult);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), thirdVoteResult);
    }

    private int voteToRollbackPendingFederation(CallContext tx) {
        ABICallSpec rollbackAbiCallSpec = new ABICallSpec(FederationChangeFunction.ROLLBACK.getKey(), new byte[][]{});
        return federationSupport.voteFederationChange(tx, rollbackAbiCallSpec, bridgeEventLogger);
    }
}
