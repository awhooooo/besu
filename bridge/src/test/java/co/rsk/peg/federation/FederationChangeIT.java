package co.rsk.peg.federation;

import static co.rsk.peg.BridgeEventsTestUtils.*;
import static co.rsk.peg.BridgeSupportTestUtil.*;
import static co.rsk.peg.bitcoin.UtxoUtils.extractOutpointValues;
import static co.rsk.peg.federation.FederationStorageIndexKey.NEW_FEDERATION_BTC_UTXOS_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.script.*;
import co.rsk.bitcoinj.store.BtcBlockStore;
import co.rsk.peg.*;
import co.rsk.peg.PegoutsWaitingForConfirmations.Entry;
import co.rsk.peg.bitcoin.*;
import co.rsk.peg.btcLockSender.BtcLockSenderProvider;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.feeperkb.FeePerKbSupport;
import co.rsk.peg.lockingcap.*;
import co.rsk.peg.pegininstructions.PeginInstructionsProvider;
import co.rsk.peg.host.CallContext;
import co.rsk.peg.host.InMemoryBridgeHost;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.storage.StorageAccessor;
import co.rsk.peg.utils.Weis;
import co.rsk.peg.utils.BridgeEventLogger;
import co.rsk.peg.utils.BridgeEventLoggerImpl;
import co.rsk.peg.vote.ABICallSpec;
import co.rsk.test.builders.BridgeSupportBuilder;
import co.rsk.test.builders.FederationSupportBuilder;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

import org.bouncycastle.util.encoders.Hex;
import co.rsk.peg.abi.AbiFunction;
import co.rsk.peg.utils.PublicKeys;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.LogTopic;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.junit.jupiter.api.Test;

class FederationChangeIT {
    private static final BridgeConstants BRIDGE_CONSTANTS = BridgeMainNetConstants.getInstance();
    private static final FederationConstants FEDERATION_CONSTANTS = BRIDGE_CONSTANTS.getFederationConstants();
    private static final NetworkParameters NETWORK_PARAMS = BRIDGE_CONSTANTS.getBtcParams();
    private static final List<BtcECKey> ORIGINAL_FEDERATION_MEMBERS_KEYS =
        BitcoinTestUtils.getBtcEcKeysFromSeeds(
            new String[]{
                "member01", "member02", "member03", "member04", "member05", "member06", "member07", "member08", "member09"}, true);
    private static final List<FederationMember> ORIGINAL_FEDERATION_MEMBERS = FederationTestUtils.getFederationMembersWithBtcKeys(ORIGINAL_FEDERATION_MEMBERS_KEYS);
    private static final List<BtcECKey> NEW_FEDERATION_MEMBERS_KEYS = BitcoinTestUtils.getBtcEcKeysFromSeeds(new String[]{
        "member01", "member02", "member03", "member04", "member05", "member06", "member07", "member08", "member09", "newMember10",
        "newMember11", "newMember12", "newMember13", "newMember14", "newMember15", "newMember16", "newMember17", "newMember18", "newMember19", "newMember20"
    }, true);
    private static final int NEW_FEDERATION_MEMBERS_SIZE = 20;
    private static final int NEW_FEDERATION_THRESHOLD = NEW_FEDERATION_MEMBERS_SIZE / 2 + 1;
    private static final List<FederationMember> NEW_FEDERATION_MEMBERS = FederationTestUtils.getFederationMembersWithBtcKeys(NEW_FEDERATION_MEMBERS_KEYS);
    private static final CallContext UPDATE_COLLECTIONS_TX = buildUpdateCollectionsTx();
    private static final CallContext FIRST_AUTHORIZED_TX = PegTestUtils.callFrom(FederationChangeCaller.FIRST_AUTHORIZED.getAddress());
    private static final CallContext SECOND_AUTHORIZED_TX = PegTestUtils.callFrom(FederationChangeCaller.SECOND_AUTHORIZED.getAddress());
    private static final CallContext THIRD_AUTHORIZED_TX = PegTestUtils.callFrom(FederationChangeCaller.THIRD_AUTHORIZED.getAddress());
    private static final CallContext FOURTH_AUTHORIZED_TX = PegTestUtils.callFrom(FederationChangeCaller.FOURTH_AUTHORIZED.getAddress());
    private static final CallContext FIFTH_AUTHORIZED_TX = PegTestUtils.callFrom(FederationChangeCaller.FIFTH_AUTHORIZED.getAddress());
    private static final List<CallContext> AUTHORIZED_FEDERATION_CHANGE_TXS = List.of(FIRST_AUTHORIZED_TX, SECOND_AUTHORIZED_TX, THIRD_AUTHORIZED_TX, FOURTH_AUTHORIZED_TX, FIFTH_AUTHORIZED_TX);
    private static final CallContext FIRST_AUTHORIZED_INCREASE_LOCKING_CAP_TX = PegTestUtils.callFrom(LockingCapCaller.FIRST_AUTHORIZED.getRskAddress());
    private static final CallContext SECOND_AUTHORIZED_INCREASE_LOCKING_CAP_TX = PegTestUtils.callFrom(LockingCapCaller.SECOND_AUTHORIZED.getRskAddress());
    private static final CallContext THIRD_AUTHORIZED_INCREASE_LOCKING_CAP_TX = PegTestUtils.callFrom(LockingCapCaller.THIRD_AUTHORIZED.getRskAddress());
    private static final List<CallContext> AUTHORIZED_INCREASE_LOCKING_CAP_TXS = List.of(FIRST_AUTHORIZED_INCREASE_LOCKING_CAP_TX, SECOND_AUTHORIZED_INCREASE_LOCKING_CAP_TX, THIRD_AUTHORIZED_INCREASE_LOCKING_CAP_TX);

    private static final CallContext REGISTRATION_TX = PegTestUtils.callWithHash(PegTestUtils.createHash3(1));
    private Address userRefundBtcAddress;

    private InMemoryBridgeHost host;
    private BridgeStorageProvider bridgeStorageProvider;
    private BtcBlockStoreWithCache.Factory btcBlockStoreFactory;
    private BtcBlockStoreWithCache btcBlockStore;
    private BtcLockSenderProvider btcLockSenderProvider;
    private PeginInstructionsProvider peginInstructionsProvider;
    private BridgeEventLogger bridgeEventLogger;
    private FeePerKbSupport feePerKbSupport;
    private long currentBlockNumber;
    private StorageAccessor bridgeStorageAccessor;
    private FederationStorageProvider federationStorageProvider;
    private FederationSupport federationSupport;
    private LockingCapSupport lockingCapSupport;
    private BridgeSupport bridgeSupport;
    private PartialMerkleTree pmtWithTransactions;
    private int btcBlockWithPmtHeight;

    @Test
    void whenAllActivationsArePresentAndFederationChanges_shouldSuccessfullyChangeFederation() throws Exception {
        // Arrange
        setUp();

        // Increase locking cap to max value
        while (true) {
            Coin lockingCap = lockingCapSupport.getLockingCap().get();
            if (lockingCap.equals(NetworkParameters.MAX_MONEY)) {
                break;
            }

            CallContext selectedIncreaseLockingCapVoter = AUTHORIZED_INCREASE_LOCKING_CAP_TXS.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(AUTHORIZED_INCREASE_LOCKING_CAP_TXS.size()));
            if (lockingCap.isGreaterThan(NetworkParameters.MAX_MONEY.div(2))) {
                voteIncreaseLockingCapTransaction(selectedIncreaseLockingCapVoter, NetworkParameters.MAX_MONEY);
            } else {
                voteIncreaseLockingCapTransaction(selectedIncreaseLockingCapVoter, lockingCap.multiply(2));
            }
        }
        assertEquals(co.rsk.bitcoinj.core.NetworkParameters.MAX_MONEY, lockingCapSupport.getLockingCap().get());

        // Create a default original federation using the list of UTXOs
        var originalFederation = createOriginalFederation();
        var originalUTXOs = federationStorageProvider.getNewFederationBtcUTXOs();

        // Act & Assert
        assertPeginsShouldWorkToFed(originalFederation, federationSupport.getActiveFederationBtcUTXOs(), "sender0");
        assertPegoutsShouldWorkToFed(originalFederation, federationSupport.getActiveFederationBtcUTXOs(), "sender0");
        // Create pending federation using the new federation keys
        voteToCreateEmptyPendingFederation();
        voteToAddFederatorPublicKeysToPendingFederation();

        var pendingFederation = federationStorageProvider.getPendingFederation();
        assertPendingFederationIsBuiltAsExpected(pendingFederation);

        voteToCommitPendingFederation();
        var newFederationOpt = federationSupport.getProposedFederation();
        assertTrue(newFederationOpt.isPresent());
        var newFederation = newFederationOpt.get();
        var expectedProposedFederation = createExpectedProposedFederation();
        assertEquals(expectedProposedFederation, newFederation);

        assertPeginsShouldNotWorkToFed(newFederation, "sender1");

        // Proceed with SVP process
        callUpdateCollectionsAndAssertSvpFundTxIsCreated();
        registerSignedSvpFundTx();

        assertPeginsShouldWorkToFed(originalFederation, federationSupport.getActiveFederationBtcUTXOs(), "sender2");
        assertPegoutsShouldWorkToFed(originalFederation, federationSupport.getActiveFederationBtcUTXOs(), "sender2");
        assertPeginsShouldNotWorkToFed(newFederation, "sender3");
        assertPegoutsShouldNotWorkToFed(newFederation, "sender3");

        callUpdateCollectionsAndAssertSvpSpendTxIsCreated();
        addSignaturesToAndRegisterSvpSpendTx();

        // Validations post commit
        assertLastRetiredFederationP2SHScriptMatchesWithOriginalFederation(originalFederation);
        assertUTXOsReferenceMovedFromNewToOldFederation(originalUTXOs);
        assertNewAndOldFederationsReferences(newFederation, originalFederation);
        assertNextFederationCreationBlockHeight(newFederation.getCreationBlockNumber());

        assertPeginsShouldWorkToFed(originalFederation, federationSupport.getActiveFederationBtcUTXOs(), "sender4");
        assertPegoutsShouldWorkToFed(originalFederation, federationSupport.getActiveFederationBtcUTXOs(), "sender4");
        assertPeginsShouldNotWorkToFed(newFederation, "sender5");
        assertPegoutsShouldNotWorkToFed(newFederation, "sender5");

        // Move blockchain until the activation phase
        activateNewFederation();
        assertActiveAndRetiringFederationsHaveExpectedAddress(newFederation.getAddress(), originalFederation.getAddress());
        assertMigrationHasNotStarted();

        assertPeginsShouldWorkToFed(originalFederation, federationSupport.getRetiringFederationBtcUTXOs(), "sender6");
        assertPegoutsShouldWorkToFed(originalFederation, federationSupport.getRetiringFederationBtcUTXOs(), "sender6");
        assertPeginsShouldWorkToFed(newFederation, federationSupport.getActiveFederationBtcUTXOs(), "sender7");
        assertPegoutsShouldWorkToFed(newFederation, federationSupport.getActiveFederationBtcUTXOs(), "sender7");

        // Move blockchain until the migration phase
        activateMigration();

        // Calling update collections should start migration
        callUpdateCollections();
        assertMigrationHasStarted();
        assertPegoutTxSigHashesAreSaved();
        assertReleaseBtcRequestedEventEventWasEmitted();
        assertPegoutTransactionCreatedEventWasEmitted();
        verifyPegouts();

        // Check again live federations references are as expected
        assertNewAndOldFederationsReferences(newFederation, originalFederation);
        assertActiveAndRetiringFederationsHaveExpectedAddress(newFederation.getAddress(), originalFederation.getAddress());

        assertPeginsShouldWorkToFed(originalFederation, federationSupport.getRetiringFederationBtcUTXOs(), "sender8");
        assertPegoutsShouldWorkToFed(originalFederation, federationSupport.getRetiringFederationBtcUTXOs(), "sender8");
        assertPeginsShouldWorkToFed(newFederation, federationSupport.getActiveFederationBtcUTXOs(), "sender9");
        assertPegoutsShouldWorkToFed(newFederation, federationSupport.getActiveFederationBtcUTXOs(), "sender9");

        // Move blockchain until the end of the migration phase
        long migrationCreationRskBlockNumber = currentBlockNumber;
        endMigration();
        assertPegoutConfirmedEventEventWasEmitted(migrationCreationRskBlockNumber);

        assertOnlyActiveFedIsLive(newFederation);
        assertPeginsShouldNotWorkToFed(originalFederation, "sender10");
        assertPegoutsShouldNotWorkToFed(originalFederation, "sender10");
        assertPeginsShouldWorkToFed(newFederation, federationSupport.getActiveFederationBtcUTXOs(), "sender11");
        assertPegoutsShouldWorkToFed(newFederation, federationSupport.getActiveFederationBtcUTXOs(), "sender11");
    }

    private void setUp() throws Exception {
        host = new InMemoryBridgeHost();
        host.balance(BridgeAddresses.BRIDGE, Weis.fromSatoshis(BRIDGE_CONSTANTS.getMaxRbtc()));

        bridgeStorageProvider =
            new BridgeStorageProvider(new BridgeStorageAccessorImpl(host), NETWORK_PARAMS);

        btcBlockStoreFactory =
            new RepositoryBtcBlockStoreWithCache.Factory(NETWORK_PARAMS, 100, 100);
        btcBlockStore =
            btcBlockStoreFactory.newInstance(host, BRIDGE_CONSTANTS, bridgeStorageProvider);
        // Setting a chain head different from genesis to avoid having to read the checkpoints file
        addNewBtcBlockOnTipOfChain(btcBlockStore);

        peginInstructionsProvider = new PeginInstructionsProvider();
        btcLockSenderProvider = new BtcLockSenderProvider();

        bridgeEventLogger = new BridgeEventLoggerImpl(BRIDGE_CONSTANTS, host);

        bridgeStorageAccessor = new BridgeStorageAccessorImpl(new InMemoryBridgeHost());

        federationStorageProvider = new FederationStorageProviderImpl(bridgeStorageAccessor);

        var blockNumber = 0L;
        currentBlockNumber = blockNumber;
        host.blockNumber(currentBlockNumber);

        federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(FEDERATION_CONSTANTS)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        var lockingCapStorageProvider = new LockingCapStorageProviderImpl(bridgeStorageAccessor);
        lockingCapSupport = new LockingCapSupportImpl(
            lockingCapStorageProvider,
            BRIDGE_CONSTANTS.getLockingCapConstants());

        feePerKbSupport = mock(FeePerKbSupport.class);
        when(feePerKbSupport.getFeePerKb()).thenReturn(Coin.SATOSHI);

        bridgeSupport = BridgeSupportBuilder.builder()
            .withProvider(bridgeStorageProvider)
            .withEventLogger(bridgeEventLogger)
            .withHost(host)
            .withBridgeConstants(BRIDGE_CONSTANTS)
            .withBtcBlockStoreFactory(btcBlockStoreFactory)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(peginInstructionsProvider)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .withLockingCapSupport(lockingCapSupport)
            .build();
    }

    private Federation createOriginalFederation() {
        var originalFederationArgs = new FederationArgs(
            ORIGINAL_FEDERATION_MEMBERS,
            Instant.EPOCH,
            0,
            NETWORK_PARAMS);
        var erpPubKeys = FEDERATION_CONSTANTS.getErpFedPubKeysList();
        var activationDelay = FEDERATION_CONSTANTS.getErpFedActivationDelay();

        Federation originalFederation = FederationFactory.buildP2shErpFederation(originalFederationArgs, erpPubKeys, activationDelay);
        // Set original federation
        federationStorageProvider.setNewFederation(originalFederation);

        // Set new UTXOs
        var originalUTXOs = createUTXOs(originalFederation.getAddress());
        bridgeStorageAccessor.saveToRepository(NEW_FEDERATION_BTC_UTXOS_KEY.getKey(), originalUTXOs, BridgeSerializationUtils::serializeUTXOList);

        return originalFederation;
    }  

    private Federation createExpectedProposedFederation() {
        var expectedFederationArgs =
            new FederationArgs(NEW_FEDERATION_MEMBERS, Instant.EPOCH, 0, NETWORK_PARAMS);
        var erpPubKeys = FEDERATION_CONSTANTS.getErpFedPubKeysList();
        var activationDelay = FEDERATION_CONSTANTS.getErpFedActivationDelay();

        return FederationFactory.buildP2shP2wshErpFederation(expectedFederationArgs, erpPubKeys, activationDelay);
    }

    private int voteToCreatePendingFederation(CallContext tx) {
        var createFederationAbiCallSpec = new ABICallSpec(FederationChangeFunction.CREATE.getKey(), new byte[][]{});
        return federationSupport.voteFederationChange(tx, createFederationAbiCallSpec, bridgeEventLogger);
    }

    private int voteToAddFederatorPublicKeysToPendingFederation(CallContext tx, BtcECKey btcPublicKey, BtcECKey rskPublicKey, BtcECKey mstPublicKey) {
        ABICallSpec addFederatorAbiCallSpec = new ABICallSpec(FederationChangeFunction.ADD_MULTI.getKey(),
            new byte[][]{ btcPublicKey.getPubKey(), rskPublicKey.getPubKey(), mstPublicKey.getPubKey() }
        );

        return federationSupport.voteFederationChange(tx, addFederatorAbiCallSpec, bridgeEventLogger);
    }

    private int voteCommitPendingFederation(CallContext tx) {
        var pendingFederationHash = federationSupport.getPendingFederationHash();
        var commitFederationAbiCallSpec = new ABICallSpec(FederationChangeFunction.COMMIT.getKey(), new byte[][]{ pendingFederationHash.getBytes().toArrayUnsafe() });

        return federationSupport.voteFederationChange(tx, commitFederationAbiCallSpec, bridgeEventLogger);
    }

    private void voteIncreaseLockingCapTransaction(CallContext tx, co.rsk.bitcoinj.core.Coin amount) throws LockingCapIllegalArgumentException {
        bridgeSupport.increaseLockingCap(tx, amount);
    }
  
    private void voteToCreateEmptyPendingFederation() {
        // Voting with enough authorizers to create the pending federation
        List<CallContext> shuffled = new ArrayList<>(AUTHORIZED_FEDERATION_CHANGE_TXS);
        Collections.shuffle(shuffled);

        var resultFromFirstAuthorizer = voteToCreatePendingFederation(shuffled.get(0));
        var resultFromSecondAuthorizer = voteToCreatePendingFederation(shuffled.get(1));
        var resultFromThirdAuthorizer = voteToCreatePendingFederation(shuffled.get(2));

        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), resultFromFirstAuthorizer);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), resultFromSecondAuthorizer);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), resultFromThirdAuthorizer);

        assertEquals(0, federationSupport.getPendingFederationSize());
        assertNotNull(federationSupport.getPendingFederationHash());
    }

    private void voteToAddFederatorPublicKeysToPendingFederation(BtcECKey btcPublicKey, BtcECKey rskPublicKey, BtcECKey mstPublicKey) {
        List<CallContext> shuffled = new ArrayList<>(AUTHORIZED_FEDERATION_CHANGE_TXS);
        Collections.shuffle(shuffled);

        int resultFromFirstAuthorizer = voteToAddFederatorPublicKeysToPendingFederation(shuffled.get(0), btcPublicKey, rskPublicKey, mstPublicKey);
        int resultFromSecondAuthorizer = voteToAddFederatorPublicKeysToPendingFederation(shuffled.get(1), btcPublicKey, rskPublicKey, mstPublicKey);
        int resultFromThirdAuthorizer = voteToAddFederatorPublicKeysToPendingFederation(shuffled.get(2), btcPublicKey, rskPublicKey, mstPublicKey);

        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), resultFromFirstAuthorizer);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), resultFromSecondAuthorizer);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), resultFromThirdAuthorizer);
    }

    private void voteToAddFederatorPublicKeysToPendingFederation() {
        var expectedPendingFederationSize = 0;

        for (FederationMember member : NEW_FEDERATION_MEMBERS) {
            var memberBtcKey = member.getBtcPublicKey();
            var memberRskKey = member.getRskPublicKey();
            var memberMstKey = member.getMstPublicKey();

            voteToAddFederatorPublicKeysToPendingFederation(memberBtcKey, memberRskKey, memberMstKey);

            assertEquals(++expectedPendingFederationSize, federationSupport.getPendingFederationSize());
            assertTrue(federationStorageProvider.getPendingFederation().getMembers().contains(member));
        }
    }

    private void voteToCommitPendingFederation() {
        // Pending Federation should exist
        var pendingFederation = federationStorageProvider.getPendingFederation();
        assertNotNull(pendingFederation);

        List<CallContext> shuffled = new ArrayList<>(AUTHORIZED_FEDERATION_CHANGE_TXS);
        Collections.shuffle(shuffled);

        var firstVoteResult = voteCommitPendingFederation(shuffled.get(0));
        var secondVoteResult = voteCommitPendingFederation(shuffled.get(1));
        var thirdVoteResult = voteCommitPendingFederation(shuffled.get(2));

        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), firstVoteResult);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), secondVoteResult);
        assertEquals(FederationChangeResponseCode.SUCCESSFUL.getCode(), thirdVoteResult);

        // Since the proposed federation is committed, it should be null in storage
        assertNull(federationStorageProvider.getPendingFederation());
    }

    private void callUpdateCollectionsAndAssertSvpFundTxIsCreated() throws Exception {
        // Get UTXO size before creating fund tx
        var activeFederationUtxosSizeBeforeCreatingFundTx =
            federationSupport.getActiveFederationBtcUTXOs().size();

        // Next call to update collections will create svp fund tx
        bridgeSupport.updateCollections(UPDATE_COLLECTIONS_TX);
        bridgeSupport.save();

        var svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();
        assertTrue(svpFundTxHashUnsigned.isPresent());
        assertEquals(activeFederationUtxosSizeBeforeCreatingFundTx - 1, federationSupport.getActiveFederationBtcUTXOs().size());
    }

    private void registerSignedSvpFundTx() throws Exception {
        var pegoutsTxs =
            bridgeStorageProvider.getPegoutsWaitingForConfirmations().getEntries().stream().toList();
        assertEquals(1, pegoutsTxs.size());
        var svpFundTx = new BtcTransaction(NETWORK_PARAMS, pegoutsTxs.get(0).getBtcTransaction().bitcoinSerialize());

        int neededSignatures = federationSupport.getActiveFederationThreshold();
        signInputs(svpFundTx, ORIGINAL_FEDERATION_MEMBERS_KEYS.subList(0, neededSignatures));

        int activeFederationUtxosSizeBeforeRegisteringTx = federationSupport.getActiveFederationBtcUTXOs().size();
        registerBtcTransaction(svpFundTx);

        assertEquals(activeFederationUtxosSizeBeforeRegisteringTx + 1, federationSupport.getActiveFederationBtcUTXOs().size());
        var svpFundTxHashUnsigned = bridgeStorageProvider.getSvpFundTxHashUnsigned();
        assertFalse(svpFundTxHashUnsigned.isPresent());
        var svpFundTransactionSigned = bridgeStorageProvider.getSvpFundTxSigned();
        assertTrue(svpFundTransactionSigned.isPresent());

        // simulate removal to leave state clean
        assertTrue(bridgeStorageProvider.getPegoutsWaitingForConfirmations().removeEntry(pegoutsTxs.get(0)));
    }

    private void callUpdateCollectionsAndAssertSvpSpendTxIsCreated() throws Exception {
        // Next call to update collections will create svp spend tx
        bridgeSupport.updateCollections(UPDATE_COLLECTIONS_TX);
        bridgeSupport.save();

        var svpFundTransactionSigned = bridgeStorageProvider.getSvpFundTxSigned();
        assertFalse(svpFundTransactionSigned.isPresent());
        var svpSpendTransactionHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();
        assertTrue(svpSpendTransactionHashUnsigned.isPresent());
        var svpSpendTxWaitingForSignatures = bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();
        assertTrue(svpSpendTxWaitingForSignatures.isPresent());
    }

    private void addSignaturesToAndRegisterSvpSpendTx() throws Exception {
        var svpSpendTxWaitingForSignatures = bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();
        assertTrue(svpSpendTxWaitingForSignatures.isPresent());

        var proposedFederation = federationSupport.getProposedFederation();
        assertTrue(proposedFederation.isPresent());

        // Add the signatures for the svp spend tx
        var svpSpendTxCreationHash = svpSpendTxWaitingForSignatures.get().getKey();
        var svpSpendTx = svpSpendTxWaitingForSignatures.get().getValue();
        var svpSpendTxSigHashes = IntStream.range(0, svpSpendTx.getInputs().size())
            .mapToObj(i -> BitcoinUtils.generateSigHashForSegwitTransactionInput(svpSpendTx, i, svpSpendTx.getInput(i).getValue()))
            .toList();

        for (BtcECKey proposedFederatorSignerKey : NEW_FEDERATION_MEMBERS_KEYS.subList(0, NEW_FEDERATION_THRESHOLD)) {
            List<byte[]> signatures = BitcoinTestUtils.generateSignerEncodedSignatures(proposedFederatorSignerKey, svpSpendTxSigHashes);
            bridgeSupport.addSignature(proposedFederatorSignerKey, signatures, svpSpendTxCreationHash);
            assertFederatorSigning(
                svpSpendTxCreationHash.getBytes().toArrayUnsafe(),
                svpSpendTx,
                svpSpendTxSigHashes,
                proposedFederation.get(),
                proposedFederatorSignerKey,
                host.logs()
            );
        }

        // Verify that the svp spend tx was released
        assertLogReleaseBtc(svpSpendTxCreationHash, svpSpendTx);
        svpSpendTxWaitingForSignatures = bridgeStorageProvider.getSvpSpendTxWaitingForSignatures();
        assertFalse(svpSpendTxWaitingForSignatures.isPresent());

        var activeFederationUtxosSizeBeforeRegisteringTx = federationSupport.getActiveFederationBtcUTXOs().size();
        // Register the svp spend tx
        registerBtcTransaction(svpSpendTx);

        assertEquals(activeFederationUtxosSizeBeforeRegisteringTx + 1, federationSupport.getActiveFederationBtcUTXOs().size());
        var svpSpendTxHashUnsigned = bridgeStorageProvider.getSvpSpendTxHashUnsigned();
        assertFalse(svpSpendTxHashUnsigned.isPresent());
        var newFederationOpt = federationSupport.getProposedFederation();
        assertFalse(newFederationOpt.isPresent());
    }

    private void assertLogReleaseBtc(Hash rskTxHash, BtcTransaction btcTx) {
        AbiFunction releaseBtcEvent = BridgeEvents.RELEASE_BTC.getEvent();

        byte[] rskTxHashSerialized = rskTxHash.getBytes().toArrayUnsafe();
        List<LogTopic> encodedTopics = getEncodedTopics(releaseBtcEvent, rskTxHashSerialized);

        byte[] btcTxSerialized = btcTx.bitcoinSerialize();
        Bytes encodedData = getEncodedData(releaseBtcEvent, btcTxSerialized);

        assertEventWasEmittedWithExpectedTopics(encodedTopics);
        assertEventWasEmittedWithExpectedData(encodedData);
    }

    private void assertEventWasEmittedWithExpectedTopics(List<LogTopic> expectedTopics) {
        Optional<Log> topicOpt = getLogsTopics(host.logs(), expectedTopics);
        assertTrue(topicOpt.isPresent());
    }

    private void assertEventWasEmittedWithExpectedData(Bytes expectedData) {
        Optional<Log> data = getLogsData(host.logs(), expectedData);
        assertTrue(data.isPresent());
    }
    
    private void activateNewFederation() {
        // Move the required blocks ahead for the new powpeg to become active
        var blockNumber = 
            currentBlockNumber + FEDERATION_CONSTANTS.getFederationActivationAge();
        currentBlockNumber = blockNumber;
        host.blockNumber(currentBlockNumber);

        advanceBlockchainTo();
    }

    private void activateMigration() {
        // Move the required blocks ahead for the new federation to start migrating,
        // adding 1 as the migration is exclusive
        var blockNumber = 
            currentBlockNumber + FEDERATION_CONSTANTS.getFundsMigrationAgeSinceActivationBegin() + 1L;
        currentBlockNumber = blockNumber;
        host.blockNumber(currentBlockNumber);

        advanceBlockchainTo();
    }

    private void endMigration() throws Exception {
        // Move the required blocks ahead for the new federation to finish migrating,
        // adding 1 as the migration is exclusive
        var blockNumber = 
            currentBlockNumber + FEDERATION_CONSTANTS.getFundsMigrationAgeSinceActivationEnd() + 1L;
        currentBlockNumber = blockNumber;
        host.blockNumber(currentBlockNumber);

        advanceBlockchainTo();

        // The first update collections after the migration finished should get rid of the retiring powpeg
        bridgeSupport.updateCollections(UPDATE_COLLECTIONS_TX);
        bridgeSupport.save();
    }

    private void callUpdateCollections() throws Exception {
        bridgeSupport.updateCollections(UPDATE_COLLECTIONS_TX);
        bridgeSupport.save();
    }

    private void assertPeginsShouldWorkToFed(Federation federation, List<UTXO> federationUtxosReference, String senderSeed) throws Exception {
        var federationAddress = federation.getAddress();
        assertLegacyP2pkhPeginWorks(federationAddress, federationUtxosReference, senderSeed);
        assertLegacyP2shP2wpkhPeginWorks(federationAddress, federationUtxosReference, senderSeed);
        assertPeginV1Works(federationAddress, federationUtxosReference, senderSeed);
    }

    private void assertLegacyP2pkhPeginWorks(Address federationAddress, List<UTXO> federationUtxosReference, String senderSeed) throws Exception {
        var legacyP2pkhPeginToFed = createLegacyP2pkhPegin(federationAddress, senderSeed);
        assertPeginWorks(legacyP2pkhPeginToFed, federationUtxosReference);
    }

    private void assertLegacyP2shP2wpkhPeginWorks(Address federationAddress, List<UTXO> federationUtxosReference, String senderSeed) throws Exception {
        var legacyP2shP2wpkhPeginToFed = createLegacyP2shP2wpkhPegin(federationAddress, senderSeed);
        assertPeginWorks(legacyP2shP2wpkhPeginToFed, federationUtxosReference);
    }

    private void assertPeginV1Works(Address federationAddress, List<UTXO> federationUtxosReference, String senderSeed) throws Exception {
        var peginV1ToFed = createPeginV1(federationAddress, senderSeed);
        assertPeginWorks(peginV1ToFed, federationUtxosReference);
    }

    private void assertPeginWorks(BtcTransaction pegin, List<UTXO> federationUtxosReference) throws Exception {
        int utxosSizeBeforeRegisteringPeginV1 = federationUtxosReference.size();
        registerBtcTransaction(pegin);

        // assert pegin was processed
        assertTrue(bridgeSupport.isBtcTxHashAlreadyProcessed(pegin.getHash()));
        // assert utxo was registered
        assertEquals(utxosSizeBeforeRegisteringPeginV1 + 1, federationUtxosReference.size());
    }

    private void assertPegoutsShouldWorkToFed(Federation federation, List<UTXO> federationUtxosReference, String senderSeed) throws Exception {
        var pegout = createPegout(federation, senderSeed);
        // save pegout index
        BitcoinUtils.getSigHashForPegoutIndex(pegout)
            .ifPresent(inputSigHash -> bridgeStorageProvider.setPegoutTxSigHash(inputSigHash));

        int utxosSizeBeforeRegisteringPegout = federationUtxosReference.size();
        registerBtcTransaction(pegout);

        // assert pegout was processed
        assertTrue(bridgeSupport.isBtcTxHashAlreadyProcessed(pegout.getHash()));
        // assert utxo was registered
        assertEquals(utxosSizeBeforeRegisteringPegout + 1, federationUtxosReference.size());
    }

    private void assertPeginsShouldNotWorkToFed(Federation federation, String senderSeed) throws Exception {
        var federationAddress = federation.getAddress();

        assertLegacyP2pkhPeginDoesNotWork(federationAddress, senderSeed);
        assertLegacyP2shP2wpkhPeginDoesNotWork(federationAddress, senderSeed);
        assertPeginV1DoesNotWork(federationAddress, senderSeed);
    }

    private void assertLegacyP2pkhPeginDoesNotWork(Address federationAddress, String senderSeed) throws Exception {
        var legacyP2pkhPeginToFed = createLegacyP2pkhPegin(federationAddress, senderSeed);
        assertPeginDoesNotWork(legacyP2pkhPeginToFed);
    }

    private void assertLegacyP2shP2wpkhPeginDoesNotWork(Address federationAddress, String senderSeed) throws Exception {
        var legacyP2shP2wpkhPeginToFed = createLegacyP2shP2wpkhPegin(federationAddress, senderSeed);
        assertPeginDoesNotWork(legacyP2shP2wpkhPeginToFed);
    }

    private void assertPeginV1DoesNotWork(Address federationAddress, String senderSeed) throws Exception {
        var peginV1ToFed = createPeginV1(federationAddress, senderSeed);
        assertPeginDoesNotWork(peginV1ToFed);
    }

    private void assertPeginDoesNotWork(BtcTransaction pegin) throws Exception {
        int activeFederationUtxosSizeBeforeRegisteringPegin = federationSupport.getActiveFederationBtcUTXOs().size();
        int retiringFederationUtxosSizeBeforeRegisteringPegin = federationSupport.getRetiringFederationBtcUTXOs().size();
        registerBtcTransaction(pegin);

        // assert pegin was not processed
        assertFalse(bridgeSupport.isBtcTxHashAlreadyProcessed(pegin.getHash()));
        // assert no utxos were registered
        assertEquals(activeFederationUtxosSizeBeforeRegisteringPegin, federationSupport.getActiveFederationBtcUTXOs().size());
        assertEquals(retiringFederationUtxosSizeBeforeRegisteringPegin, federationSupport.getRetiringFederationBtcUTXOs().size());
    }

    private void assertPegoutsShouldNotWorkToFed(Federation federation, String senderSeed) throws Exception {
        var pegout = createPegout(federation, senderSeed);
        var activeFederationUtxosSizeBeforeRegisteringPegout = federationSupport.getActiveFederationBtcUTXOs().size();
        var retiringFederationUtxosSizeBeforeRegisteringPegout = federationSupport.getRetiringFederationBtcUTXOs().size();

        registerBtcTransaction(pegout);

        // assert pegout was not processed
        assertFalse(bridgeSupport.isBtcTxHashAlreadyProcessed(pegout.getHash()));
        // assert no utxos were registered
        assertEquals(activeFederationUtxosSizeBeforeRegisteringPegout, federationSupport.getActiveFederationBtcUTXOs().size());
        assertEquals(retiringFederationUtxosSizeBeforeRegisteringPegout, federationSupport.getRetiringFederationBtcUTXOs().size());
    }

    private BtcTransaction createLegacyP2pkhPegin(Address federationAddress, String senderSeed) {
        var peginBtcTx = new BtcTransaction(NETWORK_PARAMS);
        var senderPublicKey = BitcoinTestUtils.getBtcEcKeyFromSeed(senderSeed);

        peginBtcTx.addInput(BitcoinTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, senderPublicKey));
        peginBtcTx.addOutput(Coin.FIFTY_COINS.multiply(10), federationAddress);

        return peginBtcTx;
    }

    private BtcTransaction createLegacyP2shP2wpkhPegin(Address federationAddress, String senderSeed) {
        var peginBtcTx = new BtcTransaction(NETWORK_PARAMS);
        var senderPublicKey = BitcoinTestUtils.getBtcEcKeyFromSeed(senderSeed);
        var redeemScript = BitcoinTestUtils.merge(new byte[]{ 0x00, 0x14}, senderPublicKey.getPubKeyHash());
        var witnessScript = new ScriptBuilder()
            .data(redeemScript)
            .build();

        peginBtcTx.addInput(BitcoinTestUtils.createHash(1), 0, witnessScript);
        var txWit = new TransactionWitness(2);
        txWit.setPush(0, new byte[72]); // push for signatures
        txWit.setPush(1, senderPublicKey.getPubKey());
        peginBtcTx.setWitness(0, txWit);

        peginBtcTx.addOutput(Coin.FIFTY_COINS.multiply(10), federationAddress);

        return peginBtcTx;
    }

    private BtcTransaction createPeginV1(Address federationAddress, String senderSeed) {
        var peginBtcTx = new BtcTransaction(NETWORK_PARAMS);
        var senderPublicKey = BitcoinTestUtils.getBtcEcKeyFromSeed(senderSeed);

        peginBtcTx.addInput(BitcoinTestUtils.createHash(1), 0, ScriptBuilder.createInputScript(null, senderPublicKey));
        peginBtcTx.addOutput(Coin.FIFTY_COINS.multiply(10), federationAddress);
        // Adding OP_RETURN output to identify this peg-in as v1 and avoid sender identification
        var opReturnOutputScript =
            PegTestUtils.createOpReturnScriptForRsk(1, BridgeAddresses.BRIDGE, Optional.empty());
        peginBtcTx.addOutput(Coin.ZERO, opReturnOutputScript);

        return peginBtcTx;
    }

    private BtcTransaction createPegout(Federation federation, String senderSeed) {
        var prevTx = new BtcTransaction(NETWORK_PARAMS);
        prevTx.addOutput(Coin.FIFTY_COINS.multiply(10), federation.getAddress());

        TransactionOutput outpoint = prevTx.getOutput(0);
        var pegout = new BtcTransaction(NETWORK_PARAMS);
        pegout.addInput(outpoint);
        BitcoinUtils.addSpendingFederationBaseScript(pegout, 0, federation.getRedeemScript(), federation.getFormatVersion());

        var receiverPublicKey = BitcoinTestUtils.getBtcEcKeyFromSeed(senderSeed);
        pegout.addOutput(Coin.FIFTY_COINS.multiply(10), receiverPublicKey);
        var federationP2SHScript = federation.getP2SHScript();
        pegout.addOutput(Coin.FIFTY_COINS.multiply(100), federationP2SHScript);

        return pegout;
    }

    private void registerBtcTransaction(BtcTransaction btcTx) throws Exception {
        setUpForTransactionRegistration(btcTx);

        bridgeSupport.registerBtcTransaction(
            REGISTRATION_TX,
            btcTx.bitcoinSerialize(),
            btcBlockWithPmtHeight,
            pmtWithTransactions.bitcoinSerialize()
        );
        bridgeSupport.save();
    }

    private void setUpForTransactionRegistration(BtcTransaction btcTx) throws Exception {
        pmtWithTransactions = createValidPmtForTransactions(List.of(btcTx), NETWORK_PARAMS);
        btcBlockWithPmtHeight = 4_320;
        var chainHeight = btcBlockWithPmtHeight + BRIDGE_CONSTANTS.getBtc2RskMinimumAcceptableConfirmations();
        recreateChainFromPmt(btcBlockStore, chainHeight, pmtWithTransactions, btcBlockWithPmtHeight, NETWORK_PARAMS);
        bridgeStorageProvider.save();
    }

    private void advanceBlockchainTo() {
        federationSupport = FederationSupportBuilder.builder()
            .withFederationConstants(FEDERATION_CONSTANTS)
            .withFederationStorageProvider(federationStorageProvider)
            .withHost(host)
            .build();

        bridgeSupport = BridgeSupportBuilder.builder()
            .withProvider(bridgeStorageProvider)
            .withEventLogger(bridgeEventLogger)
            .withHost(host)
            .withBridgeConstants(BRIDGE_CONSTANTS)
            .withBtcBlockStoreFactory(btcBlockStoreFactory)
            .withBtcLockSenderProvider(btcLockSenderProvider)
            .withPeginInstructionsProvider(peginInstructionsProvider)
            .withFederationSupport(federationSupport)
            .withFeePerKbSupport(feePerKbSupport)
            .withLockingCapSupport(lockingCapSupport)
            .build();
    }

    private static void addNewBtcBlockOnTipOfChain(BtcBlockStore blockStore) throws Exception {
        var chainHead = blockStore.getChainHead();
        var btcBlock = new BtcBlock(
            NETWORK_PARAMS,
            1,
            chainHead.getHeader().getHash(),
            BitcoinTestUtils.createHash(chainHead.getHeight() + 1),
            0,
            0,
            0,
            List.of());
        var storedBlock = new StoredBlock(
            btcBlock,
            BigInteger.ZERO,
            chainHead.getHeight() + 1
        );

        blockStore.put(storedBlock);
        blockStore.setChainHead(storedBlock);
    }

    private List<UTXO> createUTXOs(Address owner) {
        var outputScript = ScriptBuilder.createOutputScript(owner);
        List<UTXO> utxos = new ArrayList<>();

        var howMany = 50;
        for (int i = 1; i < howMany; i++) {
            Coin value = Coin.FIFTY_COINS.multiply(10);
            Sha256Hash utxoHash = BitcoinTestUtils.createHash(i);
            utxos.add(new UTXO(utxoHash, 0, value, 0, false, outputScript));
        }

        return utxos;
    }

    private Script getFederationDefaultRedeemScript(Federation federation) {
        return federation instanceof ErpFederation ?
            ((ErpFederation) federation).getDefaultRedeemScript() :
            federation.getRedeemScript();
    }
   
    private static Script getFederationDefaultP2SHScript(Federation federation) {
        return federation instanceof ErpFederation ?
            ((ErpFederation) federation).getDefaultP2SHScript() :
            federation.getP2SHScript();
    }

    private static CallContext buildUpdateCollectionsTx() {
        var randomKey = BtcECKey.fromPrivate(Hex.decode("45c5b07fc1a6f58892615b7c31dca6c96db58c4bbc538a6b8a22999aaa860c32"));
        return PegTestUtils.callFrom(PublicKeys.addressOf(randomKey), PegTestUtils.createHash3(3));
    }

    private void signInputs(BtcTransaction transaction, List<BtcECKey> keysToSign) {
        List<TransactionInput> inputs = transaction.getInputs();
        IntStream.range(0, inputs.size()).forEach(i ->
            BitcoinTestUtils.signLegacyTransactionInputFromP2shMultiSig(transaction, i, keysToSign)
        );
    }
    
    // Assert federation change related methods
    private void assertUTXOsReferenceMovedFromNewToOldFederation(List<UTXO> utxos) {
        // Assert old federation exists in storage
        assertNotNull(
            federationStorageProvider.getOldFederation(FEDERATION_CONSTANTS));
        // Assert new federation exists in storage
        assertNotNull(
            federationStorageProvider.getNewFederation(FEDERATION_CONSTANTS));
        // Assert old federation holds the original utxos
        List<UTXO> utxosToMigrate = federationStorageProvider.getOldFederationBtcUTXOs();
        assertTrue(utxosToMigrate.containsAll(utxos));
        // Assert the new federation does not have any utxos yet
        assertTrue(federationStorageProvider
            .getNewFederationBtcUTXOs()
            .isEmpty());
    }

    private void assertNewAndOldFederationsReferences(Federation expectedNewFederation, Federation expectedOldFederation) {
        FederationConstants federationConstants = FEDERATION_CONSTANTS;
        assertEquals(expectedNewFederation, federationStorageProvider.getNewFederation(federationConstants));
        assertEquals(expectedOldFederation, federationStorageProvider.getOldFederation(federationConstants));
    }

    private void assertActiveAndRetiringFederationsHaveExpectedAddress(Address expectedNewFederationAddress, Address expectedOldFederationAddress) {
        assertEquals(expectedNewFederationAddress, bridgeSupport.getActiveFederationAddress());
        assertEquals(expectedOldFederationAddress, bridgeSupport.getRetiringFederationAddress());
    }

    private void assertNextFederationCreationBlockHeight(long newFederationCreationBlockNumber) {
        Optional<Long> nextFederationCreationBlockHeight = federationStorageProvider.getNextFederationCreationBlockHeight();
        assertTrue(nextFederationCreationBlockHeight.isPresent());
        assertEquals(newFederationCreationBlockNumber, nextFederationCreationBlockHeight.get());
    }

    private void assertMigrationHasNotStarted() throws Exception {
        // Current block is behind fedActivationAge + fundsMigrationAgeBegin
        var blockNumber = FEDERATION_CONSTANTS.getFederationActivationAge() +
            FEDERATION_CONSTANTS.getFundsMigrationAgeSinceActivationBegin();
        assertTrue(currentBlockNumber <= blockNumber);

        // Pegouts waiting for confirmations should be empty
        assertTrue(bridgeStorageProvider.getPegoutsWaitingForConfirmations().getEntries().isEmpty());
    }
     
    private void assertMigrationHasStarted() throws Exception {
        // Pegouts waiting for confirmations should not be empty
        // Expecting only one element since the retiring federation had less than 50 UTXOs
        assertEquals(1, bridgeStorageProvider.getPegoutsWaitingForConfirmations().getEntries().size());
    }

    private void assertOnlyActiveFedIsLive(Federation newFederation) {
        // New active federation still there, retiring federation no longer there
        assertEquals(newFederation, bridgeSupport.getActiveFederation());
        assertNull(bridgeSupport.getRetiringFederationAddress());
    }
    
    private void assertLastRetiredFederationP2SHScriptMatchesWithOriginalFederation(
          Federation originalFederation) {
        var lastRetiredFederationP2SHScriptOptional = 
            federationStorageProvider.getLastRetiredFederationP2SHScript();
        assertTrue(lastRetiredFederationP2SHScriptOptional.isPresent());
        Script lastRetiredFederationP2SHScript = lastRetiredFederationP2SHScriptOptional.get();

        assertNotEquals(lastRetiredFederationP2SHScript, originalFederation.getP2SHScript());
        assertEquals(lastRetiredFederationP2SHScript, getFederationDefaultP2SHScript(originalFederation));
    }

    private void assertPendingFederationIsBuiltAsExpected(PendingFederation pendingFederation) {
        assertNotNull(pendingFederation);
        assertEquals(NEW_FEDERATION_MEMBERS_SIZE, pendingFederation.getSize());
        assertTrue(pendingFederation.getMembers().containsAll(NEW_FEDERATION_MEMBERS));
    }

    private void assertPegoutTransactionCreatedEventWasEmitted() throws Exception {
        var pegoutsTxs = bridgeStorageProvider.getPegoutsWaitingForConfirmations()
            .getEntries().stream()
            .map(Entry::getBtcTransaction)
            .toList();

        assertEquals(1, pegoutsTxs.size());
        assertLogPegoutTransactionCreated(pegoutsTxs.get(0));
    }

    private void assertLogPegoutTransactionCreated(BtcTransaction pegoutTransaction) {
        AbiFunction pegoutTransactionCreatedEvent = BridgeEvents.PEGOUT_TRANSACTION_CREATED.getEvent();

        Sha256Hash pegoutTransactionHash = pegoutTransaction.getHash();
        byte[] pegoutTransactionHashSerialized = pegoutTransactionHash.getBytes();
        List<LogTopic> encodedTopics = getEncodedTopics(pegoutTransactionCreatedEvent, pegoutTransactionHashSerialized);

        List<Coin> outpointValues = extractOutpointValues(pegoutTransaction);
        byte[] serializedOutpointValues = UtxoUtils.encodeOutpointValues(outpointValues);
        Bytes encodedData = getEncodedData(pegoutTransactionCreatedEvent, serializedOutpointValues);

        assertEventWasEmittedWithExpectedTopics(encodedTopics);
        assertEventWasEmittedWithExpectedData(encodedData);
    }

    private void assertReleaseBtcRequestedEventEventWasEmitted() throws Exception {
        var pegoutsTxs = bridgeStorageProvider.getPegoutsWaitingForConfirmations()
            .getEntries().stream()
            .toList();
        
        assertEquals(1, pegoutsTxs.size());

        var releaseCreationTxHash = pegoutsTxs.get(0).getPegoutCreationRskTxHash();
        var btcTx = pegoutsTxs.get(0).getBtcTransaction();
        var amount = btcTx.getFee().add(btcTx.getOutputSum());
        assertLogReleaseRequested(releaseCreationTxHash, btcTx.getHash(), amount);
    }

    private void assertLogReleaseRequested(Hash releaseCreationTxHash, Sha256Hash pegoutTransactionHash, Coin requestedAmount) {
        AbiFunction releaseRequestedEvent = BridgeEvents.RELEASE_REQUESTED.getEvent();

        byte[] releaseCreationTxHashSerialized = releaseCreationTxHash.getBytes().toArrayUnsafe();
        byte[] pegoutTransactionHashSerialized = pegoutTransactionHash.getBytes();
        List<LogTopic> encodedTopics = getEncodedTopics(releaseRequestedEvent, releaseCreationTxHashSerialized, pegoutTransactionHashSerialized);

        Bytes encodedData = getEncodedData(releaseRequestedEvent, requestedAmount.getValue());

        assertEventWasEmittedWithExpectedTopics(encodedTopics);
        assertEventWasEmittedWithExpectedData(encodedData);
    }

    private void assertPegoutConfirmedEventEventWasEmitted(long pegoutCreationRskBlockNumber) throws Exception {
        var pegoutsTxs = bridgeStorageProvider.getPegoutsWaitingForSignatures()
            .entrySet().stream()
            .toList();
        
        assertEquals(1, pegoutsTxs.size());

        var btcTx = pegoutsTxs.get(0).getValue();
        assertLogPegoutConfirmed(btcTx.getHash(), pegoutCreationRskBlockNumber);
    }

    private void assertLogPegoutConfirmed(Sha256Hash btcTxHash, long pegoutCreationRskBlockNumber) {
        AbiFunction pegoutConfirmedEvent = BridgeEvents.PEGOUT_CONFIRMED.getEvent();

        byte[] btcTxHashSerialized = btcTxHash.getBytes();
        List<LogTopic> encodedTopics = getEncodedTopics(pegoutConfirmedEvent, btcTxHashSerialized);

        Bytes encodedData = getEncodedData(pegoutConfirmedEvent, pegoutCreationRskBlockNumber);

        assertEventWasEmittedWithExpectedTopics(encodedTopics);
        assertEventWasEmittedWithExpectedData(encodedData);
    }
    
    private void verifyPegouts() throws Exception {
        var activeFederation = federationStorageProvider.getNewFederation(FEDERATION_CONSTANTS);
        var retiringFederation = federationStorageProvider.getOldFederation(FEDERATION_CONSTANTS);

        for (PegoutsWaitingForConfirmations.Entry pegoutEntry : bridgeStorageProvider.getPegoutsWaitingForConfirmations().getEntries()) {
            var pegoutBtcTransaction = pegoutEntry.getBtcTransaction();

            List<TransactionInput> inputs = pegoutBtcTransaction.getInputs();
            for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
                TransactionInput input = inputs.get(inputIndex);

                // Each input should contain the right scriptSig
                Script inputRedeemScript = BitcoinUtils.extractRedeemScriptFromInput(pegoutBtcTransaction, inputIndex).orElseThrow();

                // Get the standard redeem script to compare against, since it could be a flyover redeem script
                var redeemScriptChunks = ScriptParser.parseScriptProgram(
                    inputRedeemScript.getProgram());

                var redeemScriptParser = RedeemScriptParserFactory.get(redeemScriptChunks);
                var inputStandardRedeemScriptChunks = redeemScriptParser.extractStandardRedeemScriptChunks();
                var inputStandardRedeemScript = new ScriptBuilder().addChunks(inputStandardRedeemScriptChunks).build();

                Optional<Federation> spendingFederationOptional = Optional.empty();
                if (inputStandardRedeemScript.equals(getFederationDefaultRedeemScript(activeFederation))) {
                    spendingFederationOptional = Optional.of(activeFederation);
                } else if (retiringFederation != null &&
                    inputStandardRedeemScript.equals(getFederationDefaultRedeemScript(retiringFederation))) {
                    spendingFederationOptional = Optional.of(retiringFederation);
                } else {
                    fail("Pegout scriptsig does not match any Federation");
                }

                // Check the script sig composition
                Federation spendingFederation = spendingFederationOptional.get();
                var inputScriptChunks = input.getScriptSig().getChunks();
                assertEquals(ScriptOpCodes.OP_0, inputScriptChunks.get(0).opcode);
                for (int i = 1; i <= spendingFederation.getNumberOfSignaturesRequired(); i++) {
                    assertEquals(ScriptOpCodes.OP_0, inputScriptChunks.get(i).opcode);
                }

                int index = spendingFederation.getNumberOfSignaturesRequired() + 1;
                if (spendingFederation instanceof ErpFederation) {
                    // Should include an additional OP_0
                    assertEquals(ScriptOpCodes.OP_0, inputScriptChunks.get(index).opcode);
                }
            }
        }
    }

    private void assertPegoutTxSigHashesAreSaved() throws IOException {
        var pegoutsTxs = bridgeStorageProvider.getPegoutsWaitingForConfirmations()
            .getEntries().stream()
            .map(Entry::getBtcTransaction)
            .toList();

        for (var pegoutTx : pegoutsTxs) {
            var lastPegoutSigHash = BitcoinUtils.getSigHashForPegoutIndex(pegoutTx);
            assertTrue(lastPegoutSigHash.isPresent());
            assertTrue(bridgeStorageProvider.hasPegoutTxSigHash(lastPegoutSigHash.get()));
        }
    }
}
