package co.rsk.federate;

import static co.rsk.federate.testing.BitcoinFixture.REGTEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import co.rsk.federate.bitcoin.BitcoinWrapper;
import co.rsk.federate.io.BtcToRskClientFileStorage;
import co.rsk.federate.io.BtcToRskClientFileStorageImpl;
import co.rsk.federate.io.BtcToRskClientFileStorageInfo;
import co.rsk.federate.signing.ECDSASignerFromFileKey;
import co.rsk.federate.signing.KeyId;
import co.rsk.federate.testing.BitcoinFixture;
import co.rsk.federate.testing.FakeBitcoinWrapper;
import co.rsk.federate.testing.FakeNode;
import co.rsk.federate.tx.LegacyTransactionSigner;
import co.rsk.peg.BridgeMethods;
import co.rsk.peg.btcLockSender.BtcLockSenderProvider;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeRegTestConstants;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.federation.FederationArgs;
import co.rsk.peg.federation.FederationFactory;
import co.rsk.peg.federation.FederationMember;
import co.rsk.peg.pegininstructions.PeginInstructionsProvider;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.PartialMerkleTree;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The peg-in client, driven by a Bitcoin peer whose chain the test lays out and a node that decodes
 * what it is sent.
 *
 * <p>Nothing here stubs {@link FederatorSupport}: every assertion about a bridge call is made
 * against arguments decoded from a signed transaction by the bridge's own ABI definition, so an
 * encoding that would not survive the real bridge fails here too.
 */
class BtcToRskClientTest {

    private static final BigInteger CHAIN_ID = BigInteger.valueOf(33);
    private static final String RSK_KEY = "505334c7745df2fc61486dffb900784505776a898377172ffa77384892749179";
    private static final int HEADERS_PER_TURN = 25;
    private static final int CONFIRMATIONS_ON_RSK = 10;  // small, so a test can step over it

    @TempDir Path home;

    private BridgeConstants bridgeConstants;
    private Federation federation;
    private org.bitcoinj.core.Address federationAddress;
    private FakeBitcoinWrapper bitcoin;
    private FakeNode node;
    private FederatorSupport federatorSupport;
    private Block genesis;

    @BeforeEach
    void setUp() throws Exception {
        bridgeConstants = new BridgeRegTestConstants();
        federation = aFederation();
        federationAddress = org.bitcoinj.core.LegacyAddress.fromBase58(
            REGTEST, federation.getAddress().toBase58());

        bitcoin = new FakeBitcoinWrapper();
        genesis = BitcoinFixture.block(Sha256Hash.ZERO_HASH, List.of(BitcoinFixture.coinbase(0)));
        bitcoin.appendToBestChain(genesis);

        node = new FakeNode();
        federatorSupport = new FederatorSupport(bridgeClient(node), bridgeConstants.getBtcParams());
    }

    // ---------------------------------------------------------------- gathering proofs

    @Test
    void aTransactionIsRememberedBeforeAnyBlockProvesIt() throws Exception {
        BtcToRskClient client = startedClient();
        Transaction payment = BitcoinFixture.payTo(federationAddress, Coin.COIN, 1);

        client.onTransaction(payment);

        assertThat(client.getTransactionsToSendToRsk()).containsOnlyKeys(payment.getWTxId());
        assertThat(client.getTransactionsToSendToRsk().get(payment.getWTxId())).isEmpty();
    }

    @Test
    void aBlockProvesTheTransactionsBeingWaitedOn() throws Exception {
        BtcToRskClient client = startedClient();
        Transaction payment = BitcoinFixture.payTo(federationAddress, Coin.COIN, 1);
        Block block = blockWith(payment);

        client.onTransaction(payment);
        client.onBlock(block);

        List<Proof> proofs = client.getTransactionsToSendToRsk().get(payment.getWTxId());
        assertThat(proofs).hasSize(1);
        assertThat(proofs.get(0).getBlockHash()).isEqualTo(block.getHash());
    }

    @Test
    void aBlockWithNothingOfInterestIsIgnored() throws Exception {
        BtcToRskClient client = startedClient();
        Transaction other = BitcoinFixture.payTo(BitcoinFixture.someP2shAddress(9), Coin.COIN, 5);

        client.onBlock(blockWith(other));

        assertThat(client.getTransactionsToSendToRsk()).isEmpty();
    }

    @Test
    void theSameBlockTwiceDoesNotProveTheSameThingTwice() throws Exception {
        BtcToRskClient client = startedClient();
        Transaction payment = BitcoinFixture.payTo(federationAddress, Coin.COIN, 1);
        Block block = blockWith(payment);

        client.onTransaction(payment);
        client.onBlock(block);
        client.onBlock(block);

        assertThat(client.getTransactionsToSendToRsk().get(payment.getWTxId())).hasSize(1);
    }

    @Test
    void aForkLeavesTwoProofsToChooseBetween() throws Exception {
        BtcToRskClient client = startedClient();
        Transaction payment = BitcoinFixture.payTo(federationAddress, Coin.COIN, 1);
        Block onOneSide = BitcoinFixture.block(genesis.getHash(), List.of(BitcoinFixture.coinbase(1), payment));
        Block onTheOther = BitcoinFixture.block(genesis.getHash(), List.of(BitcoinFixture.coinbase(2), payment));

        client.onTransaction(payment);
        client.onBlock(onOneSide);
        client.onBlock(onTheOther);

        assertThat(client.getTransactionsToSendToRsk().get(payment.getWTxId()))
            .extracting(Proof::getBlockHash)
            .containsExactlyInAnyOrder(onOneSide.getHash(), onTheOther.getHash());
    }

    @Test
    void theProofIsOneTheBlockHeaderActuallyCommitsTo() throws Exception {
        // The proof the bridge checks is a merkle path, and the only thing that makes it valid is
        // that it recomputes the header's merkle root. Comparing bytes to a recorded fixture would
        // not notice a systematically wrong tree.
        BtcToRskClient client = startedClient();
        Transaction payment = BitcoinFixture.payTo(federationAddress, Coin.COIN, 1);
        Transaction noise = BitcoinFixture.payTo(BitcoinFixture.someP2shAddress(4), Coin.CENT, 6);
        Block block = BitcoinFixture.block(
            genesis.getHash(), List.of(BitcoinFixture.coinbase(1), noise, payment));

        client.onTransaction(payment);
        client.onBlock(block);

        PartialMerkleTree pmt = client.getTransactionsToSendToRsk().get(payment.getWTxId()).get(0).getPartialMerkleTree();
        List<Sha256Hash> matched = new ArrayList<>();
        assertThat(pmt.getTxnHashAndMerkleRoot(matched)).isEqualTo(block.getMerkleRoot());
        assertThat(matched).containsExactly(payment.getTxId());
    }

    @Test
    void aSegwitProofCommitsToTheWitnessRootTheCoinbaseNames() throws Exception {
        BtcToRskClient client = startedClient();
        Transaction payment = BitcoinFixture.segwitPayTo(federationAddress, Coin.COIN, 1);
        Block block = segwitBlockWith(payment);

        client.onTransaction(payment);
        client.onBlock(block);

        Proof proof = client.getTransactionsToSendToRsk().get(payment.getWTxId()).get(0);
        List<Sha256Hash> matched = new ArrayList<>();
        Sha256Hash root = proof.getPartialMerkleTree().getTxnHashAndMerkleRoot(matched);

        CoinbaseInformation coinbase = storedCoinbaseOf(client, block);
        assertThat(root).isEqualTo(coinbase.getWitnessRoot());
        // A segwit transaction is proved by wtxid, which is not its txid.
        assertThat(matched).containsExactly(payment.getWTxId());
        assertThat(payment.getWTxId()).isNotEqualTo(payment.getTxId());
    }

    @Test
    void aSegwitPeginStoresTheCoinbaseNotYetReadyToInform() throws Exception {
        BtcToRskClient client = startedClient();
        Transaction payment = BitcoinFixture.segwitPayTo(federationAddress, Coin.COIN, 1);
        Block block = segwitBlockWith(payment);

        client.onTransaction(payment);
        client.onBlock(block);

        // The bridge cannot check a coinbase against a header it has not been given yet.
        assertThat(storedCoinbaseOf(client, block).isReadyToInform()).isFalse();
    }

    @Test
    void aNonSegwitPeginNeedsNoCoinbase() throws Exception {
        BtcToRskClient client = startedClient();
        Transaction payment = BitcoinFixture.payTo(federationAddress, Coin.COIN, 1);

        client.onTransaction(payment);
        client.onBlock(blockWith(payment));

        assertThat(coinbasesOf(client)).isEmpty();
    }

    @Test
    void aCoinbaseCommittingToTheWrongRootIsRefusedAndNoProofIsKept() throws Exception {
        BtcToRskClient client = startedClient();
        Transaction payment = BitcoinFixture.segwitPayTo(federationAddress, Coin.COIN, 1);
        // A commitment over somebody else's witness root: what a malicious or broken peer sends.
        Transaction coinbase = BitcoinFixture.segwitCoinbase(1, Sha256Hash.of(new byte[] {9}), new byte[32]);
        Block block = BitcoinFixture.block(genesis.getHash(), List.of(coinbase, payment));

        client.onTransaction(payment);
        client.onBlock(block);

        assertThat(coinbasesOf(client)).isEmpty();
        assertThat(client.getTransactionsToSendToRsk().get(payment.getWTxId())).isEmpty();
    }

    // ---------------------------------------------------------------- informing headers

    @Test
    void theBridgeIsGivenOnlyTheHeadersItLacks() throws Exception {
        buildChain(5);
        BtcToRskClient client = startedClient();
        bridgeSeesBitcoinChain(2);

        client.updateBridge();

        FakeNode.Sent sent = node.firstOf(BridgeMethods.RECEIVE_HEADERS).orElseThrow();
        Object[] headers = (Object[]) sent.arguments()[0];
        assertThat(headers).hasSize(3);
        assertThat(headerHashesOf(headers))
            .containsExactly(hashAt(3), hashAt(4), hashAt(5));
    }

    @Test
    void headersGoOldestFirst() throws Exception {
        buildChain(4);
        BtcToRskClient client = startedClient();
        bridgeSeesBitcoinChain(1);

        client.updateBridge();

        Object[] headers = (Object[]) node.firstOf(BridgeMethods.RECEIVE_HEADERS).orElseThrow().arguments()[0];
        // The bridge extends its chain one block at a time, so a batch out of order is rejected.
        assertThat(headerHashesOf(headers)).containsExactly(hashAt(2), hashAt(3), hashAt(4));
    }

    @Test
    void aTurnSendsAtMostItsShareOfHeaders() throws Exception {
        buildChain(HEADERS_PER_TURN + 10);
        BtcToRskClient client = startedClient();
        bridgeSeesBitcoinChain(0);

        client.updateBridge();

        Object[] headers = (Object[]) node.firstOf(BridgeMethods.RECEIVE_HEADERS).orElseThrow().arguments()[0];
        assertThat(headers).hasSize(HEADERS_PER_TURN);
    }

    @Test
    void aBridgeThatIsUpToDateIsToldNothing() throws Exception {
        buildChain(3);
        BtcToRskClient client = startedClient();
        bridgeSeesBitcoinChain(3);

        client.updateBridge();

        assertThat(node.sentOf(BridgeMethods.RECEIVE_HEADERS)).isEmpty();
    }

    @Test
    void afterAForkTheHeadersStartFromTheCommonAncestor() throws Exception {
        buildChain(4);
        BtcToRskClient client = startedClient();
        // The bridge is on a fork: its head is a block this peer stored but did not keep.
        Block abandoned = BitcoinFixture.block(hashAt(2), List.of(BitcoinFixture.coinbase(99)));
        bitcoin.storeOffChain(abandoned, 3);
        node.answering(BridgeMethods.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT, BigInteger.valueOf(3))
            .answering(BridgeMethods.GET_BTC_BLOCKCHAIN_INITIAL_BLOCK_HEIGHT, BigInteger.ZERO)
            .answeringWith(BridgeMethods.GET_BTC_BLOCKCHAIN_BLOCK_HASH_AT_DEPTH, arguments -> {
                int depth = ((BigInteger) arguments[0]).intValue();
                return new Object[] {depth == 0 ? abandoned.getHash().getBytes() : hashAt(3 - depth).getBytes()};
            });

        client.updateBridge();

        Object[] headers = (Object[]) node.firstOf(BridgeMethods.RECEIVE_HEADERS).orElseThrow().arguments()[0];
        // Back to height 2, which both agree on, then forward along this peer's chain.
        assertThat(headerHashesOf(headers)).containsExactly(hashAt(3), hashAt(4));
    }

    @Test
    void informingHeadersMarksTheirCoinbasesInformable() throws Exception {
        Transaction payment = BitcoinFixture.segwitPayTo(federationAddress, Coin.COIN, 1);
        Block withPegin = segwitBlockWith(payment);
        bitcoin.appendToBestChain(withPegin);

        BtcToRskClient client = startedClient();
        client.onTransaction(payment);
        client.onBlock(withPegin);
        assertThat(storedCoinbaseOf(client, withPegin).isReadyToInform()).isFalse();

        bridgeSeesBitcoinChain(0);
        client.updateBridge();

        assertThat(storedCoinbaseOf(client, withPegin).isReadyToInform()).isTrue();
    }

    // ---------------------------------------------------------------- informing coinbases

    @Test
    void aReadyCoinbaseIsSentBeforeThePeginThatNeedsIt() throws Exception {
        Transaction payment = BitcoinFixture.segwitPayTo(federationAddress, Coin.COIN, 1);
        Block withPegin = segwitBlockWith(payment);
        bitcoin.appendToBestChain(withPegin);
        bitcoin.confirm(payment);

        BtcToRskClient client = startedClient();
        client.onTransaction(payment);
        client.onBlock(withPegin);
        bridgeSeesBitcoinChain(0);
        peginNotYetProcessed();
        node.answering(BridgeMethods.HAS_BTC_BLOCK_COINBASE_TRANSACTION_INFORMATION, false);

        client.updateBridge();

        assertThat(node.methodsSent())
            .containsSubsequence(
                BridgeMethods.REGISTER_BTC_COINBASE_TRANSACTION, BridgeMethods.REGISTER_BTC_TRANSACTION);
    }

    @Test
    void theCoinbaseIsSentWithTheCommitmentTheBridgeWillCheck() throws Exception {
        Transaction payment = BitcoinFixture.segwitPayTo(federationAddress, Coin.COIN, 1);
        Block withPegin = segwitBlockWith(payment);
        bitcoin.appendToBestChain(withPegin);

        BtcToRskClient client = startedClient();
        client.onTransaction(payment);
        client.onBlock(withPegin);
        bridgeSeesBitcoinChain(0);
        node.answering(BridgeMethods.HAS_BTC_BLOCK_COINBASE_TRANSACTION_INFORMATION, false);

        client.updateBridge();

        FakeNode.Sent sent = node.firstOf(BridgeMethods.REGISTER_BTC_COINBASE_TRANSACTION).orElseThrow();
        CoinbaseInformation stored = storedCoinbaseOf(client, withPegin);
        assertThat((byte[]) sent.arguments()[1]).isEqualTo(withPegin.getHash().getBytes());
        assertThat((byte[]) sent.arguments()[3]).isEqualTo(stored.getWitnessRoot().getBytes());
        assertThat((byte[]) sent.arguments()[4]).isEqualTo(new byte[32]);
        // The serialized coinbase must carry no witness: the bridge rebuilds it from the other two.
        Transaction roundTripped = new Transaction(REGTEST, (byte[]) sent.arguments()[0]);
        assertThat(roundTripped.hasWitnesses()).isFalse();
        assertThat(roundTripped.getTxId()).isEqualTo(withPegin.getTransactions().get(0).getTxId());
    }

    @Test
    void aCoinbaseTheBridgeAlreadyHasIsDroppedRatherThanResent() throws Exception {
        Transaction payment = BitcoinFixture.segwitPayTo(federationAddress, Coin.COIN, 1);
        Block withPegin = segwitBlockWith(payment);
        bitcoin.appendToBestChain(withPegin);

        BtcToRskClient client = startedClient();
        client.onTransaction(payment);
        client.onBlock(withPegin);
        bridgeSeesBitcoinChain(0);
        node.answering(BridgeMethods.HAS_BTC_BLOCK_COINBASE_TRANSACTION_INFORMATION, true);

        client.updateBridge();

        assertThat(node.sentOf(BridgeMethods.REGISTER_BTC_COINBASE_TRANSACTION)).isEmpty();
        assertThat(coinbasesOf(client)).isEmpty();
    }

    // ---------------------------------------------------------------- registering peg-ins

    @Test
    void aProvedConfirmedPeginIsRegisteredWithItsHeightAndProof() throws Exception {
        Transaction payment = BitcoinFixture.payTo(federationAddress, Coin.COIN, 1);
        Block withPegin = blockWith(payment);
        bitcoin.appendToBestChain(withPegin);
        bitcoin.confirm(payment);

        BtcToRskClient client = startedClient();
        client.onTransaction(payment);
        client.onBlock(withPegin);
        bridgeSeesBitcoinChain(1);
        peginNotYetProcessed();

        client.updateBridge();

        FakeNode.Sent sent = node.firstOf(BridgeMethods.REGISTER_BTC_TRANSACTION).orElseThrow();
        assertThat((byte[]) sent.arguments()[0]).isEqualTo(payment.bitcoinSerialize());
        assertThat((BigInteger) sent.arguments()[1]).isEqualTo(BigInteger.ONE);
        List<Sha256Hash> matched = new ArrayList<>();
        PartialMerkleTree pmt = new PartialMerkleTree(REGTEST, (byte[]) sent.arguments()[2], 0);
        assertThat(pmt.getTxnHashAndMerkleRoot(matched)).isEqualTo(withPegin.getMerkleRoot());
    }

    @Test
    void anUnconfirmedPeginIsKeptRatherThanSent() throws Exception {
        Transaction payment = BitcoinFixture.payTo(federationAddress, Coin.COIN, 1);
        Block withPegin = blockWith(payment);
        bitcoin.appendToBestChain(withPegin);
        // Deliberately not confirmed: not yet buried deeply enough for the bridge.

        BtcToRskClient client = startedClient();
        client.onTransaction(payment);
        client.onBlock(withPegin);
        bridgeSeesBitcoinChain(1);

        client.updateBridge();

        assertThat(node.sentOf(BridgeMethods.REGISTER_BTC_TRANSACTION)).isEmpty();
        assertThat(client.getTransactionsToSendToRsk()).containsKey(payment.getWTxId());
    }

    @Test
    void aTransactionPayingTheFederationNothingIsDropped() throws Exception {
        Transaction elsewhere = BitcoinFixture.payTo(BitcoinFixture.someP2shAddress(3), Coin.COIN, 8);
        Block block = blockWith(elsewhere);
        bitcoin.appendToBestChain(block);
        bitcoin.confirm(elsewhere);

        BtcToRskClient client = startedClient();
        client.onTransaction(elsewhere);
        client.onBlock(block);
        bridgeSeesBitcoinChain(1);

        client.updateBridge();

        assertThat(node.sentOf(BridgeMethods.REGISTER_BTC_TRANSACTION)).isEmpty();
        assertThat(client.getTransactionsToSendToRsk()).isEmpty();
    }

    @Test
    void aTransactionWithAnUnparseableScriptIsDroppedRatherThanRetriedForever() throws Exception {
        // The bridge runs the same parser, so sending this would buy a reverted transaction and the
        // gas for it, once per turn, for as long as the client kept trying.
        Transaction malformed = BitcoinFixture.payToWithUnparseableInput(federationAddress, Coin.COIN, 1);
        Block block = blockWith(malformed);
        bitcoin.appendToBestChain(block);
        bitcoin.confirm(malformed);

        BtcToRskClient client = startedClient();
        client.onTransaction(malformed);
        client.onBlock(block);
        bridgeSeesBitcoinChain(1);
        peginNotYetProcessed();

        client.updateBridge();

        assertThat(node.sentOf(BridgeMethods.REGISTER_BTC_TRANSACTION)).isEmpty();
        assertThat(client.getTransactionsToSendToRsk()).isEmpty();
    }

    @Test
    void aTurnRegistersAtMostFortyPegins() throws Exception {
        List<Transaction> payments = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            payments.add(BitcoinFixture.payTo(federationAddress, Coin.COIN, i + 20));
        }
        List<Transaction> contents = new ArrayList<>();
        contents.add(BitcoinFixture.coinbase(1));
        contents.addAll(payments);
        Block block = BitcoinFixture.block(genesis.getHash(), contents);
        bitcoin.appendToBestChain(block);
        payments.forEach(bitcoin::confirm);

        BtcToRskClient client = startedClient();
        payments.forEach(client::onTransaction);
        client.onBlock(block);
        bridgeSeesBitcoinChain(1);
        peginNotYetProcessed();

        client.updateBridge();

        assertThat(node.sentOf(BridgeMethods.REGISTER_BTC_TRANSACTION)).hasSize(40);
        // The rest are still waiting, not lost.
        assertThat(client.getTransactionsToSendToRsk()).hasSize(45);
    }

    @Test
    void theProofUsedComesFromTheBlockOnTheBestChain() throws Exception {
        // Two blocks prove the same transaction; only one survived the fork. The bridge will have
        // the header of that one, and rejects a proof against the other.
        Transaction payment = BitcoinFixture.payTo(federationAddress, Coin.COIN, 1);
        Block abandoned = BitcoinFixture.block(
            genesis.getHash(), List.of(BitcoinFixture.coinbase(50), payment));
        Block survived = BitcoinFixture.block(
            genesis.getHash(), List.of(BitcoinFixture.coinbase(51), payment));
        bitcoin.storeOffChain(abandoned, 1);
        bitcoin.appendToBestChain(survived);
        bitcoin.confirm(payment);

        BtcToRskClient client = startedClient();
        client.onTransaction(payment);
        client.onBlock(abandoned);
        client.onBlock(survived);
        assertThat(client.getTransactionsToSendToRsk().get(payment.getWTxId())).hasSize(2);
        bridgeSeesBitcoinChain(1);
        peginNotYetProcessed();

        client.updateBridge();

        FakeNode.Sent sent = node.firstOf(BridgeMethods.REGISTER_BTC_TRANSACTION).orElseThrow();
        PartialMerkleTree pmt = new PartialMerkleTree(REGTEST, (byte[]) sent.arguments()[2], 0);
        assertThat(pmt.getTxnHashAndMerkleRoot(new ArrayList<>())).isEqualTo(survived.getMerkleRoot());
        assertThat(pmt.getTxnHashAndMerkleRoot(new ArrayList<>())).isNotEqualTo(abandoned.getMerkleRoot());
    }

    @Test
    void aPeginProvedOnlyByAnAbandonedBlockIsNotSentYet() throws Exception {
        Transaction payment = BitcoinFixture.payTo(federationAddress, Coin.COIN, 1);
        Block abandoned = BitcoinFixture.block(
            genesis.getHash(), List.of(BitcoinFixture.coinbase(50), payment));
        Block survived = BitcoinFixture.block(genesis.getHash(), List.of(BitcoinFixture.coinbase(51)));
        bitcoin.storeOffChain(abandoned, 1);
        bitcoin.appendToBestChain(survived);
        bitcoin.confirm(payment);

        BtcToRskClient client = startedClient();
        client.onTransaction(payment);
        client.onBlock(abandoned);
        bridgeSeesBitcoinChain(1);
        peginNotYetProcessed();

        client.updateBridge();

        // Nothing to send, and nothing forgotten: it may yet be mined into the surviving chain.
        assertThat(node.sentOf(BridgeMethods.REGISTER_BTC_TRANSACTION)).isEmpty();
        assertThat(client.getTransactionsToSendToRsk()).containsKey(payment.getWTxId());
    }

    @Test
    void aPeginTheBridgeHasJustProcessedIsKeptUntilItIsBuried() throws Exception {
        Transaction payment = BitcoinFixture.payTo(federationAddress, Coin.COIN, 1);
        Block withPegin = blockWith(payment);
        bitcoin.appendToBestChain(withPegin);
        bitcoin.confirm(payment);

        BtcToRskClient client = startedClient();
        client.onTransaction(payment);
        client.onBlock(withPegin);
        bridgeSeesBitcoinChain(1);
        node.answering(BridgeMethods.IS_BTC_TX_HASH_ALREADY_PROCESSED, true)
            .answering(BridgeMethods.GET_BTC_TX_HASH_PROCESSED_HEIGHT, BigInteger.valueOf(995))
            .atHeight(1_000);

        client.updateBridge();

        // Five blocks deep, and ten are needed: a reorg could still undo it.
        assertThat(client.getTransactionsToSendToRsk()).containsKey(payment.getWTxId());
        assertThat(node.sentOf(BridgeMethods.REGISTER_BTC_TRANSACTION)).isEmpty();
    }

    @Test
    void aPeginBuriedDeepEnoughIsForgotten() throws Exception {
        Transaction payment = BitcoinFixture.payTo(federationAddress, Coin.COIN, 1);
        Block withPegin = blockWith(payment);
        bitcoin.appendToBestChain(withPegin);
        bitcoin.confirm(payment);

        BtcToRskClient client = startedClient();
        client.onTransaction(payment);
        client.onBlock(withPegin);
        bridgeSeesBitcoinChain(1);
        node.answering(BridgeMethods.IS_BTC_TX_HASH_ALREADY_PROCESSED, true)
            .answering(BridgeMethods.GET_BTC_TX_HASH_PROCESSED_HEIGHT, BigInteger.valueOf(900))
            .atHeight(1_000);

        client.updateBridge();

        assertThat(client.getTransactionsToSendToRsk()).isEmpty();
    }

    @Test
    void whetherAPeginWasProcessedIsAskedByTxidNotWtxid() throws Exception {
        // The two differ for a segwit transaction, and the bridge keys its record on the txid.
        Transaction payment = BitcoinFixture.segwitPayTo(federationAddress, Coin.COIN, 1);
        Block withPegin = segwitBlockWith(payment);
        bitcoin.appendToBestChain(withPegin);
        bitcoin.confirm(payment);

        List<String> asked = new ArrayList<>();
        BtcToRskClient client = startedClient();
        client.onTransaction(payment);
        client.onBlock(withPegin);
        bridgeSeesBitcoinChain(1);
        node.answeringWith(BridgeMethods.IS_BTC_TX_HASH_ALREADY_PROCESSED, arguments -> {
            asked.add((String) arguments[0]);
            return new Object[] {false};
        }).answering(BridgeMethods.HAS_BTC_BLOCK_COINBASE_TRANSACTION_INFORMATION, false);

        client.updateBridge();

        assertThat(asked).containsExactly(payment.getTxId().toString());
        assertThat(payment.getTxId()).isNotEqualTo(payment.getWTxId());
    }

    // ---------------------------------------------------------------- the turn as a whole

    @Test
    void aTurnEndsByNudgingTheBridge() throws Exception {
        buildChain(1);
        BtcToRskClient client = startedClient();
        bridgeSeesBitcoinChain(1);

        client.updateBridge();

        assertThat(node.methodsSent()).containsExactly(BridgeMethods.UPDATE_COLLECTIONS);
    }

    @Test
    void oneFailedStepDoesNotStopTheRest() throws Exception {
        buildChain(2);
        BtcToRskClient client = startedClient();
        // No answer registered for the bitcoin chain height, so informing headers throws.
        node.answering(BridgeMethods.GET_BTC_BLOCKCHAIN_INITIAL_BLOCK_HEIGHT, BigInteger.ZERO);

        client.updateBridge();

        assertThat(node.methodsSent()).containsExactly(BridgeMethods.UPDATE_COLLECTIONS);
    }

    @Test
    void aSyncingNodeIsLeftAlone() throws Exception {
        buildChain(3);
        BtcToRskClient client = startedClient();
        bridgeSeesBitcoinChain(0);
        node.syncing(true);

        client.updateBridge();

        // Its answers would be from a stale state, and acting on them wastes gas at best.
        assertThat(node.sent()).isEmpty();
    }

    @Test
    void aClientWithNoFederationDoesNothing() throws Exception {
        BtcToRskClient client = client();

        client.updateBridge();

        assertThat(node.sent()).isEmpty();
    }

    @Test
    void stoppingUnwatchesTheFederation() throws Exception {
        BtcToRskClient client = startedClient();
        assertThat(bitcoin.isWatching(federation)).isTrue();

        client.stop();

        assertThat(bitcoin.isWatching(federation)).isFalse();
        assertThat(bitcoin.hasBlockListeners()).isFalse();
    }

    @Test
    void aSequencerThatIsNotAMemberStillRelaysPegins() throws Exception {
        // registerBtcTransaction is open to anyone: a peg-in is somebody's coins, and the bridge
        // checks the proof rather than who carried it. So a sequencer holding keys that are in no
        // live federation still does the work that matters.
        Federation strangers =
            co.rsk.federate.testing.PegoutFixture.federationOfStrangers(bridgeConstants.getBtcParams());
        org.bitcoinj.core.Address strangersAddress = org.bitcoinj.core.LegacyAddress.fromBase58(
            REGTEST, strangers.getAddress().toBase58());
        Transaction payment = BitcoinFixture.payTo(strangersAddress, Coin.COIN, 1);
        Block withPegin = BitcoinFixture.block(
            genesis.getHash(), List.of(BitcoinFixture.coinbase(1), payment));
        bitcoin.appendToBestChain(withPegin);
        bitcoin.confirm(payment);

        BtcToRskClient client = client();
        client.start(strangers);
        client.onTransaction(payment);
        client.onBlock(withPegin);
        bridgeSeesBitcoinChain(1);
        peginNotYetProcessed();

        client.updateBridge();

        assertThat(node.sentOf(BridgeMethods.REGISTER_BTC_TRANSACTION)).hasSize(1);
    }

    @Test
    void aSequencerThatIsNotAMemberLeavesTheReservedCallsAlone() throws Exception {
        // receiveHeaders and updateCollections are the federation's, decided by the sending
        // address. Sending them anyway would revert both, once a turn, indefinitely.
        BtcToRskClient client = client();
        client.start(co.rsk.federate.testing.PegoutFixture.federationOfStrangers(bridgeConstants.getBtcParams()));
        buildChain(3);
        bridgeSeesBitcoinChain(0);
        peginNotYetProcessed();

        client.updateBridge();

        assertThat(node.sentOf(BridgeMethods.RECEIVE_HEADERS)).isEmpty();
        assertThat(node.sentOf(BridgeMethods.UPDATE_COLLECTIONS)).isEmpty();
    }

    @Test
    void aMemberDoesTheReservedCallsToo() throws Exception {
        buildChain(3);
        BtcToRskClient client = startedClient();
        bridgeSeesBitcoinChain(0);
        peginNotYetProcessed();

        client.updateBridge();

        assertThat(node.sentOf(BridgeMethods.RECEIVE_HEADERS)).hasSize(1);
        assertThat(node.sentOf(BridgeMethods.UPDATE_COLLECTIONS)).hasSize(1);
    }

    @Test
    void aSequencerThatIsNotAMemberStillGathersProofs() throws Exception {
        // Watching bitcoin is not privileged, and an incoming federation's member has to be
        // running before their change completes in order to be useful when it does.
        Transaction payment = BitcoinFixture.payTo(federationAddress, Coin.COIN, 1);
        Block withPegin = blockWith(payment);

        BtcToRskClient client = client();
        client.start(co.rsk.federate.testing.PegoutFixture.federationOfStrangers(bridgeConstants.getBtcParams()));
        client.onTransaction(payment);
        client.onBlock(withPegin);

        assertThat(client.getTransactionsToSendToRsk().get(payment.getWTxId())).hasSize(1);
    }

    @Test
    void aTransactionPayingOnlyAnotherRecognisedFederationIsStillRelayed() throws Exception {
        // The transaction that funds a federation change pays the proposed federation and its
        // flyover address and nothing else. Asking only whether it pays the federation this client
        // acts as would drop it, and dropping it stalls the change: the bridge learns the funding
        // confirmed only when somebody registers it, which anybody may do.
        Federation proposed = co.rsk.federate.testing.PegoutFixture.segwitFederation(
            co.rsk.federate.testing.PegoutFixture.strangersKeys(3),
            bridgeConstants.getBtcParams(), bridgeConstants.getFederationConstants());
        org.bitcoinj.core.Address proposedAddress = org.bitcoinj.core.LegacyAddress.fromBase58(
            REGTEST, proposed.getAddress().toBase58());
        Transaction fundTx = BitcoinFixture.payTo(proposedAddress, Coin.COIN.multiply(2), 1);
        Block withFundTx = BitcoinFixture.block(
            genesis.getHash(), List.of(BitcoinFixture.coinbase(1), fundTx));
        bitcoin.appendToBestChain(withFundTx);
        bitcoin.confirm(fundTx);

        BtcToRskClient client = startedClient();
        client.alsoRelayFor(proposed);
        client.onTransaction(fundTx);
        client.onBlock(withFundTx);
        bridgeSeesBitcoinChain(1);
        peginNotYetProcessed();

        client.updateBridge();

        assertThat(node.sentOf(BridgeMethods.REGISTER_BTC_TRANSACTION)).hasSize(1);
    }

    @Test
    void aTransactionPayingNoRecognisedFederationIsStillDropped() throws Exception {
        Transaction elsewhere = BitcoinFixture.payTo(BitcoinFixture.someP2shAddress(13), Coin.COIN, 9);
        Block block = blockWith(elsewhere);
        bitcoin.appendToBestChain(block);
        bitcoin.confirm(elsewhere);

        BtcToRskClient client = startedClient();
        client.onTransaction(elsewhere);
        client.onBlock(block);
        bridgeSeesBitcoinChain(1);
        peginNotYetProcessed();

        client.updateBridge();

        assertThat(node.sentOf(BridgeMethods.REGISTER_BTC_TRANSACTION)).isEmpty();
        assertThat(client.getTransactionsToSendToRsk()).isEmpty();
    }

    // ---------------------------------------------------------------- restarting

    @Test
    void proofsGatheredBeforeARestartAreStillThereAfterIt() throws Exception {
        Transaction payment = BitcoinFixture.payTo(federationAddress, Coin.COIN, 1);
        Block withPegin = blockWith(payment);

        BtcToRskClient first = startedClient();
        first.onTransaction(payment);
        first.onBlock(withPegin);

        BtcToRskClient second = client();

        assertThat(second.getTransactionsToSendToRsk()).containsKey(payment.getWTxId());
        assertThat(second.getTransactionsToSendToRsk().get(payment.getWTxId()))
            .extracting(Proof::getBlockHash).containsExactly(withPegin.getHash());
    }

    @Test
    void anUnreadableFileStopsTheClientStartingRatherThanLosingProofsQuietly() throws Exception {
        BtcToRskClientFileStorage storage = storage();
        Files.createDirectories(storage.getInfo().getPegDirectory());
        Files.write(storage.getInfo().getFilePath(), new byte[] {(byte) 0xc0, 0x01, 0x02});

        assertThatThrownBy(this::client)
            .isInstanceOf(IOException.class)
            .hasMessageContaining("unreadable");
    }

    // ---------------------------------------------------------------- helpers

    private BtcToRskClient client() throws IOException {
        return new BtcToRskClient(
            bitcoin,
            federatorSupport,
            bridgeConstants,
            storage(),
            new BtcLockSenderProvider(),
            new PeginInstructionsProvider(),
            HEADERS_PER_TURN,
            CONFIRMATIONS_ON_RSK);
    }

    private BtcToRskClient startedClient() throws IOException {
        BtcToRskClient client = client();
        client.start(federation);
        return client;
    }

    private BtcToRskClientFileStorage storage() {
        return new BtcToRskClientFileStorageImpl(new BtcToRskClientFileStorageInfo(home));
    }

    private BridgeClient bridgeClient(FakeNode node) throws Exception {
        Path keyFile = home.resolve("rsk.key");
        Files.writeString(keyFile, RSK_KEY);
        Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ));
        LegacyTransactionSigner signer = new LegacyTransactionSigner(
            new ECDSASignerFromFileKey(new KeyId("RSK"), keyFile.toString()), new KeyId("RSK"), CHAIN_ID);
        return new BridgeClient(node, signer, GasPolicy.alwaysPaid(BigInteger.ONE), 4_000_000);
    }

    /** Extends the peer's best chain to {@code height}, with empty blocks. */
    private void buildChain(int height) {
        Sha256Hash previous = genesis.getHash();
        for (int i = 1; i <= height; i++) {
            Block block = BitcoinFixture.block(previous, List.of(BitcoinFixture.coinbase(i)));
            bitcoin.appendToBestChain(block);
            previous = block.getHash();
        }
    }

    /** Makes the bridge claim a bitcoin chain that agrees with this peer's up to {@code height}. */
    private void bridgeSeesBitcoinChain(int height) {
        node.answering(BridgeMethods.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT, BigInteger.valueOf(height))
            .answering(BridgeMethods.GET_BTC_BLOCKCHAIN_INITIAL_BLOCK_HEIGHT, BigInteger.ZERO)
            .answeringWith(BridgeMethods.GET_BTC_BLOCKCHAIN_BLOCK_HASH_AT_DEPTH, arguments -> {
                int depth = ((BigInteger) arguments[0]).intValue();
                return new Object[] {hashAt(height - depth).getBytes()};
            });
    }

    private void peginNotYetProcessed() {
        node.answering(BridgeMethods.IS_BTC_TX_HASH_ALREADY_PROCESSED, false)
            .answering(BridgeMethods.HAS_BTC_BLOCK_COINBASE_TRANSACTION_INFORMATION, false);
    }

    private Sha256Hash hashAt(int height) {
        return bitcoin.getBlockAtHeight(height).getHeader().getHash();
    }

    private Block blockWith(Transaction tx) {
        return BitcoinFixture.block(genesis.getHash(), List.of(BitcoinFixture.coinbase(1), tx));
    }

    private Block segwitBlockWith(Transaction tx) {
        Transaction placeholderCoinbase = BitcoinFixture.coinbase(1);
        Sha256Hash witnessRoot = BitcoinFixture.witnessMerkleRoot(List.of(placeholderCoinbase, tx));
        Transaction coinbase = BitcoinFixture.segwitCoinbase(1, witnessRoot, new byte[32]);
        return BitcoinFixture.block(genesis.getHash(), List.of(coinbase, tx));
    }

    private static List<Sha256Hash> headerHashesOf(Object[] serializedHeaders) {
        List<Sha256Hash> hashes = new ArrayList<>(serializedHeaders.length);
        for (Object header : serializedHeaders) {
            hashes.add(new Block(REGTEST, (byte[]) header).getHash());
        }
        return hashes;
    }

    private static CoinbaseInformation storedCoinbaseOf(BtcToRskClient client, Block block) throws Exception {
        return coinbasesOf(client).get(block.getHash());
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<Sha256Hash, CoinbaseInformation> coinbasesOf(BtcToRskClient client)
        throws Exception {
        java.lang.reflect.Field field = BtcToRskClient.class.getDeclaredField("fileData");
        field.setAccessible(true);
        return ((co.rsk.federate.io.BtcToRskClientFileData) field.get(client)).getCoinbaseInformationMap();
    }

    /**
     * A federation this sequencer belongs to.
     *
     * <p>One member's RSK key is the one the sequencer signs transactions with, because that is
     * how the bridge decides whether to accept them at all: receiveHeaders,
     * registerBtcTransaction and updateCollections compare the sending address against each
     * member's. A federation of unrelated keys would let these tests pass while the real bridge
     * rejected every call.
     */
    private Federation aFederation() {
        co.rsk.bitcoinj.core.BtcECKey ourRskKey =
            co.rsk.bitcoinj.core.BtcECKey.fromPrivate(org.apache.tuweni.bytes.Bytes.fromHexString(RSK_KEY).toArray());
        List<FederationMember> members = List.of(
            new FederationMember(
                co.rsk.bitcoinj.core.BtcECKey.fromPrivate(BigInteger.valueOf(101)), ourRskKey, ourRskKey),
            FederationMember.getFederationMemberFromKey(
                co.rsk.bitcoinj.core.BtcECKey.fromPrivate(BigInteger.valueOf(102))),
            FederationMember.getFederationMemberFromKey(
                co.rsk.bitcoinj.core.BtcECKey.fromPrivate(BigInteger.valueOf(103))));
        return FederationFactory.buildStandardMultiSigFederation(new FederationArgs(
            members, java.time.Instant.ofEpochSecond(1_700_000_000L), 1L, bridgeConstants.getBtcParams()));
    }
}
