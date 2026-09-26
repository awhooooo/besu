package co.rsk.federate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import co.rsk.federate.rpc.EthClient;
import co.rsk.federate.rpc.RpcException;
import co.rsk.federate.signing.ECDSASignerFromFileKey;
import co.rsk.federate.signing.KeyId;
import co.rsk.federate.signing.SignerException;
import co.rsk.federate.tx.LegacyTransactionSigner;
import co.rsk.peg.BridgeMethods;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The transaction pool used to keep the nonce in order; out of process this has to. These check the
 * two ways that goes wrong: handing out the same nonce twice, and carrying on from a number the node
 * never accepted.
 */
class BridgeClientTest {

    private static final BigInteger CHAIN_ID = BigInteger.valueOf(33);
    private static final String RSK_KEY =
        "505334c7745df2fc61486dffb900784505776a898377172ffa77384892749179";

    @TempDir Path keys;

    private RecordingNode node;
    private LegacyTransactionSigner transactionSigner;

    @BeforeEach
    void setUp() throws IOException, SignerException {
        Path keyFile = keys.resolve("rsk.key");
        Files.writeString(keyFile, RSK_KEY);
        Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ));

        node = new RecordingNode();
        transactionSigner =
            new LegacyTransactionSigner(
                new ECDSASignerFromFileKey(new KeyId("RSK"), keyFile.toString()),
                new KeyId("RSK"),
                CHAIN_ID);
    }

    @Test
    void theNodeIsAskedForTheNonceOnceAndThenCountedForward() throws SignerException {
        node.pendingNonce = 11;
        BridgeClient subject = client(GasPolicy.alwaysPaid(BigInteger.TEN));

        subject.send(BridgeMethods.UPDATE_COLLECTIONS);
        subject.send(BridgeMethods.UPDATE_COLLECTIONS);
        subject.send(BridgeMethods.UPDATE_COLLECTIONS);

        assertEquals(List.of(11L, 12L, 13L), noncesSent());
        assertEquals(1, node.nonceQueries, "the node should have been asked once, not once per send");
    }

    @Test
    void aRefusedTransactionMakesTheNextOneAskTheNodeAgain() throws SignerException {
        // The node may or may not have taken it. Carrying on from a guess would either replace a
        // transaction that was accepted or leave a gap that stalls every later one.
        node.pendingNonce = 4;
        node.refuseNextSend = true;
        BridgeClient subject = client(GasPolicy.alwaysPaid(BigInteger.TEN));

        assertThrows(RpcException.class, () -> subject.send(BridgeMethods.UPDATE_COLLECTIONS));

        node.pendingNonce = 4;
        subject.send(BridgeMethods.UPDATE_COLLECTIONS);

        assertEquals(List.of(4L), noncesSent());
        assertEquals(2, node.nonceQueries, "the node must be asked again after a refusal");
    }

    @Test
    void insideTheBootstrapWindowNothingIsOffered() throws SignerException {
        // The exemption waives the fee floor but not the up-front debit, so a federator holding
        // nothing must offer exactly zero or its own transaction is refused for a balance it never
        // needed.
        node.blockNumber = 40;
        BridgeClient subject = client(new GasPolicy(100, BigInteger.valueOf(999)));

        subject.send(BridgeMethods.UPDATE_COLLECTIONS);

        assertEquals(BigInteger.ZERO, sentTransaction(0).getGasPrice().orElseThrow().toBigInteger());
    }

    @Test
    void onceTheWindowHasClosedTheConfiguredPriceIsPaid() throws SignerException {
        node.blockNumber = 100;
        BridgeClient subject = client(new GasPolicy(100, BigInteger.valueOf(999)));

        subject.send(BridgeMethods.UPDATE_COLLECTIONS);

        assertEquals(BigInteger.valueOf(999), sentTransaction(0).getGasPrice().orElseThrow().toBigInteger());
    }

    @Test
    void theWindowIsJudgedOnTheBlockTheTransactionWillLandIn() throws SignerException {
        // Built at the last block of the window, it would be mined in the first block outside it.
        node.blockNumber = 99;
        BridgeClient subject = client(new GasPolicy(100, BigInteger.valueOf(999)));

        subject.send(BridgeMethods.UPDATE_COLLECTIONS);

        assertEquals(
            BigInteger.valueOf(999),
            sentTransaction(0).getGasPrice().orElseThrow().toBigInteger(),
            "a transaction built at block 99 lands in 100, where it must pay");
    }

    @Test
    void whatIsSentGoesToTheBridgeCarriesNoValueAndRecoversToTheFederator() throws SignerException {
        BridgeClient subject = client(GasPolicy.alwaysPaid(BigInteger.TEN));

        subject.send(BridgeMethods.UPDATE_COLLECTIONS);

        Transaction sent = sentTransaction(0);
        assertEquals(
            Address.fromHexString("0x0000000000000000000000000000000001000006"),
            sent.getTo().orElseThrow());
        assertEquals(BigInteger.ZERO, sent.getValue().toBigInteger());
        assertEquals(subject.senderAddress(), sent.getSender());
        assertEquals(
            BridgeMethods.UPDATE_COLLECTIONS.getFunction().encode(), sent.getPayload());
    }

    @Test
    void aCallIsDecodedAsTheMethodSaysItShouldBe() {
        // getBtcBlockchainBestChainHeight returns an int; 0x7b1 is 1969.
        node.callResult =
            Bytes.fromHexString("0x00000000000000000000000000000000000000000000000000000000000007b1");
        BridgeClient subject = client(GasPolicy.alwaysPaid(BigInteger.TEN));

        Object result = subject.callOne(BridgeMethods.GET_BTC_BLOCKCHAIN_BEST_CHAIN_HEIGHT);

        assertEquals(BigInteger.valueOf(1969), new BigInteger(result.toString()));
    }

    @Test
    void aSyncingNodeIsNotWorthActingOn() {
        BridgeClient subject = client(GasPolicy.alwaysPaid(BigInteger.TEN));

        node.syncing = false;
        assertTrue(subject.nodeIsUsable());
        node.syncing = true;
        assertFalse(subject.nodeIsUsable());
    }

    @Test
    void aGasPolicyRefusesNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new GasPolicy(-1, BigInteger.ONE));
        assertThrows(IllegalArgumentException.class, () -> new GasPolicy(1, BigInteger.valueOf(-1)));
        assertThrows(IllegalArgumentException.class, () -> new GasPolicy(1, null));
    }

    @Test
    void aClientRefusesAGasLimitThatCannotPayForAnything() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new BridgeClient(node, transactionSigner, GasPolicy.alwaysPaid(BigInteger.ONE), 0));
    }

    // ------------------------------------------------------------------ helpers

    private BridgeClient client(final GasPolicy gasPolicy) {
        return new BridgeClient(node, transactionSigner, gasPolicy, 500_000);
    }

    private List<Long> noncesSent() {
        List<Long> nonces = new ArrayList<>();
        for (Bytes raw : node.sent) {
            nonces.add(Transaction.readFrom(raw).getNonce());
        }
        return nonces;
    }

    private Transaction sentTransaction(final int index) {
        return Transaction.readFrom(node.sent.get(index));
    }

    /** A node that records what it was asked and answers what the test told it to. */
    private static final class RecordingNode implements EthClient {
        private final List<Bytes> sent = new ArrayList<>();
        private long blockNumber = 1;
        private long pendingNonce = 0;
        private boolean syncing = false;
        private boolean refuseNextSend = false;
        private int nonceQueries = 0;
        private Bytes callResult = Bytes.EMPTY;

        @Override
        public Bytes call(final Address to, final Bytes callData) {
            return callResult;
        }

        @Override
        public Bytes32Hash sendRawTransaction(final Bytes signedTransaction) {
            if (refuseNextSend) {
                refuseNextSend = false;
                throw new RpcException(-32000, "nonce too low");
            }
            sent.add(signedTransaction);
            return Bytes32Hash.fromHexString(
                "0x1111111111111111111111111111111111111111111111111111111111111111");
        }

        @Override
        public long blockNumber() {
            return blockNumber;
        }

        @Override
        public long pendingNonce(final Address address) {
            nonceQueries++;
            return pendingNonce;
        }

        @Override
        public boolean syncing() {
            return syncing;
        }

        @Override
        public List<LogEntry> logs(final LogFilter filter) {
            return List.of();
        }

        @Override
        public Optional<TransactionReceipt> receipt(final Bytes32Hash transactionHash) {
            return Optional.empty();
        }
    }
}
