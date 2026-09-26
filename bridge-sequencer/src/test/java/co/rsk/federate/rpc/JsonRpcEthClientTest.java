package co.rsk.federate.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Driven against a real HTTP server rather than a mock, so that the request this builds and the
 * answer it parses are checked as bytes on a socket. A mocked client would only prove that this code
 * calls itself the way this code expects.
 */
class JsonRpcEthClientTest {

    private static final Address BRIDGE =
        Address.fromHexString("0x0000000000000000000000000000000001000006");

    private HttpServer server;
    private JsonRpcEthClient subject;
    private final List<String> requests = new ArrayList<>();
    private volatile Function<String, String> answer = request -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x\"}";
    private volatile int statusCode = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        subject =
            new JsonRpcEthClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(5));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aCallAsksTheChainHeadAndReturnsWhatCameBack() {
        answer = request -> result("\"0x00000000000000000000000000000000000000000000000000000000000007b1\"");

        Bytes result = subject.call(BRIDGE, Bytes.fromHexString("0x0c5a9990"));

        assertEquals(1, requests.size());
        String sent = requests.get(0);
        assertTrue(sent.contains("\"method\":\"eth_call\""), sent);
        assertTrue(sent.contains("\"to\":\"0x0000000000000000000000000000000001000006\""), sent);
        assertTrue(sent.contains("\"data\":\"0x0c5a9990\""), sent);
        assertTrue(sent.contains("\"latest\""), sent);
        assertEquals(0x7b1, result.toUnsignedBigInteger().intValueExact());
    }

    @Test
    void quantitiesComeBackAsNumbers() {
        answer = request -> result("\"0x1a4\"");
        assertEquals(420L, subject.blockNumber());

        answer = request -> result("\"0x0\"");
        assertEquals(0L, subject.blockNumber());
    }

    @Test
    void thePendingNonceIsAskedForPendingAndNotLatest() {
        // Asking for "latest" would hand out a nonce a transaction already in the pool has taken.
        answer = request -> result("\"0x5\"");

        assertEquals(5L, subject.pendingNonce(BRIDGE));
        assertTrue(requests.get(0).contains("\"pending\""), requests.get(0));
    }

    @Test
    void syncingIsFalseOnlyWhenTheNodeSaysFalse() {
        answer = request -> result("false");
        assertFalse(subject.syncing());

        answer = request -> result("{\"currentBlock\":\"0x1\",\"highestBlock\":\"0x99\"}");
        assertTrue(subject.syncing());
    }

    @Test
    void aMissingReceiptIsAnAbsentOneAndNotAFailure() {
        answer = request -> result("null");

        assertTrue(
            subject
                .receipt(EthClient.Bytes32Hash.fromHexString(
                    "0x2222222222222222222222222222222222222222222222222222222222222222"))
                .isEmpty());
    }

    @Test
    void aReceiptSaysWhetherTheTransactionDidAnything() {
        answer = request -> result("{\"blockNumber\":\"0x10\",\"status\":\"0x1\"}");
        EthClient.Bytes32Hash hash = EthClient.Bytes32Hash.fromHexString(
            "0x2222222222222222222222222222222222222222222222222222222222222222");

        EthClient.TransactionReceipt receipt = subject.receipt(hash).orElseThrow();
        assertEquals(16L, receipt.blockNumber());
        assertTrue(receipt.successful());

        answer = request -> result("{\"blockNumber\":\"0x10\",\"status\":\"0x0\"}");
        assertFalse(subject.receipt(hash).orElseThrow().successful());
    }

    @Test
    void theNodesOwnErrorArrivesAsAnExceptionWithItsCode() {
        answer = request -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"nonce too low\"}}";

        RpcException thrown =
            assertThrows(RpcException.class, () -> subject.sendRawTransaction(Bytes.fromHexString("0xf801")));

        assertEquals(-32000, thrown.getCode());
        assertTrue(thrown.getMessage().contains("nonce too low"), thrown.getMessage());
    }

    @Test
    void anHttpFailureIsNotMistakenForAnAnswer() {
        statusCode = 503;
        answer = request -> "unavailable";

        assertThrows(RpcException.class, () -> subject.blockNumber());
    }

    @Test
    void ananswerThatIsNotJsonIsNotMistakenForAnAnswer() {
        answer = request -> "<html>proxy error</html>";

        assertThrows(RpcException.class, () -> subject.blockNumber());
    }

    @Test
    void anAnswerWithNoResultIsRefused() {
        answer = request -> "{\"jsonrpc\":\"2.0\",\"id\":1}";

        assertThrows(RpcException.class, () -> subject.blockNumber());
    }

    @Test
    void anUnreachableNodeIsAnRpcFailureAndNotSomethingElse() {
        JsonRpcEthClient nowhere =
            new JsonRpcEthClient(URI.create("http://127.0.0.1:1"), Duration.ofMillis(300));

        assertThrows(RpcException.class, nowhere::blockNumber);
    }

    @Test
    void everyRequestCarriesItsOwnId() {
        answer = request -> result("\"0x1\"");

        subject.blockNumber();
        subject.blockNumber();

        assertFalse(
            requests.get(0).equals(requests.get(1)),
            "two requests must not be byte-identical: their ids differ");
    }

    private static String result(final String json) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + json + "}";
    }

    private void handle(final HttpExchange exchange) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(request);
        byte[] body = answer.apply(request).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
