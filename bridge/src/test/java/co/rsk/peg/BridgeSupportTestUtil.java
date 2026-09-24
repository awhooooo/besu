package co.rsk.peg;

import static co.rsk.bitcoinj.script.ScriptOpCodes.OP_0;
import static co.rsk.peg.BridgeEventsTestUtils.*;
import static co.rsk.peg.BridgeEventsTestUtils.getLogsData;
import static co.rsk.peg.BridgeStorageIndexKey.RELEASES_OUTPOINTS_VALUES;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.bitcoinj.script.ScriptChunk;
import co.rsk.bitcoinj.store.BlockStoreException;
import co.rsk.peg.abi.AbiFunction;
import co.rsk.peg.bitcoin.BitcoinTestUtils;
import co.rsk.peg.bitcoin.BitcoinUtils;
import co.rsk.peg.bitcoin.UtxoUtils;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.federation.FederationMember;
import co.rsk.peg.host.BridgeHost;
import co.rsk.peg.pegin.RejectedPeginReason;
import co.rsk.peg.utils.NonRefundablePeginReason;
import co.rsk.peg.utils.PublicKeys;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import org.bouncycastle.util.encoders.Hex;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogTopic;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/** Ported from RSKj. Logs are Besu logs, and what a bridge event encodes is what AbiFunction encodes. */
public final class BridgeSupportTestUtil {

    private BridgeSupportTestUtil() {
    }

    public static PartialMerkleTree createValidPmtForTransactions(List<BtcTransaction> btcTransactions, NetworkParameters networkParameters) {
        List<Sha256Hash> hashesToAdd = new ArrayList<>();
        for (BtcTransaction transaction : btcTransactions) {
            hashesToAdd.add(getHashConsideringWitness(transaction));
        }

        return createValidPmtForTransactionsHashes(hashesToAdd, networkParameters);
    }

    private static Sha256Hash getHashConsideringWitness(BtcTransaction btcTransaction) {
        if (!btcTransaction.hasWitness()) {
            return btcTransaction.getHash();
        }

        return btcTransaction.getHash(true);
    }

    private static PartialMerkleTree createValidPmtForTransactionsHashes(List<Sha256Hash> hashesToAdd, NetworkParameters networkParameters) {
        byte[] relevantNodesBits = new byte[(int)Math.ceil(hashesToAdd.size() / 8.0)];
        for (int i = 0; i < hashesToAdd.size(); i++) {
            Utils.setBitLE(relevantNodesBits, i);
        }

        return PartialMerkleTree.buildFromLeaves(networkParameters, relevantNodesBits, hashesToAdd);
    }

    public static void recreateChainFromPmt(
        BtcBlockStoreWithCache btcBlockStoreWithCache,
        int chainHeight,
        PartialMerkleTree partialMerkleTree,
        int btcBlockWithPmtHeight,
        NetworkParameters networkParameters
    ) throws BlockStoreException {

        // first create a block that has the wanted partial merkle tree
        BtcBlock btcBlockWithPmt = createBtcBlockWithPmt(partialMerkleTree, networkParameters);
        // store it on the chain at wanted height
        StoredBlock storedBtcBlockWithPmt = new StoredBlock(btcBlockWithPmt, BigInteger.ONE, btcBlockWithPmtHeight);
        btcBlockStoreWithCache.put(storedBtcBlockWithPmt);
        btcBlockStoreWithCache.setMainChainBlock(btcBlockWithPmtHeight, btcBlockWithPmt.getHash());

        // create and store a new chainHead at wanted chain height
        Sha256Hash otherTransactionHash = Sha256Hash.of(Hex.decode("aa"));
        PartialMerkleTree pmt = createValidPmtForTransactionsHashes(List.of(otherTransactionHash), networkParameters);
        BtcBlock chainHeadBlock = createBtcBlockWithPmt(pmt, networkParameters);
        StoredBlock storedChainHeadBlock = new StoredBlock(chainHeadBlock, BigInteger.TEN, chainHeight);
        btcBlockStoreWithCache.put(storedChainHeadBlock);
        btcBlockStoreWithCache.setChainHead(storedChainHeadBlock);
    }

    private static BtcBlock createBtcBlockWithPmt(PartialMerkleTree pmt, NetworkParameters networkParameters) {
        Sha256Hash prevBlockHash = BitcoinTestUtils.createHash(1);
        Sha256Hash merkleRoot = pmt.getTxnHashAndMerkleRoot(new ArrayList<>());

        return new co.rsk.bitcoinj.core.BtcBlock(
            networkParameters,
            1,
            prevBlockHash,
            merkleRoot,
            1,
            1,
            1,
            new ArrayList<>()
        );
    }

    public static void mockChainOfStoredBlocks(BtcBlockStoreWithCache btcBlockStore, BtcBlock targetHeader, int headHeight, int targetHeight) throws BlockStoreException {
        // Simulate that the block is in there by mocking the getter by height,
        // and then simulate that the txs have enough confirmations by setting a high head.
        when(btcBlockStore.getStoredBlockAtMainChainHeight(targetHeight)).thenReturn(new StoredBlock(targetHeader, BigInteger.ONE, targetHeight));
        // Mock current pointer's header
        StoredBlock currentStored = mock(StoredBlock.class);
        BtcBlock currentBlock = mock(BtcBlock.class);
        doReturn(Sha256Hash.of(Hex.decode("aa"))).when(currentBlock).getHash();
        doReturn(currentBlock).when(currentStored).getHeader();
        when(currentStored.getHeader()).thenReturn(currentBlock);
        when(btcBlockStore.getChainHead()).thenReturn(currentStored);
        when(currentStored.getHeight()).thenReturn(headHeight);
    }

    public static PartialMerkleTree buildPMTAndRecreateChainForTransactionRegistration(
        BridgeStorageProvider bridgeStorageProvider,
        BridgeConstants bridgeConstants,
        int btcBlockToRegisterHeight,
        BtcTransaction transaction,
        BtcBlockStoreWithCache btcBlockStore
    ) throws BlockStoreException {
        NetworkParameters networkParameters = bridgeConstants.getBtcParams();

        PartialMerkleTree pmtWithTransactions = createValidPmtForTransactions(List.of(transaction), networkParameters);
        int chainHeight = btcBlockToRegisterHeight + bridgeConstants.getBtc2RskMinimumAcceptableConfirmations();
        recreateChainFromPmt(btcBlockStore, chainHeight, pmtWithTransactions, btcBlockToRegisterHeight, networkParameters);
        bridgeStorageProvider.save();

        return pmtWithTransactions;
    }

    public static Bytes32 getStorageKeyForReleaseOutpointsValues(Sha256Hash releaseTxHash) {
        return RELEASES_OUTPOINTS_VALUES.getCompoundKey("-", releaseTxHash.toString());
    }

    public static BtcTransaction getReleaseFromPegoutsWFC(BridgeStorageProvider bridgeStorageProvider) throws IOException {
        // we assume that the only present release is the expected one
        PegoutsWaitingForConfirmations pegoutsWFC = bridgeStorageProvider.getPegoutsWaitingForConfirmations();
        Set<PegoutsWaitingForConfirmations.Entry> pegoutsWFCEntries = pegoutsWFC.getEntries();
        Iterator<PegoutsWaitingForConfirmations.Entry> iterator = pegoutsWFCEntries.iterator();
        PegoutsWaitingForConfirmations.Entry pegoutEntry = iterator.next();

        return pegoutEntry.getBtcTransaction();
    }

    public static void assertWitnessAndScriptSigHaveExpectedInputRedeemData(TransactionWitness witness, TransactionInput input, Script expectedRedeemScript) {
        // assert last push has the redeem script
        int redeemScriptIndex = witness.getPushCount() - 1;
        byte[] redeemData = witness.getPush(redeemScriptIndex);
        assertArrayEquals(expectedRedeemScript.getProgram(), redeemData);

        // assert the script sig has expected fixed redeem script data
        byte[] redeemScriptHash = Sha256Hash.hash(expectedRedeemScript.getProgram());
        Script segwitScriptSig = new ScriptBuilder().number(OP_0).data(redeemScriptHash).build();
        Script oneChunkSegwitScriptSig = new ScriptBuilder().data(segwitScriptSig.getProgram()).build();
        Script actualScriptSig = input.getScriptSig();
        assertEquals(oneChunkSegwitScriptSig, actualScriptSig);
    }

    public static void assertScriptSigHasExpectedInputRedeemData(TransactionInput input, Script expectedRedeemScript) {
        List<ScriptChunk> scriptSigChunks = input.getScriptSig().getChunks();
        int redeemScriptIndex = scriptSigChunks.size() - 1;
        byte[] redeemData = scriptSigChunks.get(redeemScriptIndex).data;

        assertArrayEquals(expectedRedeemScript.getProgram(), redeemData);
    }

    public static void assertFederatorSigning(
        byte[] rskTxHashSerialized,
        BtcTransaction btcTx,
        List<Sha256Hash> sigHashes,
        Federation federation,
        BtcECKey key,
        List<Log> logs
    ) {
        Optional<FederationMember> federationMember = federation.getMemberByBtcPublicKey(key);
        assertTrue(federationMember.isPresent());
        assertLogAddSignature(logs, federationMember.get(), rskTxHashSerialized);
        assertFederatorSignedInputs(btcTx, sigHashes, key);
    }

    private static void assertFederatorSignedInputs(BtcTransaction btcTx, List<Sha256Hash> sigHashes, BtcECKey key) {
        for (int i = 0; i < btcTx.getInputs().size(); i++) {
            Sha256Hash sigHash = sigHashes.get(i);
            assertTrue(BridgeUtils.isInputSignedByThisFederator(btcTx, i, key, sigHash));
        }
    }

    public static void assertFederatorDidNotSignInputs(BtcTransaction btcTx, List<Sha256Hash> sigHashes, BtcECKey key) {
        for (int i = 0; i < btcTx.getInputs().size(); i++) {
            Sha256Hash sigHash = sigHashes.get(i);
            assertFalse(BridgeUtils.isInputSignedByThisFederator(btcTx, i, key, sigHash));
        }
    }

    public static void assertReleaseRejectionWasSettled(
        BridgeHost host,
        BridgeStorageProvider bridgeStorageProvider,
        List<Log> logs,
        long executionBlock,
        Hash releaseCreationTxHash,
        BtcTransaction releaseTransaction,
        List<Coin> expectedOutpointsValues,
        Coin totalAmountRequested
    ) throws IOException {
        Sha256Hash releaseTransactionHash = releaseTransaction.getHash();

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = bridgeStorageProvider.getPegoutsWaitingForConfirmations();
        assertPegoutWasAddedToPegoutsWaitingForConfirmations(pegoutsWaitingForConfirmations, releaseTransactionHash, releaseCreationTxHash, executionBlock);
        assertLogReleaseRequested(logs, releaseCreationTxHash, releaseTransactionHash, totalAmountRequested);
        assertReleaseTransactionInfoWasProcessed(host, bridgeStorageProvider, logs, releaseTransaction, expectedOutpointsValues);
    }

    public static void assertReleaseWasSettled(
        BridgeHost host,
        BridgeStorageProvider bridgeStorageProvider,
        List<Log> logs,
        long executionBlock,
        Hash releaseCreationTxHash,
        BtcTransaction releaseTransaction,
        List<Coin> expectedOutpointsValues,
        Coin totalAmountRequested
    ) throws IOException {
        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = bridgeStorageProvider.getPegoutsWaitingForConfirmations();
        assertPegoutWasAddedToPegoutsWaitingForConfirmations(pegoutsWaitingForConfirmations, releaseTransaction.getHash(), releaseCreationTxHash, executionBlock);
        assertPegoutTxSigHashWasSaved(bridgeStorageProvider, releaseTransaction);
        assertLogReleaseRequested(logs, releaseCreationTxHash, releaseTransaction.getHash(), totalAmountRequested);
        assertReleaseTransactionInfoWasProcessed(host, bridgeStorageProvider, logs, releaseTransaction, expectedOutpointsValues);
    }

    public static void assertPegoutWasAddedToPegoutsWaitingForConfirmations(PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations, Sha256Hash pegoutTransactionHash, Hash releaseCreationRskTxHash, long executionBlock) {
        Set<PegoutsWaitingForConfirmations.Entry> pegoutEntries = pegoutsWaitingForConfirmations.getEntries();
        Optional<PegoutsWaitingForConfirmations.Entry> pegoutEntry = pegoutEntries.stream()
            .filter(entry -> entry.getBtcTransaction().getHash().equals(pegoutTransactionHash) &&
                entry.getPegoutCreationRskBlockNumber().equals(executionBlock) &&
                entry.getPegoutCreationRskTxHash().equals(releaseCreationRskTxHash))
            .findFirst();
        assertTrue(pegoutEntry.isPresent());
    }

    public static void assertPegoutTxSigHashWasSaved(BridgeStorageProvider bridgeStorageProvider, BtcTransaction pegoutTransaction) {
        Optional<Sha256Hash> pegoutTxSigHashOpt = BitcoinUtils.getSigHashForPegoutIndex(pegoutTransaction);
        assertTrue(pegoutTxSigHashOpt.isPresent());

        Sha256Hash pegoutTxSigHash = pegoutTxSigHashOpt.get();
        assertTrue(bridgeStorageProvider.hasPegoutTxSigHash(pegoutTxSigHash));
    }

    public static void assertReleaseTransactionInfoWasProcessed(
        BridgeHost host,
        BridgeStorageProvider bridgeStorageProvider,
        List<Log> logs,
        BtcTransaction releaseTransaction,
        List<Coin> outpointsValues
    ) {
        assertLogPegoutTransactionCreated(logs, releaseTransaction, outpointsValues);
        assertReleaseOutpointsValuesWereSaved(host, bridgeStorageProvider, releaseTransaction, outpointsValues);
    }

    public static void assertLogReleaseRequested(List<Log> logs, Hash releaseCreationTxHash, Sha256Hash pegoutTransactionHash, Coin requestedAmount) {
        AbiFunction releaseRequestedEvent = BridgeEvents.RELEASE_REQUESTED.getEvent();

        byte[] releaseCreationTxHashSerialized = releaseCreationTxHash.getBytes().toArrayUnsafe();
        byte[] pegoutTransactionHashSerialized = pegoutTransactionHash.getBytes();
        List<LogTopic> encodedTopics = getEncodedTopics(releaseRequestedEvent, releaseCreationTxHashSerialized, pegoutTransactionHashSerialized);

        Bytes encodedData = getEncodedData(releaseRequestedEvent, requestedAmount.getValue());

        assertEventWasEmittedWithExpectedTopics(logs, encodedTopics);
        assertEventWasEmittedWithExpectedData(logs, encodedData);
    }

    public static void assertLogPegoutTransactionCreated(List<Log> logs, BtcTransaction releaseTransaction, List<Coin> expectedOutpointsValues) {
        AbiFunction pegoutTransactionCreatedEvent = BridgeEvents.PEGOUT_TRANSACTION_CREATED.getEvent();
        Sha256Hash pegoutTransactionHash = releaseTransaction.getHash();
        byte[] pegoutTransactionHashSerialized = pegoutTransactionHash.getBytes();
        List<LogTopic> encodedTopics = getEncodedTopics(pegoutTransactionCreatedEvent, pegoutTransactionHashSerialized);

        byte[] serializedOutpointValues = UtxoUtils.encodeOutpointValues(expectedOutpointsValues);
        Bytes encodedData = getEncodedData(pegoutTransactionCreatedEvent, serializedOutpointValues);

        assertEventWasEmittedWithExpectedTopics(logs, encodedTopics);
        assertEventWasEmittedWithExpectedData(logs, encodedData);
    }

    private static void assertReleaseOutpointsValuesWereSaved(BridgeHost host, BridgeStorageProvider bridgeStorageProvider, BtcTransaction releaseTransaction, List<Coin> expectedOutpointsValues) {
        Sha256Hash releaseTransactionHash = releaseTransaction.getHash();
        // assert entry was saved in storage
        byte[] savedReleaseOutpointsValues = host.getStorage(getStorageKeyForReleaseOutpointsValues(releaseTransactionHash));
        assertNotNull(savedReleaseOutpointsValues);

        // assert saved values are the expected ones
        Optional<List<Coin>> releaseOutpointsValues = bridgeStorageProvider.getReleaseOutpointsValues(releaseTransactionHash);
        assertTrue(releaseOutpointsValues.isPresent());
        assertEquals(expectedOutpointsValues, releaseOutpointsValues.get());
    }

    public static void assertTransactionWasProcessed(BridgeStorageProvider bridgeStorageProvider, Sha256Hash transactionHash, int executionBlockNumber) throws IOException {
        Optional<Long> rskBlockHeightAtWhichBtcTxWasProcessed = bridgeStorageProvider.getHeightIfBtcTxhashIsAlreadyProcessed(transactionHash);
        assertTrue(rskBlockHeightAtWhichBtcTxWasProcessed.isPresent());

        assertEquals(executionBlockNumber, rskBlockHeightAtWhichBtcTxWasProcessed.get());
    }

    private static void assertLogAddSignature(List<Log> logs, FederationMember federationMember, byte[] rskTxHash) {
        AbiFunction addSignatureEvent = BridgeEvents.ADD_SIGNATURE.getEvent();
        BtcECKey federatorRskPublicKey = federationMember.getRskPublicKey();
        String federatorRskAddress = PublicKeys.addressOf(federatorRskPublicKey).getBytes().toUnprefixedHexString();

        List<LogTopic> encodedTopics = getEncodedTopics(addSignatureEvent, rskTxHash, federatorRskAddress);

        BtcECKey federatorBtcPublicKey = federationMember.getBtcPublicKey();
        Bytes encodedData = getEncodedData(addSignatureEvent, federatorBtcPublicKey.getPubKey());

        assertEventWasEmittedWithExpectedTopics(logs, encodedTopics);
        assertEventWasEmittedWithExpectedData(logs, encodedData);
    }

    public static void assertLogReleaseBtc(List<Log> logs, BtcTransaction btcTx, Hash rskTxHash) {
        AbiFunction releaseBtcEvent = BridgeEvents.RELEASE_BTC.getEvent();
        byte[] rskTxHashSerialized = rskTxHash.getBytes().toArrayUnsafe();
        List<LogTopic> encodedTopics = getEncodedTopics(releaseBtcEvent, rskTxHashSerialized);

        byte[] btcTxSerialized = btcTx.bitcoinSerialize();
        Bytes encodedData = getEncodedData(releaseBtcEvent, btcTxSerialized);

        assertEventWasEmittedWithExpectedTopics(logs, encodedTopics);
        assertEventWasEmittedWithExpectedData(logs, encodedData);
    }

    public static void assertLogRejectedPegin(List<Log> logs, BtcTransaction btcTx, RejectedPeginReason reason) {
        AbiFunction rejectedPeginEvent = BridgeEvents.REJECTED_PEGIN.getEvent();

        byte[] btcTxHashSerialized = btcTx.getHash().getBytes();
        List<LogTopic> encodedTopics = getEncodedTopics(rejectedPeginEvent, btcTxHashSerialized);

        int reasonValue = reason.getValue();
        Bytes encodedData = getEncodedData(rejectedPeginEvent, reasonValue);

        assertEventWasEmittedWithExpectedTopics(logs, encodedTopics);
        assertEventWasEmittedWithExpectedData(logs, encodedData);
    }

    public static void assertLogNonRefundablePegin(List<Log> logs, BtcTransaction pegin, NonRefundablePeginReason reason) {
        AbiFunction unrefundablePeginEvent = BridgeEvents.UNREFUNDABLE_PEGIN.getEvent();
        byte[] btcTxHashSerialized = pegin.getHash().getBytes();
        List<LogTopic> encodedTopics = getEncodedTopics(unrefundablePeginEvent, btcTxHashSerialized);

        int reasonValue = reason.getValue();
        Bytes encodedData = getEncodedData(unrefundablePeginEvent, reasonValue);

        assertEventWasEmittedWithExpectedTopics(logs, encodedTopics);
        assertEventWasEmittedWithExpectedData(logs, encodedData);
    }

    public static void assertEventWasEmittedWithExpectedTopics(List<Log> logs, List<LogTopic> expectedTopics) {
        Optional<Log> topicOpt = getLogsTopics(logs, expectedTopics);
        assertTrue(topicOpt.isPresent());
    }

    public static void assertEventWasEmittedWithExpectedData(List<Log> logs, Bytes expectedData) {
        Optional<Log> data = getLogsData(logs, expectedData);
        assertTrue(data.isPresent());
    }
}
